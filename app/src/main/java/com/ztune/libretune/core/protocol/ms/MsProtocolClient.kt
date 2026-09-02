package com.ztune.libretune.core.protocol.ms

import com.ztune.libretune.core.ecu.EcuTransport
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

/** Shared empty array to avoid repeated zero-length allocations. */
private val EMPTY_BYTE_ARRAY = ByteArray(0)

/**
 * MegaSquirt serial protocol client.
 *
 * Handles the MS envelope protocol (0x5A header framing, 0x7D byte-stuffing,
 * CRC-16/CCITT-USB integrity checks) over an [EcuTransport] abstraction.
 *
 * All public suspend methods are **serialized** through an internal [Mutex] so that
 * concurrent callers never interleave frames on the wire.
 */
class MsProtocolClient(private val transport: EcuTransport) {

    // ------------------------------------------------------------------
    // Internal state
    // ------------------------------------------------------------------

    /** Accumulated raw bytes received from the transport, waiting to be deframed. */
    private val receiveBuffer = mutableListOf<Byte>()

    /** Serialises all outbound + inbound I/O so frames never interleave. */
    private val ioMutex = Mutex()

    // ------------------------------------------------------------------
    // Companion – command bytes
    // ------------------------------------------------------------------

    companion object {
        const val CMD_QUERY_SIGNATURE: Byte = 'Q'.code.toByte()  // 0x51
        const val CMD_BLOCK_READ: Byte = 'R'.code.toByte()      // 0x52
        const val CMD_BLOCK_WRITE: Byte = 'W'.code.toByte()     // 0x57
        const val CMD_BURN: Byte = 'B'.code.toByte()            // 0x42
        const val CMD_SINGLE_READ: Byte = '#'.code.toByte()     // 0x23
        const val CMD_COMM_RESET: Byte = 'c'.code.toByte()      // 0x63
        const val HEADER: Byte = 0x5A
        const val ESCAPE: Byte = 0x7D
        const val TIMEOUT_MS: Long = 1_000L
    }

    // ==================================================================
    // Public API
    // ==================================================================

    /**
     * Query the ECU for its signature string (e.g. "Megasquirt-Extra 3.1.x").
     *
     * Sends the 'Q' command and returns the ASCII signature with trailing
     * NUL bytes stripped.
     */
    suspend fun querySignature(): Result<String> = ioMutex.withLock {
        runCatching {
            val response = sendAndReceive(CMD_QUERY_SIGNATURE, emptyByteArray(), TIMEOUT_MS)
            // The payload is the raw signature bytes; strip trailing 0x00.
            val trimmed = response.trimTrailingNulls()
            String(trimmed, Charsets.US_ASCII)
        }
    }

    /**
     * Read a block of ECU memory.
     *
     * Standard MS block-read format: `R + page(1) + offset(2 LE) + count(1)`.
     * The response payload contains exactly [length] data bytes.
     *
     * @param page  ECU memory page index.
     * @param offset Byte offset within the page.
     * @param length Number of bytes to read (capped at [MsConstants.MAX_BLOCK_SIZE]).
     * @param commandOverride If non-null, use this command byte instead of 'R'.
     */
    suspend fun readBlock(
        page: Int,
        offset: Int,
        length: Int,
        commandOverride: Byte? = null
    ): Result<ByteArray> = ioMutex.withLock {
        runCatching {
            val cmd = commandOverride ?: CMD_BLOCK_READ
            val clampedLength = length.coerceIn(0, MsConstants.MAX_BLOCK_SIZE)

            val payload = byteArrayOf(
                page.toByte(),
                (offset and 0xFF).toByte(),
                ((offset ushr 8) and 0xFF).toByte(),
                clampedLength.toByte()
            )

            val response = sendAndReceive(cmd, payload, TIMEOUT_MS)
            if (response.size < clampedLength) {
                throw ProtocolException(
                    "readBlock: expected $clampedLength bytes but got ${response.size}"
                )
            }
            response.copyOfRange(0, clampedLength)
        }
    }

    /**
     * Write a block of data to ECU memory.
     *
     * Standard MS block-write format: `W + page(1) + offset(2 LE) + data…`.
     * The response is a single-byte acknowledgment.
     *
     * @param page  ECU memory page index.
     * @param offset Byte offset within the page.
     * @param data   Bytes to write.
     * @param commandOverride If non-null, use this command byte instead of 'W'.
     */
    suspend fun writeBlock(
        page: Int,
        offset: Int,
        data: ByteArray,
        commandOverride: Byte? = null
    ): Result<Unit> = ioMutex.withLock {
        runCatching {
            val cmd = commandOverride ?: CMD_BLOCK_WRITE
            val header = byteArrayOf(
                page.toByte(),
                (offset and 0xFF).toByte(),
                ((offset ushr 8) and 0xFF).toByte()
            )
            val payload = ByteArray(header.size + data.size)
            System.arraycopy(header, 0, payload, 0, header.size)
            System.arraycopy(data, 0, payload, header.size, data.size)

            val response = sendAndReceive(cmd, payload, TIMEOUT_MS)
            val ack = response.firstOrNull()
                ?: throw ProtocolException("writeBlock: empty response")
            if (ack != MsConstants.RESPONSE_SUCCESS) {
                throw ProtocolException(
                    "writeBlock: ECU returned 0x${ack.toUByte().toString(16)} (expected 0x30)"
                )
            }
        }
    }

    /**
     * Burn (flash) the current page to non-volatile ECU memory.
     *
     * Sends 'B' and waits for an acknowledgment.  Some MS firmware variants
     * require a modified burn command with page + offset; that variant is
     * supported via [commandOverride].
     *
     * @param commandOverride If non-null, use this command byte instead of 'B'.
     * @param burnTimeout Custom timeout in ms (defaults to [MsConstants.BURN_TIMEOUT_MS]).
     */
    suspend fun burnPage(
        commandOverride: Byte? = null,
        burnTimeout: Long = MsConstants.BURN_TIMEOUT_MS
    ): Result<Unit> = ioMutex.withLock {
        runCatching {
            val cmd = commandOverride ?: CMD_BURN
            val response = sendAndReceive(cmd, emptyByteArray(), burnTimeout)
            val ack = response.firstOrNull()
                ?: throw ProtocolException("burnPage: empty response")
            if (ack != MsConstants.RESPONSE_SUCCESS) {
                throw ProtocolException(
                    "burnPage: ECU returned 0x${ack.toUByte().toString(16)} (expected 0x30)"
                )
            }
        }
    }

    /**
     * Send a raw framed command and return the response payload.
     *
     * This is the primitive on which all higher-level methods are built.
     *
     * @param command  Command byte.
     * @param data     Payload bytes (may be empty).
     * @param timeout  Operation timeout in milliseconds.
     */
    suspend fun sendCommand(
        command: Byte,
        data: ByteArray = byteArrayOf(),
        timeout: Long = TIMEOUT_MS
    ): Result<ByteArray> = ioMutex.withLock {
        runCatching {
            sendAndReceive(command, data, timeout)
        }
    }

    /**
     * Expand and send a TunerStudio-style controller command template.
     *
     * Substitution tokens in [commandTemplate]:
     * - `%2i` → page/index as 2-byte **big-endian** (TunerStudio convention)
     * - `%2o` → offset as 2-byte **big-endian**
     * - `%2c` → count as 2-byte **big-endian**
     * - `%v`  → value as 1 byte
     * - `{varName}` → byte value from [pcVariables] (typically tsCanId)
     *
     * The remaining literal bytes after substitution are split into the first
     * byte (used as the command) and the rest (used as the payload).
     *
     * @param commandTemplate Template string, e.g. `"R%2i%2o%2c"` or `"B%2i%2o"`.
     * @param pcVariables    Map of variable name → byte value for `{var}` substitution.
     * @param value          Integer value for `%v` substitution.
     */
    suspend fun sendControllerCommand(
        commandTemplate: String,
        pcVariables: Map<String, Byte> = emptyMap(),
        value: Int = 0
    ): Result<ByteArray> = ioMutex.withLock {
        runCatching {
            val expanded = expandCommandTemplate(commandTemplate, pcVariables, value)
            if (expanded.isEmpty()) {
                throw ProtocolException("sendControllerCommand: template expanded to empty bytes")
            }
            val cmdByte = expanded[0]
            val payload = if (expanded.size > 1) expanded.copyOfRange(1, expanded.size) else emptyByteArray()
            sendAndReceive(cmdByte, payload, TIMEOUT_MS)
        }
    }

    /**
     * Reset the ECU communication state.
     *
     * Sends 'c' (comm reset) and clears the internal receive buffer.
     */
    suspend fun commReset(): Result<Unit> = ioMutex.withLock {
        runCatching {
            clearBuffer()
            val framed = MsEnvelope.frame(CMD_COMM_RESET, emptyByteArray())
            transport.send(framed)
            // Some ECUs respond, some don't — just clear again.
            clearBuffer()
        }
    }

    /**
     * Feed raw received bytes through the deframer.
     *
     * This is useful when bytes arrive from a streaming source outside
     * of a normal request/response cycle (e.g. a background data logger).
     *
     * @param data  Raw bytes received from the transport.
     * @return Zero or more complete, CRC-valid frames.
     */
    fun processIncomingData(data: ByteArray): List<FrameResult> {
        synchronized(receiveBuffer) {
            receiveBuffer.addAll(data.toList())
            return extractFrames()
        }
    }

    /** Discard all bytes currently in the receive buffer. */
    fun clearBuffer() {
        synchronized(receiveBuffer) {
            receiveBuffer.clear()
        }
    }

    /** Close the transport connection. */
    suspend fun close() {
        ioMutex.withLock {
            clearBuffer()
            transport.disconnect()
        }
    }

    // ==================================================================
    // Internal helpers
    // ==================================================================

    /**
     * Core send-receive loop.
     *
     * 1. Clears the receive buffer.
     * 2. Frames and sends [command] + [data] via the transport.
     * 3. Reads chunks from the transport, accumulating in [receiveBuffer],
     *    and tries to deframe after each chunk until a valid frame whose
     *    command byte matches [command] is found or [timeout] elapses.
     * 4. Returns the deframed payload.
     */
    private suspend fun sendAndReceive(
        command: Byte,
        data: ByteArray,
        timeout: Long
    ): ByteArray {
        clearBuffer()

        val framed = MsEnvelope.frame(command, data)
        transport.send(framed)

        return withTimeout(timeout) {
            // Read in chunks.  A max single-read of 512 bytes is generous
            // enough for any MS frame (max ~260 data + framing overhead).
            val chunkSize = 512
            while (true) {
                val chunk = tryRead(chunkSize)
                if (chunk == null || chunk.isEmpty()) continue

                val frames: List<FrameResult>
                synchronized(receiveBuffer) {
                    receiveBuffer.addAll(chunk.toList())
                    frames = extractFrames()
                }

                // Return the first frame whose command matches what we sent.
                for (frame in frames) {
                    if (frame.command == command) {
                        return@withTimeout frame.payload
                    }
                }
            }
            // Unreachable — while(true) only exits via return@withTimeout or exception.
            // Explicit ByteArray return helps the Kotlin compiler's type inference.
            @Suppress("UNREACHABLE_CODE")
            ByteArray(0)
        }
    }

    /**
     * Try to read [expectedLength] bytes from the transport.
     *
     * Returns `null` if the read times out or throws, in which case the
     * caller may retry or give up based on its own timeout wrapper.
     */
    private suspend fun tryRead(expectedLength: Int): ByteArray? {
        return withTimeoutOrNull(200L) {
            transport.receive(expectedLength)
        }
    }

    /**
     * Extract all complete frames currently in [receiveBuffer].
     *
     * **Must** be called while holding `synchronized(receiveBuffer)`.  For each
     * successfully deframed packet the consumed bytes are removed from the buffer
     * head.  Leading garbage bytes (before any 0x5A header) are also stripped.
     *
     * @return List of deframed results (may be empty).
     */
    private fun extractFrames(): List<FrameResult> {
        val results = mutableListOf<FrameResult>()

        while (receiveBuffer.isNotEmpty()) {
            val bufArray = receiveBuffer.toByteArray()
            val frame = MsEnvelope.deframe(bufArray, 0, bufArray.size)

            if (frame != null) {
                results.add(frame)
                // Remove the consumed bytes from the head of the buffer.
                repeat(frame.totalBytesConsumed) { receiveBuffer.removeAt(0) }
            } else {
                // No complete frame found.  Remove any leading non-header
                // bytes so the next read attempt doesn't re-process garbage.
                while (receiveBuffer.isNotEmpty() && receiveBuffer[0] != HEADER) {
                    receiveBuffer.removeAt(0)
                }
                break
            }
        }

        return results
    }

    // ------------------------------------------------------------------
    // Command template expansion (TunerStudio compatibility)
    // ------------------------------------------------------------------

    /**
     * Expand a TunerStudio command template into raw bytes.
     *
     * Tokens:
     * - `{varName}` → 1 byte from [pcVariables]
     * - `%2i` → page as 2-byte **big-endian** (from `pcVariables["page"]`, default 0)
     * - `%2o` → offset as 2-byte **big-endian** (from `pcVariables["offset"]`, default 0)
     * - `%2c` → count as 2-byte **big-endian** (from `pcVariables["count"]`, default 0)
     * - `%v`  → value as 1 byte (low byte of [value])
     *
     * Higher-level code that knows the exact page/offset/count should
     * populate those keys in [pcVariables] before calling.
     */
    private fun expandCommandTemplate(
        template: String,
        pcVariables: Map<String, Byte>,
        value: Int
    ): ByteArray {
        val out = ArrayList<Byte>(template.length + 16)
        var i = 0

        while (i < template.length) {
            if (i + 3 <= template.length && template.substring(i, i + 3) == "%2i") {
                // Page — 2 bytes big-endian. Check pcVariables for "page".
                val v = pcVariables["page"]?.toInt() ?: 0
                out.add(((v ushr 8) and 0xFF).toByte())
                out.add((v and 0xFF).toByte())
                i += 3
            } else if (i + 3 <= template.length && template.substring(i, i + 3) == "%2o") {
                // Offset — 2 bytes big-endian.
                val v = pcVariables["offset"]?.toInt() ?: 0
                out.add(((v ushr 8) and 0xFF).toByte())
                out.add((v and 0xFF).toByte())
                i += 3
            } else if (i + 3 <= template.length && template.substring(i, i + 3) == "%2c") {
                // Count — 2 bytes big-endian.
                val v = pcVariables["count"]?.toInt() ?: 0
                out.add(((v ushr 8) and 0xFF).toByte())
                out.add((v and 0xFF).toByte())
                i += 3
            } else if (i + 2 <= template.length && template.substring(i, i + 2) == "%v") {
                // Value — 1 byte.
                out.add((value and 0xFF).toByte())
                i += 2
            } else if (template[i] == '{') {
                // Variable substitution: {varName}
                val closeIdx = template.indexOf('}', i)
                if (closeIdx > i) {
                    val varName = template.substring(i + 1, closeIdx)
                    val b = pcVariables[varName]
                        ?: throw ProtocolException(
                            "sendControllerCommand: undefined pcVariable \"$varName\""
                        )
                    out.add(b)
                    i = closeIdx + 1
                } else {
                    // Malformed — emit literal '{'
                    out.add('{'.code.toByte())
                    i++
                }
            } else {
                out.add(template[i].code.toByte())
                i++
            }
        }

        return out.toByteArray()
    }

    // ------------------------------------------------------------------
    // Utility extensions
    // ------------------------------------------------------------------

    /** Strip trailing 0x00 bytes from a ByteArray. */
    private fun ByteArray.trimTrailingNulls(): ByteArray {
        var end = size
        while (end > 0 && this[end - 1] == 0.toByte()) end--
        return if (end == size) this else copyOfRange(0, end)
    }

    /** Reusable empty byte array to avoid repeated allocations. */
    private fun emptyByteArray(): ByteArray = EMPTY_BYTE_ARRAY
}

// ----------------------------------------------------------------------
// Exception types
// ----------------------------------------------------------------------

/** Thrown when the MS protocol layer detects an error. */
class ProtocolException(message: String, cause: Throwable? = null) : Exception(message, cause)
