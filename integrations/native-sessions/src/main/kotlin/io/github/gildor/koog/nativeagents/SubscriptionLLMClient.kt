package io.github.gildor.koog.nativeagents

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.prompt.Prompt
import ai.koog.prompt.dsl.ModerationResult
import ai.koog.prompt.executor.clients.LLMClient
import ai.koog.prompt.executor.clients.LLMClientException
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.message.ResponseMetaInfo
import ai.koog.prompt.params.LLMParams
import ai.koog.utils.time.KoogClock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/** One stateless request to a native runtime: a fresh session that runs a single turn. */
data class NativeTurnRequest(
    val model: String,
    /** Koog system messages, joined; null when the prompt has none. */
    val system: String?,
    val prompt: String,
    /** JSON schema the runtime should enforce, when Koog asked for native structured output. */
    val schema: JsonObject?,
)

data class NativeTurnResult(
    val text: String,
    val inputTokens: Int? = null,
    val outputTokens: Int? = null,
    /** Model ids the runtime reports having used. */
    val modelId: String? = null,
    /** Runtime-specific numbers worth keeping (duration, list-price estimate, …). */
    val metadata: JsonObject? = null,
)

/** Runs [NativeTurnRequest]s on one kind of native runtime. Implementations own session lifecycle. */
interface NativeTurnRunner : AutoCloseable {
    val provider: LLMProvider
    suspend fun turn(request: NativeTurnRequest): NativeTurnResult
    override fun close() = Unit
}

/**
 * A Koog [LLMClient] backed by a subscription-logged-in native runtime (Claude
 * Agent SDK, Copilot SDK). Koog owns history, strategy, structured output and
 * tracing; each [execute] is one turn in a fresh native session that only
 * produces text. Tool-free by design: the runtimes run their own tool loops,
 * which cannot share ownership with Koog's, so any request carrying tools or
 * tool messages is rejected.
 */
class SubscriptionLLMClient(private val runner: NativeTurnRunner) : LLMClient() {

    override fun llmProvider(): LLMProvider = runner.provider

    override suspend fun execute(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): Message.Assistant {
        if (tools.isNotEmpty()) {
            throw LLMClientException(clientName, "tools are not supported by a native-session client: ${tools.map { it.name }}")
        }
        require(model.provider == runner.provider) { "Model ${model.id} belongs to ${model.provider.id}, not ${runner.provider.id}" }
        val request = render(prompt, model)
        val result = try {
            runner.turn(request)
        } catch (e: LLMClientException) {
            throw e
        } catch (e: Exception) {
            throw LLMClientException(clientName, e.message ?: e.javaClass.simpleName, e)
        }
        val meta = ResponseMetaInfo.create(
            KoogClock.System,
            totalTokensCount = listOfNotNull(result.inputTokens, result.outputTokens).takeIf { it.isNotEmpty() }?.sum(),
            inputTokensCount = result.inputTokens,
            outputTokensCount = result.outputTokens,
            modelId = result.modelId ?: model.id,
            metadata = result.metadata,
        )
        return Message.Assistant(result.text, meta)
    }

    override suspend fun moderate(prompt: Prompt, model: LLModel): ModerationResult =
        throw LLMClientException(clientName, "moderation is not supported by a native-session client")

    override fun close() = runner.close()

    companion object {
        /**
         * Flattens a Koog prompt into one native turn. A single user message is
         * sent as is; a longer history becomes a labelled transcript, since every
         * call starts a fresh native session.
         */
        fun render(prompt: Prompt, model: LLModel): NativeTurnRequest {
            val system = mutableListOf<String>()
            val turns = mutableListOf<Pair<String, String>>()
            for (message in prompt.messages) {
                val unsupported = message.parts.filter { it !is MessagePart.Text && it !is MessagePart.Reasoning }
                if (unsupported.isNotEmpty()) {
                    throw LLMClientException(
                        "SubscriptionLLMClient",
                        "only text messages are supported, got ${unsupported.map { it.javaClass.simpleName }.distinct()}",
                    )
                }
                val text = message.parts.filterIsInstance<MessagePart.Text>().joinToString("\n") { it.text }
                when (message.role) {
                    Message.Role.System -> system += text
                    Message.Role.User -> turns += "user" to text
                    Message.Role.Assistant -> turns += "assistant" to text
                    else -> throw LLMClientException("SubscriptionLLMClient", "unsupported message role ${message.role}")
                }
            }
            if (turns.isEmpty()) throw LLMClientException("SubscriptionLLMClient", "prompt has no user message")
            val body = if (turns.size == 1 && turns[0].first == "user") {
                turns[0].second
            } else {
                buildString {
                    appendLine("Conversation so far:")
                    for ((role, text) in turns) {
                        appendLine()
                        appendLine("[$role]")
                        appendLine(text.trim())
                    }
                    appendLine()
                    append("Write the next assistant message.")
                }
            }
            val schema = (prompt.params.schema as? LLMParams.Schema.JSON)?.schema
            return NativeTurnRequest(model.id, system.joinToString("\n\n").ifBlank { null }, body, schema)
        }

        /** A model entry for [runner]. [structuredOutput] when the runtime enforces a JSON schema. */
        fun model(provider: LLMProvider, id: String, structuredOutput: Boolean, contextLength: Long = 200_000) = LLModel(
            provider = provider,
            id = id,
            capabilities = buildList {
                add(LLMCapability.Completion)
                if (structuredOutput) add(LLMCapability.Schema.JSON.Standard)
            },
            contextLength = contextLength,
        )
    }
}

/**
 * Koog schemas are `{"$id", "$ref": "#/$defs/<root>", "$defs"}`. Runtimes that
 * turn an output schema into a tool input schema need `"type": "object"` at
 * the root, so the referenced root definition is inlined and `$defs` kept.
 */
fun rootObjectSchema(schema: JsonObject): JsonObject {
    if ("type" in schema) return schema
    val ref = (schema["\$ref"] as? JsonPrimitive)?.contentOrNull ?: return schema
    val defs = schema["\$defs"] as? JsonObject ?: return schema
    val root = defs[ref.removePrefix("#/\$defs/")] as? JsonObject ?: return schema
    return JsonObject(root + ("\$defs" to defs))
}

internal fun usageMetadata(vararg values: Pair<String, Number?>): JsonObject? =
    values.filter { it.second != null }.takeIf { it.isNotEmpty() }?.let { present ->
        buildJsonObject { for ((k, v) in present) put(k, v) }
    }
