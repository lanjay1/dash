package com.ztune.libretune.core.ecu

import com.ztune.libretune.core.ini.EcuDefinition
import com.ztune.libretune.core.ini.types.EcuType
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.zip.CRC32

/**
 * rusEFI ECU implementation using the rusEFI TunerStudio protocol.
 *
 * rusEFI shares the 'S' streaming concept with MegaSquirt but uses an
 * entirely different wire format:
 * - **Signature query**: raw text — send 'S', read until newline.
 * - **Block read/write/burn**: binary commands ('r'/'w'/'B') with CRC-32
 *   framed responses (not CRC-16 like MS).
 * - **Controller commands**: raw text terminated by CR/LF.
 * - **GPPWM channels**: supported via extended controller command set.
 * - **DFU firmware updates**: exposed through INI controller commands.
 */
class RusEfiEcu : EcuInterface {
    override val ecuType = EcuType.RUSEFI
    override var definition: EcuDefinition? = null
        private set
    override var isConnected: Boolean = false
        private set

    private var transport: EcuTransport? = null
    private var streamingJob: Job? = null
    private val ioMutex = Mutex()

    private val _realtimeUpdates = MutableSharedFlow<RealtimeUpdate>(
        replay = 1,
        extraBufferCapacity = 10
    )
    override val realtimeUpdates: SharedFlow<RealtimeUpdate> = _realtimeUpdates

    /** Internal receive buffer for accumulating framed responses. */
    private val rxBuffer = mutableListOf<Byte>()

    companion object {
        private const val STREAM_INTERVAL_MS = 50L
        private const val SIGNATURE_TIMEOUT_MS = 2_000L
        private const val COMMAND_TIMEOUT_MS = 1_500L
        private const val BURN_TIMEOUT_MS = 5_000L
        private const val TEXT_TIMEOUT_MS = 2_000L
        private const val HEADER: Byte = 0x5A
        private const val CRC32_SIZE = 4
        private const val MAX_BLOCK_SIZE = 256
        private const val CR: Byte = 0x0D
        private const val LF: Byte = 0x0A
        /** rusEFI GPPWM data lives on a dedicated page. */
        private const val GPPWM_PAGE: Byte = 0x10
    }

    // ==================================================================
    // EcuInterface implementation
    // ==================================================================

    override suspend fun connect(
        transport: EcuTransport,
        definition: EcuDefinition
    ): Result<Unit> = runCatching {
        transport.connect()
        this.transport = transport
        this.definition = definition

        val sig = querySignature().getOrThrow()
        val expected = definition.signaturePrefix ?: definition.signature
        if (expected.isNotEmpty() && !sig.contains(expected)) {
            // Signature mismatch — still connect; caller may warn the user.
        }
        isConnected = true
    }

    override suspend fun disconnect() {
        streamingJob?.cancel()
        streamingJob = null
        ioMutex.withLock { rxBuffer.clear() }
        transport?.disconnect()
        transport = null
        isConnected = false
    }

    /**
     * rusEFI signature query: send 'S' and read a text line until newline.
     * The response is NOT framed — it is plain ASCII text terminated by \n.
     */
    override suspend fun querySignature(): Result<String> = ioMutex.withLock {
        runCatching {
            val t = transport ?: throw TransportException("Not connected")
            rxBuffer.clear()
            t.send(byteArrayOf('S'.code.toByte()))
            val line = readTextLine(t, SIGNATURE_TIMEOUT_MS)
            line.trim()
        }
    }

    /**
     * rusEFI block read: send 'r' + page(1) + offset(2 LE) + count(1).
     * Response is CRC-32 framed: [payload...][crc32(4 LE)].
     */
    override suspend fun readBlock(
        page: Int,
        offset: Int,
        length: Int
    ): Result<ByteArray> = ioMutex.withLock {
        runCatching {
            val t = transport ?: throw TransportException("Not connected")
            val clamped = length.coerceIn(0, MAX_BLOCK_SIZE)
            val cmd = byteArrayOf(
                'r'.code.toByte(),
                page.toByte(),
                (offset and 0xFF).toByte(),
                ((offset ushr 8) and 0xFF).toByte(),
                clamped.toByte()
            )
            rxBuffer.clear()
            t.send(cmd)
            val payload = readCrc32FramedResponse(t, clamped, COMMAND_TIMEOUT_MS)
            payload.copyOfRange(0, clamped)
        }
    }

    /**
     * rusEFI block write: send 'w' + page(1) + offset(2 LE) + data...
     * Response is a single-byte ACK inside a CRC-32 frame.
     */
    override suspend fun writeBlock(
        page: Int,
        offset: Int,
        data: ByteArray
    ): Result<Unit> = ioMutex.withLock {
        runCatching {
            val t = transport ?: throw TransportException("Not connected")
            val header = byteArrayOf(
                'w'.code.toByte(),
                page.toByte(),
                (offset and 0xFF).toByte(),
                ((offset ushr 8) and 0xFF).toByte()
            )
            val packet = ByteArray(header.size + data.size)
            System.arraycopy(header, 0, packet, 0, header.size)
            System.arraycopy(data, 0, packet, header.size, data.size)

            rxBuffer.clear()
            t.send(packet)
            val response = readCrc32FramedResponse(t, 1, COMMAND_TIMEOUT_MS)
            val ack = response.firstOrNull()
                ?: throw ProtocolException("writeBlock: empty response")
            if (ack != 0x30.toByte()) {
                throw ProtocolException(
                    "writeBlock: ECU returned 0x${ack.toUByte().toString(16)} (expected 0x30)"
                )
            }
        }
    }

    /**
     * rusEFI burn: send 'B' + page(1) + offset(2 LE).
     * Response is a single-byte ACK inside a CRC-32 frame.
     */
    override suspend fun burnPage(page: Int): Result<Unit> = ioMutex.withLock {
        runCatching {
            val t = transport ?: throw TransportException("Not connected")
            val cmd = byteArrayOf(
                'B'.code.toByte(),
                page.toByte(),
                0x00, 0x00 // offset = 0 for full-page burn
            )
            rxBuffer.clear()
            t.send(cmd)
            val response = readCrc32FramedResponse(t, 1, BURN_TIMEOUT_MS)
            val ack = response.firstOrNull()
                ?: throw ProtocolException("burnPage: empty response")
            if (ack != 0x30.toByte()) {
                throw ProtocolException(
                    "burnPage: ECU returned 0x${ack.toUByte().toString(16)} (expected 0x30)"
                )
            }
        }
    }

    /**
     * rusEFI real-time data: send 'S' and receive a CRC-32 framed binary
     * payload containing all output channels.
     */
    override suspend fun readRealtimeData(): Result<ByteArray> = ioMutex.withLock {
        runCatching {
            val t = transport ?: throw TransportException("Not connected")
            rxBuffer.clear()
            t.send(byteArrayOf('S'.code.toByte()))
            // For streaming data we don't know the exact length ahead of time;
            // read enough for a typical rusEFI output channel block.
            readCrc32FramedResponse(t, 0, COMMAND_TIMEOUT_MS)
        }
    }

    /**
     * rusEFI controller commands: send raw text with CR/LF termination.
     * The ECU responds with a text line (also terminated by CR/LF).
     * This supports the rusEFI interactive console / tsConsole.
     */
    override suspend fun sendControllerCommand(
        name: String,
        commandTemplate: String,
        value: Int
    ): Result<ByteArray> = ioMutex.withLock {
        runCatching {
            val t = transport ?: throw TransportException("Not connected")

            // Expand TS template tokens for rusEFI text commands.
            val expanded = expandTextCommand(commandTemplate, value)
            val textBytes = expanded.toByteArray(Charsets.US_ASCII)
            val packet = textBytes + byteArrayOf(CR, LF)

            rxBuffer.clear()
            t.send(packet)
            val responseText = readTextLine(t, TEXT_TIMEOUT_MS)
            responseText.toByteArray(Charsets.US_ASCII)
        }
    }

    override suspend fun startStreaming() {
        streamingJob?.cancel()
        streamingJob = CoroutineScope(Dispatchers.IO).launch {
            while (isActive) {
                try {
                    val data = readRealtimeData().getOrNull()
                    if (data != null) {
                        _realtimeUpdates.emit(RealtimeUpdate(rawData = data))
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // Individual read failures are swallowed; the loop retries.
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
            rxBuffer.clear()
            // rusEFI comm reset: send a few break characters to resync.
            t.send(byteArrayOf(0x00, 0x00, 0x00))
            delay(100L)
            rxBuffer.clear()
        }
    }

    // ==================================================================
    // rusEFI-specific API
    // ==================================================================

    /**
     * Send a raw text command to the rusEFI console and return the response.
     * Useful for GPPWM queries, test commands, and diagnostic output.
     *
     * @param command Text command (e.g. "gppwm", "help").
     * @return The ECU's text response.
     */
    suspend fun sendConsoleCommand(command: String): Result<String> = ioMutex.withLock {
        runCatching {
            val t = transport ?: throw TransportException("Not connected")
            val packet = command.toByteArray(Charsets.US_ASCII) + byteArrayOf(CR, LF)
            rxBuffer.clear()
            t.send(packet)
            readTextLine(t, TEXT_TIMEOUT_MS)
        }
    }

    /**
     * Read a GPPWM channel value from the rusEFI ECU.
     *
     * @param channel GPPWM channel index (0-based).
     * @return The current duty cycle value as a byte.
     */
    suspend fun readGppwmChannel(channel: Int): Result<Byte> = ioMutex.withLock {
        runCatching {
            val t = transport ?: throw TransportException("Not connected")
            val cmd = byteArrayOf(
                'r'.code.toByte(),
                GPPWM_PAGE,
                (channel and 0xFF).toByte(),
                ((channel ushr 8) and 0xFF).toByte(),
                0x01
            )
            rxBuffer.clear()
            t.send(cmd)
            val response = readCrc32FramedResponse(t, 1, COMMAND_TIMEOUT_MS)
            response.first()
        }
    }

    // ==================================================================
    // Internal helpers
    // ==================================================================

    /**
     * Read a text line from the transport (until LF is received or timeout).
     */
    private suspend fun readTextLine(t: EcuTransport, timeoutMs: Long): String {
        val sb = StringBuilder()
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0) break
            val chunk = withTimeoutOrNull(remaining.coerceAtMost(500L)) {
                t.receive(128)
            } ?: continue
            for (b in chunk) {
                if (b == LF) return sb.toString()
                if (b != CR) sb.append(b.toInt().toChar())
            }
        }
        if (sb.isEmpty()) throw ProtocolException("readTextLine: timed out")
        return sb.toString()
    }

    /**
     * Read a CRC-32 framed response from the transport.
     *
     * rusEFI binary response format: [0x5A][payload...][crc32(4 bytes LE)]
     * The CRC-32 covers only the payload bytes (after the 0x5A header).
     *
     * @param t           Transport to read from.
     * @param minPayload  Minimum expected payload bytes (0 = accept whatever arrives).
     * @param timeoutMs   Total timeout in milliseconds.
     * @return The validated payload bytes (excluding header and CRC).
     */
    private suspend fun readCrc32FramedResponse(
        t: EcuTransport,
        minPayload: Int,
        timeoutMs: Long
    ): ByteArray {
        val deadline = System.currentTimeMillis() + timeoutMs
        val buf = rxBuffer

        while (System.currentTimeMillis() < deadline) {
            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0) break
            val chunk = withTimeoutOrNull(remaining.coerceAtMost(500L)) {
                t.receive(512)
            } ?: continue

            synchronized(buf) {
                buf.addAll(chunk.toList())
            }

            val result = tryExtractCrc32Frame(buf, minPayload)
            if (result != null) {
                synchronized(buf) {
                    repeat(result.consumed) { buf.removeAt(0) }
                }
                return result.payload
            }
        }
        throw ProtocolException(
            "readCrc32FramedResponse: timed out after ${timeoutMs}ms"
        )
    }

    /**
     * Try to extract a valid CRC-32 framed message from [buf].
     *
     * Frame format: 0x5A | payload... | crc32(4 bytes LE)
     * CRC-32 (Java's java.util.zip.CRC32, polynomial 0xEDB88320) is computed
     * over the payload bytes only.
     *
     * @return Extracted payload and consumed byte count, or null if incomplete.
     */
    private fun tryExtractCrc32Frame(
        buf: List<Byte>,
        minPayload: Int
    ): FrameExtractResult? {
        // Find the header byte
        var idx = 0
        while (idx < buf.size && buf[idx] != HEADER) idx++
        if (idx >= buf.size) return null

        val start = idx
        val remaining = buf.size - start
        // Need at least header(1) + min_payload + crc32(4)
        if (remaining < 1 + minPayload + CRC32_SIZE) return null

        // The maximum possible payload is everything before the last 4 CRC bytes.
        // We don't know the payload length, so we scan: payload could be from
        // minPayload up to (remaining - 1 - 4).
        val maxPayloadLen = remaining - 1 - CRC32_SIZE
        for (payloadLen in minPayload..maxPayloadLen) {
            val payloadEnd = start + 1 + payloadLen
            val crcEnd = payloadEnd + CRC32_SIZE
            if (crcEnd > buf.size) continue

            val payloadBytes = ByteArray(payloadLen) { i ->
                buf[start + 1 + i]
            }

            val crcReceived = ((buf[crcEnd - 1].toInt() and 0xFF) shl 24) or
                    ((buf[crcEnd - 2].toInt() and 0xFF) shl 16) or
                    ((buf[crcEnd - 3].toInt() and 0xFF) shl 8) or
                    (buf[crcEnd - 4].toInt() and 0xFF)

            val crc = CRC32()
            crc.update(payloadBytes)
            val crcExpected = crc.value.toInt()

            if (crcReceived == crcExpected) {
                return FrameExtractResult(
                    payload = payloadBytes,
                    consumed = crcEnd - start
                )
            }
        }
        return null
    }

    /**
     * Expand a TunerStudio-style command template for rusEFI text commands.
     * Supports %v (value as decimal text) and literal characters.
     */
    private fun expandTextCommand(template: String, value: Int): String {
        val sb = StringBuilder(template.length + 8)
        var i = 0
        while (i < template.length) {
            if (i + 2 <= template.length && template.substring(i, i + 2) == "%v") {
                sb.append(value)
                i += 2
            } else {
                sb.append(template[i])
                i++
            }
        }
        return sb.toString()
    }

    // ==================================================================
    // Internal types
    // ==================================================================

    private data class FrameExtractResult(
        val payload: ByteArray,
        val consumed: Int
    )
}
