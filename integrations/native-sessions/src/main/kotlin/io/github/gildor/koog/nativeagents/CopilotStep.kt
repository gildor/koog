package io.github.gildor.koog.nativeagents

import ai.koog.agents.core.dsl.builder.node
import ai.koog.agents.core.dsl.builder.AIAgentNodeDelegate
import com.github.copilot.CopilotSession
import com.github.copilot.generated.SessionEvent
import com.github.copilot.rpc.MessageOptions
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.future.await
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.function.Consumer

/**
 * Executes turns on a host-owned native session. The host configures authentication,
 * tools and native permission callbacks before constructing this adapter.
 *
 * One adapter must own all sends to the session. Turns are serialized; this class
 * never retries a turn. A failed/interrupted transport requires explicit recovery
 * by the host before constructing a replacement adapter for the resumed session.
 */
class CopilotStep(
    private val session: CopilotSession,
    private val timeoutMillis: Long = 120_000,
    private val onEvent: Consumer<SessionEvent> = Consumer {},
) {
    init { require(timeoutMillis > 0) }

    private val mutex = Mutex()
    private var recoveryRequired = false

    /** Native conversation identity, separate from Koog's workflow/run identity. */
    val sessionId: String get() = session.sessionId

    /**
     * Waits for a complete native turn and validates its output through [decode].
     * Invalid output is not retried. A cancelled waiter explicitly aborts the native
     * turn, since cancelling a CompletableFuture alone does not stop the agent.
     * Event observers execute on the SDK's callback thread and must not block/throw.
     */
    suspend fun <Output> turn(prompt: String, decode: (String) -> Output): Output = mutex.withLock {
        check(!recoveryRequired) { "Native session requires explicit recovery; the previous turn was not replayed" }
        val subscription = session.on(onEvent)
        val response = try {
            withTimeout(timeoutMillis) {
                session.sendAndWait(MessageOptions().setPrompt(prompt), timeoutMillis).await()
            }
        } catch (failure: Exception) {
            recoveryRequired = true
            // Both a coroutine timeout and the SDK's own timeout can leave work running.
            try {
                withContext(NonCancellable) {
                    withTimeout(5_000) { session.abort().await() }
                }
            } catch (abortFailure: Exception) {
                failure.addSuppressed(abortFailure)
            }
            throw failure
        } finally {
            subscription.close()
        }
        val content = requireNotNull(response?.data?.content()) { "Native turn completed without a final response" }
        decode(content)
    }
}

/** A standard Koog node whose entire execution is one native SDK turn. */
inline fun <reified Input, reified Output> copilotNode(
    name: String,
    step: CopilotStep,
    noinline prompt: (Input) -> String,
    noinline decode: (String) -> Output,
): AIAgentNodeDelegate<Input, Output> = node(name) { input ->
    step.turn(prompt(input), decode)
}
