@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.github.gildor.koog.nativeagents

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.config.AIAgentConfig
import ai.koog.agents.core.dsl.builder.strategy
import ai.koog.prompt.dsl.prompt
import com.fasterxml.jackson.databind.JsonNode
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.*

class NativeBridgeTest {
    private fun process(
        event: (JsonNode) -> Unit = {},
        callback: suspend (JsonNode) -> JsonNode = { error("Unexpected callback") },
    ) = JsonLineProcess(listOf("node", File("bridges/test/fake-bridge.mjs").absolutePath), File("."), callback, event)

    @Test
    fun testRealProcessInTypedKoogGraphAndNativeSessionReuse() = runBlocking<Unit> {
        withTimeout(10_000) {
            val step = NativeBridgeStep(process())
            try {
                step.open(obj())
                val graph = strategy<String, Int>("bridge-test") {
                    val first by nativeNode<String, Int>("first", step, { it }, String::toInt)
                    val second by nativeNode<Int, Int>("second", step, { (it * 2).toString() }, String::toInt)
                    edge(nodeStart forwardTo first); edge(first forwardTo second); edge(second forwardTo nodeFinish)
                }
                val agent = AIAgent(promptExecutor = NativeOnlyExecutor, strategy = graph,
                    agentConfig = AIAgentConfig(prompt("test") {}, nativeOnlyModel, maxAgentIterations = 10))
                try { assertEquals(42, agent.run("21")) } finally { agent.close() }
                assertEquals("fixture-native", step.sessionId)
            } finally { step.shutdown() }
        }
    }

    @Test
    fun testNativeApprovalAndStreamingCrossProcessWithoutPolicyConversion() = runBlocking<Unit> {
        withTimeout(10_000) {
            val event = CompletableDeferred<JsonNode>()
            val answer = obj("permissions" to obj(), "scope" to "turn", "customNativeValue" to listOf(false, 7))
            val transport = process(event = { event.complete(it) }, callback = {
                assertEquals("native/approval", it["method"].asText())
                assertEquals(obj("reason" to "test", "arbitrary" to listOf(false, 7)), it["params"])
                assertEquals("before", event.await()["event"]["text"].asText())
                answer
            })
            val step = NativeBridgeStep(transport)
            try {
                step.open(obj())
                assertEquals(answer, step.turn("callback", bridgeJson::readTree))
            } finally { step.shutdown() }
        }
    }

    @Test
    fun testNativeCallbackFailureRejectsTurnAndStopsTransport() = runBlocking<Unit> {
        withTimeout(10_000) {
            val step = NativeBridgeStep(process(callback = { error("host declined to answer") }))
            try {
                step.open(obj())
                val failure = assertFailsWith<IllegalStateException> { step.turn("callback") { it } }
                assertContains(failure.message.orEmpty(), "host declined")
                assertFailsWith<IllegalStateException> { step.turn("retry") { it } }
            } finally { step.shutdown() }
        }
    }

    @Test
    fun testProcessDeathRejectsPendingTurnWithoutReplay() = runBlocking<Unit> {
        withTimeout(10_000) {
            val step = NativeBridgeStep(process())
            try {
                step.open(obj())
                assertFailsWith<IllegalStateException> { step.turn("crash") { it } }
                assertFailsWith<IllegalStateException> { step.turn("retry") { it } }
            } finally { step.shutdown() }
        }
    }

    @Test
    fun testMalformedTransportOutputFailsInsteadOfBecomingAssistantText() = runBlocking<Unit> {
        withTimeout(10_000) {
            val step = NativeBridgeStep(process())
            try {
                step.open(obj())
                assertFailsWith<com.fasterxml.jackson.core.JsonParseException> { step.turn("malformed") { it } }
            } finally { step.shutdown() }
        }
    }

    @Test
    fun testInvalidOutputAllowsExplicitCorrectionWithoutAutomaticRetry() = runBlocking<Unit> {
        withTimeout(10_000) {
            val step = NativeBridgeStep(process())
            try {
                step.open(obj())
                assertFailsWith<NumberFormatException> { step.turn("invalid", String::toInt) }
                assertEquals(42, step.turn("42", String::toInt))
            } finally { step.shutdown() }
        }
    }

    private class Transport : BridgeTransport {
        val calls = mutableListOf<String>()
        val turn = CompletableDeferred<JsonNode>()
        var closes = 0
        var abortFailure: Exception? = null
        override suspend fun request(method: String, params: JsonNode): JsonNode {
            calls += method
            if (method == "turn") return turn.await()
            if (method == "interrupt") abortFailure?.let { throw it }
            return obj()
        }
        override fun close() { closes++ }
    }

    @Test
    fun testCancellationAbortsThenClosesAndCannotReplay() = runTest {
        val transport = Transport(); val step = NativeBridgeStep(transport)
        val job = launch { step.turn("task") { it } }; runCurrent()
        job.cancelAndJoin()
        assertEquals(listOf("turn", "interrupt"), transport.calls)
        assertEquals(1, transport.closes)
        assertFailsWith<IllegalStateException> { step.turn("retry") { it } }
        step.shutdown(); step.shutdown()
        assertEquals(1, transport.closes)
    }

    @Test
    fun testQueuedCancellationDoesNotInterruptActiveTurn() = runTest {
        val transport = Transport(); val step = NativeBridgeStep(transport)
        val first = async { step.turn("first") { it } }; runCurrent()
        val second = launch { step.turn("second") { it } }; runCurrent()
        second.cancelAndJoin()
        assertEquals(listOf("turn"), transport.calls)
        transport.turn.complete(obj("text" to "done", "sessionId" to "saved"))
        assertEquals("done", first.await()); assertEquals("saved", step.sessionId)
        step.shutdown()
    }

    @Test
    fun testTimeoutPreservesAbortFailureAndClosesTransport() = runTest {
        val transport = Transport().apply { abortFailure = IllegalStateException("abort failed") }
        val step = NativeBridgeStep(transport, timeoutMillis = 50)
        val failure = assertFailsWith<TimeoutCancellationException> { step.turn("task") { it } }
        assertEquals("abort failed", failure.suppressed.single().message)
        assertEquals(1, transport.closes)
        step.shutdown()
    }

    @Test
    fun testConcurrentTurnsAreSerialized() = runTest {
        val transport = Transport(); val step = NativeBridgeStep(transport)
        val first = async { step.turn("one") { it } }; runCurrent()
        val second = async { step.turn("two") { it } }; runCurrent()
        assertEquals(listOf("turn"), transport.calls)
        transport.turn.complete(obj("text" to "done"))
        assertEquals("done", first.await()); assertEquals("done", second.await())
        assertEquals(listOf("turn", "turn"), transport.calls)
        step.shutdown(); step.shutdown()
        assertEquals(1, transport.closes)
        assertFailsWith<IllegalStateException> { step.turn("closed") { it } }
    }
}
