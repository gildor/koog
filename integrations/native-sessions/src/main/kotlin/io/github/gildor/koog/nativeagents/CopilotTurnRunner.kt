package io.github.gildor.koog.nativeagents

import ai.koog.prompt.llm.LLMProvider
import com.github.copilot.CopilotClient
import com.github.copilot.SystemMessageMode
import com.github.copilot.generated.AssistantUsageEvent
import com.github.copilot.rpc.CopilotClientOptions
import com.github.copilot.rpc.PermissionHandler
import com.github.copilot.rpc.PermissionRequestResult
import com.github.copilot.rpc.SessionConfig
import com.github.copilot.rpc.SystemMessageConfig
import kotlinx.coroutines.future.await
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicLong

/** Provider id for GitHub Copilot sessions on the logged-in user. */
object CopilotSubscription : LLMProvider("copilot-subscription", "GitHub Copilot (logged-in user)")

/**
 * Model id [CopilotTurnRunner.DEFAULT_MODEL] leaves the choice to the CLI's configured default.
 *
 * Runs each turn in a fresh Copilot session (official Java SDK) on one shared,
 * lazily started client using the CLI's logged-in user. Sessions get no tools
 * and every permission request is rejected. Copilot has no output-schema
 * option, so a requested schema is appended to the prompt and decoding stays
 * with Koog.
 */
class CopilotTurnRunner(
    private val cliPath: String = System.getenv("KOOG_COPILOT_CLI") ?: "copilot",
    private val timeoutMillis: Long = 300_000,
) : NativeTurnRunner {

    override val provider = CopilotSubscription


    private val lock = Mutex()
    private var client: CopilotClient? = null
    private val workspace: Path by lazy { Files.createTempDirectory("koog-copilot-") }

    private suspend fun client(): CopilotClient = lock.withLock {
        client ?: CopilotClient(
            CopilotClientOptions().setCliPath(cliPath).setCwd(workspace.toString()).setUseLoggedInUser(true),
        ).also { c ->
            client = c
            withTimeout(STARTUP_MS) { c.start().await() }
            val auth = withTimeout(STARTUP_MS) { c.authStatus.await() }
            check(auth.isAuthenticated) { "Copilot CLI is not logged in; run `copilot login`" }
        }
    }

    override suspend fun turn(request: NativeTurnRequest): NativeTurnResult {
        val config = SessionConfig()
            .setWorkingDirectory(workspace.toString())
            .setAvailableTools(emptyList())
            .setTools(emptyList())
            .setOnPermissionRequest(rejectAll)
        if (request.model != DEFAULT_MODEL) config.setModel(request.model)
        request.system?.let { config.setSystemMessage(SystemMessageConfig().setMode(SystemMessageMode.REPLACE).setContent(it)) }
        val session = withTimeout(STARTUP_MS) { client().createSession(config).await() }
        val input = AtomicLong()
        val output = AtomicLong()
        try {
            val step = CopilotStep(session, timeoutMillis) { event ->
                if (event is AssistantUsageEvent) {
                    input.addAndGet(event.data.inputTokens() ?: 0)
                    output.addAndGet(event.data.outputTokens() ?: 0)
                }
            }
            val prompt = request.schema?.let { request.prompt + schemaInstruction(rootObjectSchema(it).toString()) } ?: request.prompt
            val text = step.turn(prompt) { it }
            return NativeTurnResult(
                text = text,
                inputTokens = input.get().toInt().takeIf { it > 0 },
                outputTokens = output.get().toInt().takeIf { it > 0 },
                modelId = request.model,
            )
        } finally {
            runCatching { session.close() }
        }
    }

    override fun close() {
        client?.let { runCatching { it.close() } }
        client = null
        runCatching { workspace.toFile().deleteRecursively() }
    }

    companion object {
        const val DEFAULT_MODEL = "default"
        private const val STARTUP_MS = 60_000L
        private val rejectAll = PermissionHandler { _, _ ->
            CompletableFuture.completedFuture(PermissionRequestResult.reject("No tools in this session"))
        }

        private fun schemaInstruction(schema: String) =
            "\n\nReply with one JSON object and nothing else, valid against this JSON Schema:\n$schema"
    }
}
