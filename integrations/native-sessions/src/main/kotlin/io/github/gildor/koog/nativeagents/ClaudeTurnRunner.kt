package io.github.gildor.koog.nativeagents

import ai.koog.prompt.llm.LLMProvider
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import java.io.File
import java.nio.file.Files
import java.util.ArrayDeque

/** Provider id for Claude Agent SDK sessions on a Claude subscription login. */
object ClaudeSubscription : LLMProvider("claude-subscription", "Claude (subscription login)")

/**
 * Runs each turn in a fresh Claude Agent SDK session through the Node bridge.
 * The session has no built-in tools, no settings sources or MCP servers, is
 * not persisted, and refuses every permission or elicitation request. A Koog
 * JSON schema becomes the SDK `outputFormat`, which the runtime enforces.
 *
 * With [requireSubscription] the session is refused unless the runtime reports
 * a Claude subscription on Anthropic's own API and no API-key auth; together
 * with [SCRUBBED_ENV] this keeps a stray API key from moving calls to API billing.
 */
class ClaudeTurnRunner(
    private val timeoutMillis: Long = 300_000,
    private val requireSubscription: Boolean = true,
    private val transportFactory: TransportFactory,
) : NativeTurnRunner {

    /** Starts one bridge process in [workspace]; [onEvent] gets native SDK messages. */
    fun interface TransportFactory {
        fun open(workspace: File, onEvent: (JsonNode) -> Unit, onStderr: (String) -> Unit): BridgeTransport
    }

    override val provider = ClaudeSubscription

    override suspend fun turn(request: NativeTurnRequest): NativeTurnResult {
        val workspace = Files.createTempDirectory("koog-claude-turn-").toFile()
        val stderr = ArrayDeque<String>()
        var result: JsonNode? = null
        try {
            val transport = transportFactory.open(
                workspace,
                onEvent = { packet -> packet["event"]?.takeIf { it.path("type").asText() == "result" }?.let { result = it } },
                onStderr = { line -> synchronized(stderr) { if (stderr.size == 20) stderr.removeFirst(); stderr.addLast(line) } },
            )
            val step = NativeBridgeStep(transport, timeoutMillis)
            try {
                val info = step.open(openOptions(workspace, request))
                if (requireSubscription) requireSubscriptionLogin(info)
                val text = step.turn(request.prompt) { it }
                val r = result
                val usage = r?.get("usage")
                return NativeTurnResult(
                    text = text,
                    inputTokens = usage?.int("input_tokens"),
                    outputTokens = usage?.int("output_tokens"),
                    modelId = r?.get("modelUsage")?.fieldNames()?.asSequence()?.joinToString("+")?.ifBlank { null },
                    metadata = usageMetadata(
                        "durationMs" to r?.get("duration_ms")?.takeIf { it.isNumber }?.asLong(),
                        "cacheReadInputTokens" to usage?.int("cache_read_input_tokens"),
                        "cacheCreationInputTokens" to usage?.int("cache_creation_input_tokens"),
                        "listPriceUsd" to r?.get("total_cost_usd")?.takeIf { it.isNumber }?.asDouble(),
                    ),
                )
            } catch (e: Exception) {
                val tail = synchronized(stderr) { stderr.joinToString("\n") }
                if (tail.isBlank()) throw e
                throw IllegalStateException("${e.message}\n$tail", e)
            } finally {
                runCatching { step.shutdown() }
            }
        } finally {
            workspace.deleteRecursively()
        }
    }

    internal fun openOptions(workspace: File, request: NativeTurnRequest): JsonNode = bridgeJson.createObjectNode().apply {
        put("cwd", workspace.absolutePath)
        put("model", request.model)
        putArray("tools")
        putObject("native").apply {
            putArray("tools")
            putArray("settingSources")
            put("permissionMode", "default")
            put("persistSession", false)
            request.system?.let { put("systemPrompt", it) }
            request.schema?.let { schema ->
                putObject("outputFormat").apply {
                    put("type", "json_schema")
                    set<JsonNode>("schema", bridgeJson.readTree(rootObjectSchema(schema).toString()))
                }
            }
        }
    }

    companion object {
        /** Variables that make the Claude runtime bill the API or a cloud provider instead of the login. */
        val SCRUBBED_ENV: Map<String, String?> = listOf(
            "ANTHROPIC_API_KEY",
            "ANTHROPIC_AUTH_TOKEN",
            "ANTHROPIC_BASE_URL",
            "CLAUDE_CODE_USE_BEDROCK",
            "CLAUDE_CODE_USE_VERTEX",
            "CLAUDE_CODE_USE_FOUNDRY",
        ).associateWith { null }

        /**
         * A bridge-backed factory: `node <bridge> claude` with [SCRUBBED_ENV], every
         * native request refused. [claudeCli] pins the runtime (else the SDK's bundled one).
         */
        fun nodeBridge(node: File, bridgeScript: File, claudeCli: File? = null) = TransportFactory { workspace, onEvent, onStderr ->
            JsonLineProcess(
                command = listOf(node.absolutePath, bridgeScript.absolutePath, "claude"),
                cwd = workspace,
                onNativeRequest = ::refuseNativeRequest,
                onEvent = onEvent,
                onStderr = onStderr,
                environment = SCRUBBED_ENV + listOfNotNull(claudeCli?.let { "KOOG_CLAUDE_CLI" to it.absolutePath }),
            )
        }

        fun requireSubscriptionLogin(info: JsonNode) {
            fun text(key: String) = info.get(key)?.takeUnless { it.isNull }?.asText()?.takeIf { it.isNotBlank() }
            val authType = text("authType")
            val apiProvider = text("apiProvider")
            if (authType != null || (apiProvider != null && apiProvider != "firstParty")) {
                throw IllegalStateException(
                    "Claude runtime is not on a subscription login (authType=$authType, apiProvider=$apiProvider)",
                )
            }
            if (text("subscriptionType") == null) {
                throw IllegalStateException("Claude runtime reports no subscription login; run `claude auth login`")
            }
        }

        private fun refuseNativeRequest(request: JsonNode): JsonNode = when (val method = request.path("method").asText()) {
            "canUseTool" -> (bridgeJson.createObjectNode() as ObjectNode).put("behavior", "deny").put("message", "No tools in this session")
            "onElicitation" -> bridgeJson.createObjectNode().put("action", "decline")
            else -> throw IllegalStateException("unexpected native request $method")
        }

        private fun JsonNode.int(key: String): Int? = get(key)?.takeIf { it.isNumber }?.asInt()
    }
}
