@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.github.gildor.koog.nativeagents

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.config.AIAgentConfig
import ai.koog.agents.core.agent.entity.AIAgentStateManager
import ai.koog.agents.core.agent.entity.AIAgentStorage
import ai.koog.agents.core.agent.execution.AgentExecutionInfo
import ai.koog.agents.core.dsl.builder.node
import ai.koog.agents.core.dsl.builder.strategy
import ai.koog.agents.testing.feature.testGraph
import ai.koog.prompt.dsl.prompt
import com.fasterxml.jackson.databind.ObjectMapper
import com.github.copilot.CopilotSession
import com.github.copilot.generated.AssistantMessageEvent
import com.github.copilot.generated.SessionEvent
import com.github.copilot.rpc.MessageOptions
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.Mockito.*
import java.io.Closeable
import java.util.concurrent.CompletableFuture
import java.util.function.Consumer
import kotlin.test.*

class CopilotStepTest {
    private class Fixture {
        val session = mock(CopilotSession::class.java)
        val prompts = mutableListOf<String>()
        val pending = ArrayDeque<CompletableFuture<AssistantMessageEvent>>()
        var listener: Consumer<SessionEvent>? = null
        var unsubscribed = 0
        init {
            `when`(session.sessionId).thenReturn("native-123")
            `when`(session.on(any<Consumer<SessionEvent>>())).thenAnswer {
                listener = it.getArgument(0)
                Closeable { unsubscribed++; listener = null }
            }
            `when`(session.sendAndWait(any(MessageOptions::class.java), anyLong())).thenAnswer {
                prompts += it.getArgument<MessageOptions>(0).prompt
                pending.removeFirst()
            }
            `when`(session.abort()).thenReturn(CompletableFuture.completedFuture(null))
        }
        fun enqueue(text: String) { pending += CompletableFuture.completedFuture(message(text)) }
    }

    @Test
    fun testTwoTypedNodesContinueSameSessionWithoutKoogLlmCalls() = runTest {
        val f = Fixture().apply { enqueue("21"); enqueue("42") }
        val step = CopilotStep(f.session)
        val graph = strategy<String, Int>("native-workflow") {
            val first by copilotNode<String, Int>("first", step, { it }, String::toInt)
            val validate by node<Int, String>("validate") { value ->
                require(value == 21)
                "Double your previous answer"
            }
            val second by copilotNode<String, Int>("second", step, { it }, String::toInt)
            edge(nodeStart forwardTo first)
            edge(first forwardTo validate)
            edge(validate forwardTo second)
            edge(second forwardTo nodeFinish)
        }
        val agent = AIAgent(
            promptExecutor = NativeOnlyExecutor,
            strategy = graph,
            agentConfig = AIAgentConfig(prompt("native") {}, nativeOnlyModel, maxAgentIterations = 10),
        )
        try {
            assertEquals(42, agent.run("Answer 21"))
        } finally {
            agent.close()
        }
        assertEquals(listOf("Answer 21", "Double your previous answer"), f.prompts)
        assertEquals("native-123", step.sessionId)
        assertEquals(2, f.unsubscribed)
        verify(f.session, never()).abort()
    }

    @Test
    fun testKoogTestingFeatureUnderstandsSdkNodeAndTypedEdges() = runTest {
        val f = Fixture().apply { enqueue("17"); enqueue("17") }
        val step = CopilotStep(f.session)
        val graph = strategy<String, Int>("conformance") {
            val native by copilotNode<String, Int>("native", step, { it }, String::toInt)
            edge(nodeStart forwardTo native)
            edge(native forwardTo nodeFinish)
        }
        val agent = AIAgent(
            promptExecutor = NativeOnlyExecutor,
            strategy = graph,
            agentConfig = AIAgentConfig(prompt("test") {}, nativeOnlyModel, maxAgentIterations = 10),
        ) {
            testGraph<String, Int>("conformance") {
                val native = assertNodeByName<String, Int>("native")
                assertEdges {
                    startNode() alwaysGoesTo native
                    native alwaysGoesTo finishNode()
                }
                assertNodes {
                    // agents-test 1.3.0 copies these fields before executing the
                    // assertion; its default dummy context leaves them unset.
                    config = AIAgentConfig(prompt("test") {}, nativeOnlyModel, maxAgentIterations = 10)
                    stateManager = AIAgentStateManager()
                    storage = mock(AIAgentStorage::class.java)
                    executionInfo = AgentExecutionInfo(null, "conformance")
                    native withInput "Return 17" outputs 17
                }
            }
        }
        try { agent.run("Return 17") } finally { agent.close() }
    }

    @Test
    fun testStreamingArrivesBeforeTurnCompletion() = runTest {
        val f = Fixture()
        val pending = CompletableFuture<AssistantMessageEvent>()
        f.pending += pending
        val events = mutableListOf<SessionEvent>()
        val step = CopilotStep(f.session, onEvent = Consumer { events += it })
        val turn = async { step.turn("hello") { it } }
        runCurrent()
        val event = message("partial")
        f.listener!!.accept(event)
        assertSame(event, events.single())
        assertFalse(turn.isCompleted)
        pending.complete(message("final"))
        assertEquals("final", turn.await())
        assertEquals(1, f.unsubscribed)
    }

    @Test
    fun testConcurrentNodesDoNotOverlapTurns() = runTest {
        val f = Fixture()
        val pending = CompletableFuture<AssistantMessageEvent>()
        f.pending += pending
        f.enqueue("second")
        val step = CopilotStep(f.session)
        val first = async { step.turn("one") { it } }
        runCurrent()
        val second = async { step.turn("two") { it } }
        runCurrent()
        assertEquals(listOf("one"), f.prompts)
        pending.complete(message("first"))
        assertEquals("first", first.await())
        assertEquals("second", second.await())
    }

    @Test
    fun testCancellationAbortsNativeTurnAndRequiresRecovery() = runTest {
        val f = Fixture().apply { pending += CompletableFuture() }
        val step = CopilotStep(f.session)
        val job = launch { step.turn("long task") { it } }
        runCurrent()
        job.cancelAndJoin()
        verify(f.session).abort()
        assertEquals(1, f.unsubscribed)
        assertFailsWith<IllegalStateException> { step.turn("do not replay") { it } }
        assertEquals(listOf("long task"), f.prompts)
    }

    @Test
    fun testCancellingQueuedTurnDoesNotAbortActiveTurn() = runTest {
        val f = Fixture()
        val pending = CompletableFuture<AssistantMessageEvent>()
        f.pending += pending
        val step = CopilotStep(f.session)
        val first = async { step.turn("active") { it } }
        runCurrent()
        val queued = launch { step.turn("queued") { it } }
        runCurrent()
        queued.cancelAndJoin()
        verify(f.session, never()).abort()
        pending.complete(message("done"))
        assertEquals("done", first.await())
    }

    @Test
    fun testInvalidTypedResultFailsWithoutReplayingCompletedTurn() = runTest {
        val f = Fixture().apply { enqueue("invalid"); enqueue("42") }
        val step = CopilotStep(f.session)
        assertFailsWith<NumberFormatException> { step.turn("number", String::toInt) }
        assertEquals(42, step.turn("explicit correction", String::toInt))
        verify(f.session, never()).abort()
        assertEquals(2, f.prompts.size)
    }

    @Test
    fun testProviderFailureIsNotSilentlyRetried() = runTest {
        val failure = IllegalStateException("quota exhausted")
        val f = Fixture().apply { pending += CompletableFuture.failedFuture(failure) }
        val step = CopilotStep(f.session)
        assertEquals(failure.message, assertFailsWith<IllegalStateException> { step.turn("task") { it } }.message)
        verify(f.session).abort()
        assertEquals(1, f.unsubscribed)
        assertFailsWith<IllegalStateException> { step.turn("retry") { it } }
    }

    @Test
    fun testMissingResponseIsAnError() = runTest {
        val f = Fixture().apply { pending += CompletableFuture.completedFuture(null) }
        assertFailsWith<IllegalArgumentException> { CopilotStep(f.session).turn("task") { it } }
        assertEquals(1, f.unsubscribed)
    }

    @Test
    fun testTimeoutAbortsWithoutReplaying() = runTest {
        val f = Fixture().apply { pending += CompletableFuture() }
        val step = CopilotStep(f.session, timeoutMillis = 50)
        assertFailsWith<kotlinx.coroutines.TimeoutCancellationException> { step.turn("slow") { it } }
        verify(f.session).abort()
        assertEquals(1, f.unsubscribed)
        assertFailsWith<IllegalStateException> { step.turn("retry") { it } }
        assertEquals(listOf("slow"), f.prompts)
    }

    @Test
    fun testAbortFailurePreservesOriginalFailure() = runTest {
        val f = Fixture().apply { pending += CompletableFuture.failedFuture(IllegalStateException("transport lost")) }
        `when`(f.session.abort()).thenReturn(CompletableFuture.failedFuture(IllegalArgumentException("abort lost")))
        val error = assertFailsWith<IllegalStateException> { CopilotStep(f.session).turn("task") { it } }
        assertEquals("transport lost", error.message)
        assertEquals("abort lost", error.suppressed.single().message)
        assertEquals(1, f.unsubscribed)
    }

    @Test
    fun testNonPositiveTimeoutRejectedBeforeAnyNativeCall() {
        val session = mock(CopilotSession::class.java)
        assertFailsWith<IllegalArgumentException> { CopilotStep(session, timeoutMillis = 0) }
        verifyNoInteractions(session)
    }

    companion object {
        private val json = ObjectMapper()
        fun message(content: String): AssistantMessageEvent = json.readValue(
            json.writeValueAsString(mapOf("type" to "assistant.message", "data" to mapOf("content" to content))),
            SessionEvent::class.java,
        ) as AssistantMessageEvent
    }
}
