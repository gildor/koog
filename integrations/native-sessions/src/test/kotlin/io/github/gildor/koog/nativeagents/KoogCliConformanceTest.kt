package io.github.gildor.koog.nativeagents

import ai.koog.agents.cli.CliAIAgent
import ai.koog.agents.cli.asNode
import ai.koog.agents.cli.transport.*
import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.config.AIAgentConfig
import ai.koog.agents.core.dsl.builder.strategy
import ai.koog.prompt.dsl.prompt
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.*
import kotlin.time.Duration

/** Selected upstream CLI scenarios, using published agents-cli with a deterministic transport. */
class KoogCliConformanceTest {
    data class Request(val text: String)

    private class Transport(val output: String = "42", val available: Boolean = true) : CliTransport {
        val commands = mutableListOf<List<String>>()
        override suspend fun checkAvailability(binaryPath: String, workspace: String, timeout: Duration?) =
            if (available) CliAvailable else CliUnavailable("fixture unavailable")

        override fun execute(command: List<String>, workspace: String, env: Map<String, String>, timeout: Duration?) =
            flowOf<CliEvent>(CliEvent.Stdout(output), CliEvent.Exit(0)).also { commands += command }
    }

    private fun agent(transport: Transport) = CliAIAgent.builder(transport)
        .binaryPath("fixture-cli").llModel(nativeOnlyModel)
        .custom<Request, Int>()
        .generateRequest { it.text }
        .extractOutput { events, _ -> events.filterIsInstance<CliEvent.Stdout>().single().content.toInt() }
        .build()

    @Test
    fun testUpstreamCustomInputAndCliNodeComposition() = runTest {
        val transport = Transport()
        val cli = agent(transport)
        val graph = strategy<Request, Int>("cli-conformance") {
            val delegated by cli.asNode("delegated")
            edge(nodeStart forwardTo delegated)
            edge(delegated forwardTo nodeFinish)
        }
        val workflow = AIAgent(
            promptExecutor = NativeOnlyExecutor,
            strategy = graph,
            agentConfig = AIAgentConfig(prompt("cli") {}, nativeOnlyModel, maxAgentIterations = 10),
        )
        try {
            assertEquals(42, workflow.run(Request("Return 42")))
            assertEquals(listOf(listOf("fixture-cli", "Return 42")), transport.commands)
        } finally { workflow.close(); cli.close() }
    }

    @Test
    fun testUpstreamUnavailableCliFailsBeforeExecution() = runTest {
        val transport = Transport(available = false)
        val cli = agent(transport)
        try {
            assertFailsWith<CliNotFoundException> { cli.run(Request("Return 42")) }
            assertTrue(transport.commands.isEmpty())
        } finally { cli.close() }
    }

    @Test
    fun testUpstreamTypedOutputValidationFailsWithoutRetry() = runTest {
        val transport = Transport(output = "invalid")
        val cli = agent(transport)
        try {
            assertFailsWith<NumberFormatException> { cli.run(Request("Return 42")) }
            assertEquals(1, transport.commands.size)
        } finally { cli.close() }
    }
}
