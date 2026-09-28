package io.github.gildor.koog.nativeagents

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.config.AIAgentConfig
import ai.koog.agents.core.dsl.builder.strategy
import ai.koog.prompt.dsl.prompt
import com.fasterxml.jackson.databind.JsonNode
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** Opt-in provider probes and Koog smoke/tool/history/process-resume jobs. */
fun main(args: Array<String>) = runBlocking {
    require(args.size >= 2) { "Usage: codex|claude|opencode probe|jobs [model]" }
    val provider = args[0]
    require(provider in setOf("codex", "claude", "opencode"))
    val action = args[1]
    require(action in setOf("probe", "jobs"))
    val model = args.getOrNull(2)
    val bridge = Path.of("bridges/main.mjs").toAbsolutePath().toString()
    val workspace = Files.createTempDirectory("koog-$provider-").toFile()
    val code = UUID.randomUUID().toString()
    val expected = LookupResult("ORDER-17", code)
    val toolCalls = AtomicInteger()
    val permissions = AtomicInteger()
    val eventCounts = ConcurrentHashMap<String, AtomicInteger>()
    val errors = mutableListOf<String>()
    val tools = listOf(mapOf("name" to "lookup_reference", "description" to "Read verification code for a business reference",
        "inputSchema" to mapOf("type" to "object", "properties" to mapOf("reference" to mapOf("type" to "string")),
            "required" to listOf("reference"), "additionalProperties" to false)))

    // All decisions below belong to this read-only test host. The bridge returns
    // their native result shapes verbatim and contains no permission policy.
    suspend fun callback(request: JsonNode): JsonNode {
        val method = request["method"].asText()
        val params = request["params"]
        if (method == "item/tool/call" || method == "tool/call") {
            val name = params.get("tool")?.asText() ?: params.path("name").asText()
            require(name == "lookup_reference")
            require(params.path("arguments").path("reference").asText() == "ORDER-17")
            toolCalls.incrementAndGet()
            val content = Json.encodeToString(expected)
            return if (provider == "codex") obj("success" to true, "contentItems" to listOf(mapOf("type" to "inputText", "text" to content)))
            else obj("content" to listOf(mapOf("type" to "text", "text" to content)))
        }
        permissions.incrementAndGet()
        return when (method) {
            "canUseTool" -> if (params.path("toolName").asText() == "mcp__business__lookup_reference")
                obj("behavior" to "allow", "updatedInput" to params["input"])
                else obj("behavior" to "deny", "message" to "Fixture exposes only its read-only lookup")
            "item/commandExecution/requestApproval", "item/fileChange/requestApproval" -> obj("decision" to "decline")
            "onElicitation", "mcpServer/elicitation/request" -> obj("action" to "decline")
            "permission.asked" -> obj("response" to "reject")
            else -> error("No unattended fixture answer for native request $method")
        }
    }
    fun session() = NativeBridgeStep(JsonLineProcess(
        listOf("node", bridge, provider), workspace, ::callback,
        onEvent = { packet ->
            val event = packet["event"]
            val name = event.get("method")?.asText() ?: event.path("type").asText()
            eventCounts.computeIfAbsent(name) { AtomicInteger() }.incrementAndGet()
        },
        onStderr = { line -> synchronized(errors) { if (errors.size == 12) errors.removeAt(0); errors.add(line) } },
    ))
    fun options(id: String? = null): JsonNode {
        val native = when (provider) {
            "codex" -> mapOf("approvalPolicy" to "untrusted", "sandbox" to "read-only",
                "developerInstructions" to "Execute the provided fixture task. Return only requested JSON without Markdown fences.")
            "claude" -> mapOf("tools" to emptyList<String>(), "permissionMode" to "default", "settingSources" to emptyList<String>(),
                "systemPrompt" to mapOf("type" to "preset", "preset" to "claude_code", "append" to
                    "These automation jobs require a JSON object as the entire final answer. Never add Markdown fences or commentary."))
            else -> mapOf("permission" to mapOf("*" to "ask", "read" to "allow"))
        }
        return obj("cwd" to workspace.absolutePath, "model" to model, "sessionId" to id,
            "tools" to if (provider == "opencode") emptyList<Any>() else tools, "native" to native)
    }
    var active = session()
    try {
        val started = System.nanoTime()
        val info = active.open(options())
        emitNative(obj("job" to "probe", "provider" to provider, "info" to info, "startupMs" to millis(started)))
        if (action == "jobs") {
            require(!model.isNullOrBlank()) { "Live jobs require an explicit model from probe" }
            var start = System.nanoTime()
            check(runNativeGraph(active, "Return exactly this JSON: {\"answer\":42}. No Markdown fences or commentary.") { Json.decodeFromString<SmokeResult>(it) }.answer == 42)
            emitNative(obj("job" to "smoke", "provider" to provider, "success" to true, "durationMs" to millis(start)))
            start = System.nanoTime()
            val lookupPrompt = if (provider == "opencode") {
                workspace.resolve("fixture.json").writeText(Json.encodeToString(expected))
                "Read fixture.json in this workspace and return its JSON object exactly, without Markdown fences."
            } else "Call lookup_reference for ORDER-17 and return its result as JSON with reference and code fields. No Markdown fences."
            check(runNativeGraph(active, lookupPrompt) { Json.decodeFromString<LookupResult>(it) } == expected)
            if (provider == "opencode") check(workspace.resolve("fixture.json").delete())
            if (provider != "opencode") check(toolCalls.get() == 1)
            val id = requireNotNull(active.sessionId)
            check(runNativeGraph(active, "Without calling tools, return the same reference and code from the previous turn as JSON only.") {
                Json.decodeFromString<LookupResult>(it)
            } == expected)
            if (provider != "opencode") check(toolCalls.get() == 1)
            emitNative(obj("job" to "lookup-history", "provider" to provider, "success" to true,
                "sessionId" to id, "toolCalls" to toolCalls.get(), "durationMs" to millis(start)))
            // Resume a completed turn after replacing the entire bridge/runtime.
            // This does not claim recovery of an ambiguous, partially executed turn.
            active.shutdown()
            active = session()
            start = System.nanoTime()
            active.open(options(id))
            check(runNativeGraph(active, "Without calling tools, recall the reference and code we looked up. Return only that JSON.") {
                Json.decodeFromString<LookupResult>(it)
            } == expected)
            check(active.sessionId == id)
            if (provider != "opencode") check(toolCalls.get() == 1)
            emitNative(obj("job" to "process-resume", "provider" to provider, "success" to true, "durationMs" to millis(start)))
        }
        emitNative(obj("job" to "summary", "provider" to provider, "success" to true,
            "permissionCallbacks" to permissions.get(), "events" to eventCounts.mapValues { it.value.get() }))
    } catch (failure: Exception) {
        // Stderr is retained locally for diagnosis, never dumped into a report that
        // could accidentally expose provider configuration or user identifiers.
        val log = Path.of("build", "$provider-last-error.log")
        Files.createDirectories(log.parent)
        synchronized(errors) { Files.write(log, errors) }
        emitNative(obj("job" to "failure", "provider" to provider, "success" to false,
            "error" to "${failure.javaClass.simpleName}: ${failure.message}"))
        throw failure
    } finally { active.shutdown() }
}

private suspend inline fun <reified Output> runNativeGraph(step: NativeBridgeStep, input: String, noinline decode: (String) -> Output): Output {
    val graph = strategy<String, Output>("native-provider-job") {
        val work by nativeNode<String, Output>("work", step, { it }, decode)
        edge(nodeStart forwardTo work)
        edge(work forwardTo nodeFinish)
    }
    val agent = AIAgent(promptExecutor = NativeOnlyExecutor, strategy = graph,
        agentConfig = AIAgentConfig(prompt("native") {}, nativeOnlyModel, maxAgentIterations = 10))
    try { return agent.run(input) } finally { agent.close() }
}

private fun millis(start: Long) = (System.nanoTime() - start) / 1e6
private fun emitNative(record: JsonNode) = println(record.toString())
