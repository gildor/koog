package consumer

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.config.AIAgentConfig
import ai.koog.agents.core.dsl.builder.strategy
import ai.koog.prompt.dsl.prompt
import com.fasterxml.jackson.databind.ObjectMapper
import io.github.gildor.koog.nativeagents.JsonLineProcess
import io.github.gildor.koog.nativeagents.NativeBridgeStep
import io.github.gildor.koog.nativeagents.NativeOnlyExecutor
import io.github.gildor.koog.nativeagents.nativeNode
import io.github.gildor.koog.nativeagents.nativeOnlyModel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Runs from a different Gradle build/package with only the declared library dependency. */
class IncludedBuildTest {
    private suspend fun runJob(input: String): Int {
        val fixture = File(requireNotNull(System.getProperty("native.bridge.fixture")))
        val step = NativeBridgeStep(JsonLineProcess(
            command = listOf("node", fixture.absolutePath),
            cwd = File(System.getProperty("java.io.tmpdir")),
            onNativeRequest = { error("Unexpected fixture request") },
        ))
        try {
            step.open(ObjectMapper().createObjectNode())
            val graph = strategy<String, Int>("consumer") {
                val first by nativeNode<String, Int>("first", step, { it }, String::toInt)
                val second by nativeNode<Int, Int>("second", step, { (it * 2).toString() }, String::toInt)
                edge(nodeStart forwardTo first)
                edge(first forwardTo second)
                edge(second forwardTo nodeFinish)
            }
            val agent = AIAgent(
                promptExecutor = NativeOnlyExecutor,
                strategy = graph,
                agentConfig = AIAgentConfig(prompt("consumer") {}, nativeOnlyModel, maxAgentIterations = 10),
            )
            try { return agent.run(input) } finally { agent.close() }
        } finally { step.shutdown() }
    }

    @Test
    fun testIncludedLibraryRunsTypedGraphFromDifferentWorkingDirectory() = runBlocking<Unit> {
        withTimeout(10_000) { assertEquals(42, runJob("21")) }
    }

    @Test
    fun testInvalidNativeOutputFailsConsumerGraph() = runBlocking<Unit> {
        withTimeout(10_000) { assertFailsWith<NumberFormatException> { runJob("invalid") } }
    }
}
