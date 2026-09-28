package io.github.gildor.koog.nativeagents

import ai.koog.agents.core.dsl.builder.AIAgentNodeDelegate
import ai.koog.agents.core.dsl.builder.node
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.Closeable
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

internal val bridgeJson = ObjectMapper()
internal fun obj(vararg fields: Pair<String, Any?>): JsonNode = bridgeJson.valueToTree(fields.toMap())

/** Transport boundary only; native permission decisions are not normalized. */
interface BridgeTransport : Closeable {
    /** Send one JSON request and await its correlated response; cancellation does not imply native abort. */
    suspend fun request(method: String, params: JsonNode = obj()): JsonNode
}

/** One structured JSONL process, with bidirectional requests and native event delivery. */
class JsonLineProcess(
    command: List<String>,
    cwd: File,
    private val onNativeRequest: suspend (JsonNode) -> JsonNode,
    private val onEvent: (JsonNode) -> Unit = {},
    private val onStderr: (String) -> Unit = {},
) : BridgeTransport {
    private val process = ProcessBuilder(command).directory(cwd).start()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val writer = process.outputStream.bufferedWriter()
    private val pending = ConcurrentHashMap<Long, CompletableDeferred<JsonNode>>()
    private val sequence = AtomicLong()
    private val ended = AtomicBoolean()

    init {
        scope.launch {
            try {
                process.inputStream.bufferedReader().useLines { lines -> lines.forEach { receive(bridgeJson.readTree(it)) } }
                fail(IllegalStateException("Bridge process ended before completing pending requests"))
            } catch (failure: Exception) { fail(failure) }
        }
        scope.launch {
            try { process.errorStream.bufferedReader().useLines { lines -> lines.forEach(onStderr) } }
            catch (_: Exception) { /* Process closure also closes stderr. */ }
        }
    }

    override suspend fun request(method: String, params: JsonNode): JsonNode {
        check(!ended.get()) { "Bridge is closed" }
        val id = sequence.incrementAndGet()
        val result = CompletableDeferred<JsonNode>()
        pending[id] = result
        try {
            write(obj("id" to id, "method" to method, "params" to params))
            return result.await()
        } finally { pending.remove(id) }
    }

    private fun receive(message: JsonNode) {
        if (message.has("method")) {
            when (message["method"].asText()) {
                "native/event" -> onEvent(message["params"])
                "native/request" -> scope.launch {
                    val response = try { obj("id" to message["id"], "result" to onNativeRequest(message["params"])) }
                    catch (failure: Exception) { obj("id" to message["id"], "error" to obj("code" to -32000, "message" to failure.message)) }
                    if (!ended.get()) runCatching { write(response) }.onFailure { fail(it) }
                }
                else -> error("Unexpected bridge method: ${message["method"]}")
            }
        } else {
            val call = pending.remove(message.path("id").asLong()) ?: return
            if (message.has("error")) call.completeExceptionally(IllegalStateException(message["error"].path("message").asText()))
            else call.complete(message["result"] ?: bridgeJson.nullNode())
        }
    }

    private fun write(message: JsonNode) = synchronized(writer) {
        check(!ended.get()) { "Bridge is closed" }
        writer.write(message.toString()); writer.newLine(); writer.flush()
    }

    private fun fail(failure: Throwable) {
        if (!ended.compareAndSet(false, true)) return
        for (call in pending.values) call.completeExceptionally(failure)
        pending.clear()
    }

    override fun close() {
        fail(IllegalStateException("Bridge closed by host"))
        // Native close is requested by NativeBridgeStep first. This is the bounded
        // fallback for a dead/stalled bridge, including any remaining harness child.
        val descendants = process.descendants().toList()
        process.destroy()
        if (!process.waitFor(1500, TimeUnit.MILLISECONDS)) process.destroyForcibly()
        descendants.filter { it.isAlive }.forEach { it.destroy() }
        descendants.filter { it.isAlive }.forEach { it.destroyForcibly() }
        scope.cancel()
    }
}

/** Typed-turn adapter shared only by the process bridges, not a universal agent API. */
class NativeBridgeStep(private val transport: BridgeTransport, private val timeoutMillis: Long = 120_000) {
    init { require(timeoutMillis > 0) }
    private val mutex = Mutex()
    private var needsRecovery = false
    private var closed = false
    /** Native conversation identity, populated by open or a completed turn; separate from Koog run IDs. */
    var sessionId: String? = null
        private set

    /**
     * Opens a native session using the selected bridge's provider-specific options.
     * The host must call this once before dispatching turns and close the step if opening fails.
     */
    suspend fun open(options: JsonNode): JsonNode {
        check(!closed) { "Native bridge is closed" }
        val info = withTimeout(timeoutMillis) { transport.request("open", options) }
        sessionId = info.get("sessionId")?.takeUnless { it.isNull }?.asText()
        return info
    }

    /**
     * Executes one serialized native turn and decodes its final text.
     * Transport failure attempts native interruption, closes the transport, and forbids replay.
     * A decoding failure permits an explicitly requested correction turn.
     */
    suspend fun <Output> turn(prompt: String, decode: (String) -> Output): Output = mutex.withLock {
        check(!closed) { "Native bridge is closed" }
        check(!needsRecovery) { "Native session requires explicit recovery; no turn was replayed" }
        val result = try {
            withTimeout(timeoutMillis) { transport.request("turn", obj("prompt" to prompt)) }
        } catch (failure: Exception) {
            needsRecovery = true
            try { withContext(NonCancellable) { withTimeout(5_000) { transport.request("interrupt") } } }
            catch (abortFailure: Exception) { failure.addSuppressed(abortFailure) }
            finally { transport.close() }
            throw failure
        }
        sessionId = result.get("sessionId")?.takeUnless { it.isNull }?.asText() ?: sessionId
        val text = requireNotNull(result.get("text")?.takeIf { it.isTextual }?.asText()) { "Native turn has no final text" }
        decode(text)
    }

    /** Idempotently asks the bridge to close, then closes its transport even on failure. */
    suspend fun shutdown() = withContext(NonCancellable) {
        if (closed) return@withContext
        closed = true
        if (needsRecovery) return@withContext // Failure already closed the transport.
        try { withTimeout(5_000) { transport.request("close") } }
        finally { transport.close() }
    }
}

/** Ordinary Koog node; the host owns the native session outside graph lifetime. */
inline fun <reified Input, reified Output> nativeNode(
    name: String, step: NativeBridgeStep, noinline prompt: (Input) -> String, noinline decode: (String) -> Output,
): AIAgentNodeDelegate<Input, Output> = node(name) { step.turn(prompt(it), decode) }
