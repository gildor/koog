package io.github.gildor.koog.nativeagents

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.config.AIAgentConfig
import ai.koog.agents.core.dsl.builder.strategy
import ai.koog.prompt.dsl.prompt
import com.fasterxml.jackson.databind.ObjectMapper
import com.github.copilot.CopilotClient
import com.github.copilot.CopilotSession
import com.github.copilot.SystemMessageMode
import com.github.copilot.generated.AssistantMessageDeltaEvent
import com.github.copilot.generated.AssistantUsageEvent
import com.github.copilot.generated.SessionEvent
import com.github.copilot.rpc.*
import kotlinx.coroutines.future.await
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.function.Consumer

private val wireJson = ObjectMapper()
private val resultJson = Json

/** Fixed typed output used by paired SDK/Koog smoke jobs. */
@Serializable
data class SmokeResult(val answer: Int)

/** Typed output whose secret can only be obtained through the Kotlin lookup tool. */
@Serializable
data class LookupResult(val reference: String, val code: String)

/**
 * Runs opt-in live jobs against the official SDK using the existing logged-in user.
 * `probe` performs no model call. `benchmark N MODEL` runs N paired smoke samples
 * plus a lookup and conversation follow-up. All metrics are JSON lines on stdout.
 */
fun main(args: Array<String>) = runBlocking {
    val action = args.firstOrNull() ?: "probe"
    require(action in setOf("probe", "benchmark"))
    val samples = args.getOrNull(1)?.toInt() ?: 1
    require(samples in 1..10)
    val workspace = Files.createTempDirectory("koog-native-job-")
    val options = CopilotClientOptions()
        .setCliPath(System.getenv("KOOG_COPILOT_CLI") ?: "copilot")
        .setCwd(workspace.toString())
        .setUseLoggedInUser(true)
    CopilotClient(options).use { client ->
        val started = System.nanoTime()
        withTimeout(30_000) { client.start().await() }
        val startupMs = elapsedMs(started)
        val auth = withTimeout(15_000) { client.authStatus.await() }
        val models = withTimeout(30_000) { client.listModels().await() }.map { it.id }
        emit(mapOf("job" to "probe", "authenticated" to auth.isAuthenticated,
            "authType" to auth.authType, "startupMs" to startupMs, "models" to models,
            "koog" to "1.3.0", "sdk" to "1.0.14"))
        check(auth.isAuthenticated) { "Copilot CLI is not authenticated" }
        if (action == "probe") return@use
        val model = requireNotNull(args.getOrNull(2)) { "Pass an explicit model ID from probe output" }
        require(model in models) { "Model is not in the authenticated runtime's catalog" }
        var failed = 0
        repeat(samples) { index ->
            val modes = if (index % 2 == 0) listOf("sdk", "koog") else listOf("koog", "sdk")
            for (mode in modes) {
                val metrics = runSmoke(client, workspace.toString(), model, mode, index)
                emit(metrics)
                if (metrics["success"] != true) failed++
            }
        }
        val lookup = runLookup(client, workspace.toString(), model)
        emit(lookup)
        if (lookup["success"] != true) failed++
        emit(mapOf("job" to "summary", "failures" to failed, "pairedSamples" to samples,
            "note" to "Feasibility samples, not statistically conclusive performance evidence"))
        check(failed == 0) { "$failed live jobs failed; their samples were retained" }
    }
}

private fun fixturePermissionHandler() = PermissionHandler { _, _ ->
    // Fixture-owned decision: these jobs expose no built-in tools. An unexpected
    // permission request is rejected, not converted into an approval by the adapter.
    CompletableFuture.completedFuture(PermissionRequestResult.reject("No interactive approval in this fixture"))
}

private fun config(workspace: String, model: String, tools: List<ToolDefinition> = emptyList()) = SessionConfig()
    .setModel(model)
    .setWorkingDirectory(workspace)
    .setStreaming(true)
    .setAvailableTools(tools.map { it.name() })
    .setTools(tools)
    .setOnPermissionRequest(fixturePermissionHandler())
    .setSystemMessage(SystemMessageConfig().setMode(SystemMessageMode.APPEND)
        .setContent("Follow this test job exactly. Return only the requested JSON, with no Markdown fences."))

private class Metrics : Consumer<SessionEvent> {
    val firstDeltaAt = AtomicLong(0)
    val inputTokens = AtomicLong(0)
    val outputTokens = AtomicLong(0)
    val usageEvents = AtomicInteger(0)
    @Volatile var byok: Boolean? = null
    override fun accept(event: SessionEvent) {
        if (event is AssistantMessageDeltaEvent) firstDeltaAt.compareAndSet(0, System.nanoTime())
        if (event is AssistantUsageEvent) {
            usageEvents.incrementAndGet()
            inputTokens.addAndGet(event.data.inputTokens() ?: 0)
            outputTokens.addAndGet(event.data.outputTokens() ?: 0)
            byok = event.data.isByok()
        }
    }
    fun values(turnStart: Long): Map<String, Any?> = mapOf(
        "firstDeltaMs" to firstDeltaAt.get().takeIf { it != 0L }?.let { (it - turnStart) / 1e6 },
        "inputTokens" to inputTokens.get().takeIf { usageEvents.get() > 0 },
        "outputTokens" to outputTokens.get().takeIf { usageEvents.get() > 0 },
        "isByok" to byok,
    )
}

private suspend fun runSmoke(client: CopilotClient, workspace: String, model: String, mode: String, index: Int): Map<String, Any?> {
    val start = System.nanoTime()
    var session: CopilotSession? = null
    var turnStart = start
    var createMs: Double? = null
    var cleanupError: String? = null
    val metrics = Metrics()
    val outcome = try {
        session = withTimeout(30_000) { client.createSession(config(workspace, model)).await() }
        createMs = elapsedMs(start)
        val request = "Return exactly this JSON object: {\"answer\":42}"
        turnStart = System.nanoTime()
        val result = if (mode == "sdk") {
            val subscription = session.on(metrics)
            try {
                val event = withTimeout(120_000) { session.sendAndWait(MessageOptions().setPrompt(request), 120_000).await() }
                resultJson.decodeFromString<SmokeResult>(requireNotNull(event?.data?.content()))
            } finally { subscription.close() }
        } else {
            val step = CopilotStep(session, onEvent = metrics)
            runGraph(step, request) { resultJson.decodeFromString<SmokeResult>(it) }
        }
        check(result.answer == 42)
        mapOf("success" to true, "sessionId" to session.sessionId)
    } catch (failure: Exception) {
        runCatching { session?.let { withTimeout(5_000) { it.abort().await() } } }
        mapOf("success" to false, "error" to "${failure.javaClass.simpleName}: ${failure.message}")
    } finally { cleanupError = closeFixture(session) }
    return outcome
        .plus(mapOf("job" to "smoke", "mode" to mode, "sample" to index, "model" to model,
            "createMs" to createMs, "turnMs" to elapsedMs(turnStart), "cleanupError" to cleanupError))
        .let { if (cleanupError == null) it else it + ("success" to false) }
        .plus(metrics.values(turnStart))
}

private suspend fun runLookup(client: CopilotClient, workspace: String, model: String): Map<String, Any?> {
    val secret = UUID.randomUUID().toString()
    val calls = AtomicInteger()
    val tool = ToolDefinition.createSkipPermission(
        "lookup_reference", "Read the verification code for a business reference",
        mapOf("type" to "object", "properties" to mapOf("reference" to mapOf("type" to "string")),
            "required" to listOf("reference"), "additionalProperties" to false),
    ) { invocation ->
        val reference = invocation.arguments["reference"] as? String
        require(reference == "ORDER-17") { "Unknown fixture reference" }
        calls.incrementAndGet()
        CompletableFuture.completedFuture(mapOf("reference" to reference, "code" to secret))
    }
    // This read-only fixture tool is explicitly approved by its host registration;
    // this is not a permission policy in CopilotStep.
    val session = withTimeout(30_000) { client.createSession(config(workspace, model, listOf(tool))).await() }
    val step = CopilotStep(session)
    val start = System.nanoTime()
    var cleanupError: String? = null
    val outcome = try {
        val first = runGraph(step,
            "Call lookup_reference for ORDER-17. Return its result as JSON with reference and code fields.",
        ) { resultJson.decodeFromString<LookupResult>(it) }
        check(first == LookupResult("ORDER-17", secret))
        check(calls.get() == 1)
        val followup = runGraph(step,
            "Without calling tools again, return the same reference and code from our previous turn as JSON.",
        ) { resultJson.decodeFromString<LookupResult>(it) }
        check(first == followup)
        check(calls.get() == 1) { "Follow-up used the tool instead of conversation history" }
        mapOf("job" to "lookup-and-followup", "success" to true, "toolCalls" to calls.get(),
            "sessionId" to step.sessionId, "model" to model, "durationMs" to elapsedMs(start))
    } catch (failure: Exception) {
        mapOf("job" to "lookup-and-followup", "success" to false,
            "error" to "${failure.javaClass.simpleName}: ${failure.message}", "toolCalls" to calls.get(),
            "model" to model, "durationMs" to elapsedMs(start))
    } finally { cleanupError = closeFixture(session) }
    return outcome + mapOf("cleanupError" to cleanupError) +
        (if (cleanupError == null) emptyMap() else mapOf("success" to false))
}

private fun closeFixture(session: CopilotSession?): String? = try {
    session?.close()
    null
} catch (failure: Exception) {
    "${failure.javaClass.simpleName}: ${failure.message}; cause=${failure.cause?.cause?.message}"
}

private suspend inline fun <reified Output> runGraph(step: CopilotStep, input: String, noinline decode: (String) -> Output): Output {
    val graph = strategy<String, Output>("native-job") {
        val native by copilotNode<String, Output>("native", step, { it }, decode)
        edge(nodeStart forwardTo native)
        edge(native forwardTo nodeFinish)
    }
    val agent = AIAgent(
        promptExecutor = NativeOnlyExecutor,
        strategy = graph,
        agentConfig = AIAgentConfig(prompt("native") {}, nativeOnlyModel, maxAgentIterations = 10),
    )
    try { return agent.run(input) } finally { agent.close() }
}

private fun elapsedMs(start: Long) = (System.nanoTime() - start) / 1e6
private fun emit(value: Map<String, Any?>) = println(wireJson.writeValueAsString(value))
