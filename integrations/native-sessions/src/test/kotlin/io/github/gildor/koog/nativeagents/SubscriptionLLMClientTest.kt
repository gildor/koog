package io.github.gildor.koog.nativeagents

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.clients.LLMClientException
import ai.koog.prompt.executor.llms.MultiLLMPromptExecutor
import ai.koog.prompt.message.Message
import ai.koog.prompt.params.LLMParams
import com.fasterxml.jackson.databind.JsonNode
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SubscriptionLLMClientTest {

    private class FakeRunner(private val answer: String = "hi") : NativeTurnRunner {
        override val provider = ClaudeSubscription
        val requests = mutableListOf<NativeTurnRequest>()
        override suspend fun turn(request: NativeTurnRequest): NativeTurnResult {
            requests += request
            return NativeTurnResult(answer, inputTokens = 10, outputTokens = 5, modelId = "claude-x")
        }
    }

    private val model = SubscriptionLLMClient.model(ClaudeSubscription, "sonnet", structuredOutput = true)

    @Test
    fun singleUserMessageGoesThroughVerbatimViaKoogExecutor() = runTest {
        val runner = FakeRunner("answer")
        val executor = MultiLLMPromptExecutor(SubscriptionLLMClient(runner))
        val reply = executor.execute(prompt("p") { system("Be brief."); user("What is 2+2?") }, model, emptyList())

        val request = runner.requests.single()
        assertEquals("What is 2+2?", request.prompt)
        assertEquals("Be brief.", request.system)
        assertEquals("sonnet", request.model)
        assertNull(request.schema)
        val assistant = reply as Message.Assistant
        assertEquals("answer", assistant.textContent())
        assertEquals(10, assistant.metaInfo.inputTokensCount)
        assertEquals(5, assistant.metaInfo.outputTokensCount)
        assertEquals(15, assistant.metaInfo.totalTokensCount)
        assertEquals("claude-x", assistant.metaInfo.modelId)
    }

    @Test
    fun historyBecomesATranscript() {
        val p = prompt("p") { user("first"); assistant("reply"); user("second") }
        val request = SubscriptionLLMClient.render(p, model)
        assertNull(request.system)
        assertTrue(request.prompt.indexOf("[user]\nfirst") < request.prompt.indexOf("[assistant]\nreply"))
        assertTrue(request.prompt.indexOf("[assistant]\nreply") < request.prompt.indexOf("[user]\nsecond"))
    }

    @Test
    fun schemaFromParamsIsPassedOn() {
        val schema = Json.parseToJsonElement("""{"type":"object","properties":{"a":{"type":"integer"}}}""").jsonObject
        val p = prompt("p", params = LLMParams(schema = LLMParams.Schema.JSON.Standard("A", schema))) { user("go") }
        assertEquals(schema, SubscriptionLLMClient.render(p, model).schema)
    }

    @Test
    fun toolsAreRejected() = runTest {
        val client = SubscriptionLLMClient(FakeRunner())
        assertFailsWith<LLMClientException> {
            client.execute(prompt("p") { user("x") }, model, listOf(ToolDescriptor("lookup", "d")))
        }
    }

    @Test
    fun runnerFailuresBecomeClientExceptions() = runTest {
        val failing = object : NativeTurnRunner {
            override val provider = ClaudeSubscription
            override suspend fun turn(request: NativeTurnRequest): NativeTurnResult = error("not logged in")
        }
        val e = assertFailsWith<LLMClientException> { SubscriptionLLMClient(failing).execute(prompt("p") { user("x") }, model, emptyList()) }
        assertTrue("not logged in" in e.message!!)
    }

    @Test
    fun rootObjectSchemaInlinesTheRef() {
        val koog = Json.parseToJsonElement(
            """{"${'$'}id":"A","${'$'}ref":"#/${'$'}defs/A","${'$'}defs":{"A":{"type":"object","properties":{"b":{"${'$'}ref":"#/${'$'}defs/B"}}},"B":{"type":"string"}}}""",
        ).jsonObject
        val root = rootObjectSchema(koog)
        assertEquals("object", root["type"].toString().trim('"'))
        assertEquals(koog["${'$'}defs"], root["${'$'}defs"])
        assertEquals(root, rootObjectSchema(root))
    }

    // --- Claude runner over a fake bridge -------------------------------------------------

    private class FakeBridge(private val info: String, private val onEvent: (JsonNode) -> Unit) : BridgeTransport {
        val requests = mutableListOf<Pair<String, JsonNode>>()
        override suspend fun request(method: String, params: JsonNode): JsonNode {
            requests += method to params
            return when (method) {
                "open" -> bridgeJson.readTree(info)
                "turn" -> {
                    onEvent(bridgeJson.readTree(
                        """{"event":{"type":"result","duration_ms":7,"total_cost_usd":0.01,""" +
                            """"usage":{"input_tokens":3,"output_tokens":4,"cache_read_input_tokens":2},"modelUsage":{"claude-sonnet-x":{}}}}""",
                    ))
                    obj("text" to """{"a":1}""")
                }
                else -> obj()
            }
        }
        override fun close() = Unit
    }

    private fun claude(info: String, bridges: MutableList<FakeBridge>) = ClaudeTurnRunner(
        transportFactory = { _: File, onEvent, _ -> FakeBridge(info, onEvent).also { bridges += it } },
    )

    @Test
    fun claudeRunnerOpensALockedDownSessionPerTurn() = runTest {
        val bridges = mutableListOf<FakeBridge>()
        val runner = claude("""{"subscriptionType":"Claude Team","apiProvider":"firstParty"}""", bridges)
        val schema = Json.parseToJsonElement("""{"type":"object"}""") as JsonObject
        val result = runner.turn(NativeTurnRequest("sonnet", "sys", "go", schema))
        runner.turn(NativeTurnRequest("sonnet", null, "again", null))

        assertEquals(2, bridges.size)
        assertEquals(listOf("open", "turn", "close"), bridges[0].requests.map { it.first })
        val native = bridges[0].requests[0].second["native"]
        assertEquals(0, native["tools"].size())
        assertEquals(0, native["settingSources"].size())
        assertEquals(false, native["persistSession"].asBoolean())
        assertEquals("sys", native["systemPrompt"].asText())
        assertEquals("json_schema", native["outputFormat"]["type"].asText())
        assertTrue(bridges[1].requests[0].second["native"]["outputFormat"] == null)

        assertEquals("""{"a":1}""", result.text)
        assertEquals(3, result.inputTokens)
        assertEquals(4, result.outputTokens)
        assertEquals("claude-sonnet-x", result.modelId)
        assertEquals("7", result.metadata!!["durationMs"].toString())
    }

    @Test
    fun claudeRunnerRefusesApiKeyAuth() = runTest {
        val bridges = mutableListOf<FakeBridge>()
        val runner = claude("""{"authType":"ANTHROPIC_API_KEY","apiProvider":"firstParty"}""", bridges)
        assertFailsWith<IllegalStateException> { runner.turn(NativeTurnRequest("sonnet", null, "go", null)) }
        assertEquals(listOf("open", "close"), bridges.single().requests.map { it.first })
        assertFailsWith<IllegalStateException> { ClaudeTurnRunner.requireSubscriptionLogin(bridgeJson.readTree("""{"apiProvider":"firstParty"}""")) }
        assertFailsWith<IllegalStateException> {
            ClaudeTurnRunner.requireSubscriptionLogin(bridgeJson.readTree("""{"subscriptionType":"Pro","apiProvider":"bedrock"}"""))
        }
        assertTrue(ClaudeTurnRunner.SCRUBBED_ENV.keys.containsAll(listOf("ANTHROPIC_API_KEY", "ANTHROPIC_AUTH_TOKEN")))
    }
}
