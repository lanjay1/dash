package com.ztune.libretune.core.ecu

import com.ztune.libretune.core.ini.EcuDefinition
import com.ztune.libretune.core.ini.types.EcuType
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*

/**
 * epicEFI ECU implementation using a JSON-based serial protocol.
 *
 * epicEFI communicates entirely via newline-delimited JSON:
 * - **Commands** are JSON objects sent as a single line: `{"cmd":"identify"}\n`
 * - **Responses** are JSON arrays: `["ok", {"key":"value"}]\n`
 * - Error responses use `["err", "error message"]\n`
 *
 * This provides a human-readable, debuggable protocol at the cost of
 * slightly higher bandwidth compared to binary protocols.
 */
class EpicEfiEcu : EcuInterface {
    override val ecuType = EcuType.EPICEFI
    override var definition: EcuDefinition? = null
        private set
    override var isConnected: Boolean = false
        private set

    private var transport: EcuTransport? = null
    private var streamingJob: Job? = null
    private val ioMutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true }

    private val _realtimeUpdates = MutableSharedFlow<RealtimeUpdate>(
        replay = 1,
        extraBufferCapacity = 10
    )
    override val realtimeUpdates: SharedFlow<RealtimeUpdate> = _realtimeUpdates

    /** Parsed identification data received during connect. */
    private var identifyResult: JsonObject? = null

    companion object {
        private const val STREAM_INTERVAL_MS = 50L
        private const val COMMAND_TIMEOUT_MS = 2_000L
        private const val BURN_TIMEOUT_MS = 5_000L
        private const val IDENTIFY_TIMEOUT_MS = 3_000L
    }

    // ==================================================================
    // EcuInterface implementation
    // ==================================================================

    override suspend fun connect(
        transport: EcuTransport,
        definition: EcuDefinition
    ): Result<Unit> = ioMutex.withLock {
        runCatching {
            transport.connect()
            this.transport = transport
            this.definition = definition

            // Send identify command to verify connectivity.
            val resp = sendJsonCommand(buildJsonCommand("identify"), IDENTIFY_TIMEOUT_MS)
            val status = resp[0].jsonPrimitive.content
            if (status == "err") {
                val msg = if (resp.size > 1) resp[1].jsonPrimitive.content else "unknown error"
                throw ProtocolException("epicEFI identify failed: $msg")
            }
            if (resp.size > 1 && resp[1] is JsonObject) {
                identifyResult = resp[1].jsonObject
            }

            // Verify signature if provided in the definition.
            val sig = identifyResult?.get("firmware")?.jsonPrimitive?.content ?: ""
            val expected = definition.signaturePrefix ?: definition.signature
            if (expected.isNotEmpty() && !sig.contains(expected, ignoreCase = true)) {
                // Signature mismatch — still connect; caller may warn.
            }
            isConnected = true
        }
    }

    override suspend fun disconnect() {
        streamingJob?.cancel()
        streamingJob = null
        transport?.disconnect()
        transport = null
        identifyResult = null
        isConnected = false
    }

    /**
 * Query the ECU's signature from the cached identify response.
 * Re-sends the identify command if not cached.
 */
    override suspend fun querySignature(): Result<String> = ioMutex.withLock {
        runCatching {
            val cached = identifyResult?.get("firmware")?.jsonPrimitive?.content
            if (cached != null) return@runCatching cached

            val t = transport ?: throw TransportException("Not connected")
            val resp = sendJsonCommand(buildJsonCommand("identify"), COMMAND_TIMEOUT_MS)
            val obj = resp.getOrNull(1)?.jsonObject
                ?: throw ProtocolException("querySignature: no identification data")
            val sig = obj["firmware"]?.jsonPrimitive?.content ?: ""
            identifyResult = obj
            sig
        }
    }

    /**
 * epicEFI block read: send `{"cmd":"read","page":N,"offset":N,"length":N}`.
 * Response: `["ok", {"data":"<base64>"}]`
 */
    override suspend fun readBlock(
        page: Int,
        offset: Int,
        length: Int
    ): Result<ByteArray> = ioMutex.withLock {
        runCatching {
            val cmd = buildJsonObject {
                put("cmd", "read")
                put("page", page)
                put("offset", offset)
                put("length", length)
            }
            val resp = sendJsonCommand(cmd, COMMAND_TIMEOUT_MS)
            parseDataResponse(resp)
        }
    }

    /**
 * epicEFI block write: send `{"cmd":"write","page":N,"offset":N,"data":"<base64>"}`.
 * Response: `["ok", {"written":N}]`
 */
    override suspend fun writeBlock(
        page: Int,
        offset: Int,
        data: ByteArray
    ): Result<Unit> = ioMutex.withLock {
        runCatching {
            val base64 = android.util.Base64.encodeToString(
                data, android.util.Base64.NO_WRAP
            )
            val cmd = buildJsonObject {
                put("cmd", "write")
                put("page", page)
                put("offset", offset)
                put("data", base64)
            }
            val resp = sendJsonCommand(cmd, COMMAND_TIMEOUT_MS)
            val status = resp[0].jsonPrimitive.content
            if (status == "err") {
                val msg = if (resp.size > 1) resp[1].jsonPrimitive.content else "write failed"
                throw ProtocolException("writeBlock: $msg")
            }
        }
    }

    /**
 * epicEFI burn: send `{"cmd":"burn","page":N}`.
 * Response: `["ok", {}]`
 */
    override suspend fun burnPage(page: Int): Result<Unit> = ioMutex.withLock {
        runCatching {
            val cmd = buildJsonObject {
                put("cmd", "burn")
                put("page", page)
            }
            val resp = sendJsonCommand(cmd, BURN_TIMEOUT_MS)
            val status = resp[0].jsonPrimitive.content
            if (status == "err") {
                val msg = if (resp.size > 1) resp[1].jsonPrimitive.content else "burn failed"
                throw ProtocolException("burnPage: $msg")
            }
        }
    }

    /**
 * epicEFI real-time data: send `{"cmd":"realtime"}`.
 * Response: `["ok", {"data":"<base64>","channels":{...}}]`
 *
 * The `data` field contains the raw binary output channel block as base64.
 * The `channels` field optionally provides pre-decoded named values.
 */
    override suspend fun readRealtimeData(): Result<ByteArray> = ioMutex.withLock {
        runCatching {
            val cmd = buildJsonCommand("realtime")
            val resp = sendJsonCommand(cmd, COMMAND_TIMEOUT_MS)
            parseDataResponse(resp)
        }
    }

    /**
 * epicEFI controller command: send the command template as a JSON command.
 *
 * If the template looks like a JSON object (starts with '{'), it is sent
 * directly.  Otherwise it is wrapped as `{"cmd":"<template>","value":N}`.
 */
    override suspend fun sendControllerCommand(
        name: String,
        commandTemplate: String,
        value: Int
    ): Result<ByteArray> = ioMutex.withLock {
        runCatching {
            val cmd = if (commandTemplate.trimStart().startsWith('{')) {
                Json.parseToJsonElement(commandTemplate).jsonObject
            } else {
                buildJsonObject {
                    put("cmd", commandTemplate)
                    put("value", value)
                }
            }
            val resp = sendJsonCommand(cmd, COMMAND_TIMEOUT_MS)
            val status = resp[0].jsonPrimitive.content
            if (status == "err") {
                val msg = if (resp.size > 1) resp[1].jsonPrimitive.content else "command failed"
                throw ProtocolException("sendControllerCommand ($name): $msg")
            }
            // Return the entire JSON response as bytes for the caller.
            resp.toString().toByteArray(Charsets.UTF_8)
        }
    }

    override suspend fun startStreaming() {
        streamingJob?.cancel()
        streamingJob = CoroutineScope(Dispatchers.IO).launch {
            while (isActive) {
                try {
                    val result = readRealtimeData().getOrNull()
                    if (result != null) {
                        _realtimeUpdates.emit(RealtimeUpdate(rawData = result))
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // Swallow individual read failures; the loop retries.
                }
                delay(STREAM_INTERVAL_MS)
            }
        }
    }

    override suspend fun stopStreaming() {
        streamingJob?.cancel()
        streamingJob = null
    }

    override suspend fun commReset(): Result<Unit> = ioMutex.withLock {
        runCatching {
            val t = transport ?: throw TransportException("Not connected")
            val cmd = buildJsonCommand("reset")
            sendRawJsonLine(t, cmd)
            // No response expected after a reset; clear any stale data.
            drainTransport(t, 500L)
        }
    }

    // ==================================================================
    // Internal: JSON protocol helpers
    // ==================================================================

    /** Build a simple command JSON object: `{"cmd":"<name>"}`. */
    private fun buildJsonCommand(name: String): JsonObject = buildJsonObject {
        put("cmd", name)
    }

    /**
 * Send a JSON command and read the JSON array response.
 *
 * @param cmd      JSON object to send.
 * @param timeoutMs Total timeout for the operation.
 * @return The parsed JSON array response.
 */
    private suspend fun sendJsonCommand(
        cmd: JsonObject,
        timeoutMs: Long
    ): JsonArray {
        val t = transport ?: throw TransportException("Not connected")
        sendRawJsonLine(t, cmd)
        return readJsonArrayResponse(t, timeoutMs)
    }

    /** Serialize a JSON object and send it as a newline-terminated line. */
    private suspend fun sendRawJsonLine(t: EcuTransport, obj: JsonObject) {
        val line = json.encodeToString(JsonElement.serializer(), obj)
        t.send((line + "\n").toByteArray(Charsets.UTF_8))
    }

    /**
 * Read a single newline-terminated JSON array from the transport.
 *
 * Accumulates bytes until a complete JSON array ending in '\n' is received.
 */
    private suspend fun readJsonArrayResponse(
        t: EcuTransport,
        timeoutMs: Long
    ): JsonArray {
        val sb = StringBuilder()
        val deadline = System.currentTimeMillis() + timeoutMs

        while (System.currentTimeMillis() < deadline) {
            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0) break
            val chunk = withTimeoutOrNull(remaining.coerceAtMost(500L)) {
                t.receive(512)
            } ?: continue

            for (b in chunk) {
                val ch = b.toInt().toChar()
                sb.append(ch)
                if (ch == '\n') {
                    val raw = sb.toString().trim()
                    return try {
                        json.parseToJsonElement(raw).jsonArray
                    } catch (e: Exception) {
                        throw ProtocolException("Invalid JSON response: $raw", e)
                    }
                }
            }
        }
        throw ProtocolException("readJsonArrayResponse: timed out after ${timeoutMs}ms")
    }

    /**
 * Parse a base64-encoded `data` field from a JSON response array.
 *
 * Expected format: `["ok", {"data":"<base64>"}]`
 */
    private fun parseDataResponse(resp: JsonArray): ByteArray {
        val status = resp[0].jsonPrimitive.content
        if (status == "err") {
            val msg = if (resp.size > 1) resp[1].jsonPrimitive.content else "unknown error"
            throw ProtocolException("ECU error: $msg")
        }
        if (resp.size < 2) {
            throw ProtocolException("Response missing data payload")
        }
        val obj = resp[1].jsonObject
        val base64 = obj["data"]?.jsonPrimitive?.content
            ?: throw ProtocolException("Response missing 'data' field")
        return android.util.Base64.decode(base64, android.util.Base64.DEFAULT)
    }

    /** Drain any pending data from the transport for [timeoutMs]. */
    private suspend fun drainTransport(t: EcuTransport, timeoutMs: Long) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            withTimeoutOrNull(200L) { t.receive(256) } ?: break
        }
    }
}
