package com.ztune.libretune.core.protocol.ms

/**
 * Result of successfully deframing a single MS protocol frame.
 *
 * @property command  The command byte that was inside the frame.
 * @property payload  The decoded payload bytes (after un-escaping and CRC removal).
 * @property totalBytesConsumed  How many bytes of the input buffer were consumed,
 *   including header, escaped payload, CRC, etc.
 */
data class FrameResult(
    val command: Byte,
    val payload: ByteArray,
    val totalBytesConsumed: Int
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is FrameResult) return false
        return command == other.command &&
                payload.contentEquals(other.payload) &&
                totalBytesConsumed == other.totalBytesConsumed
    }

    override fun hashCode(): Int {
        var result = command.toInt()
        result = 31 * result + payload.contentHashCode()
        result = 31 * result + totalBytesConsumed
        return result
    }
}

/**
 * MegaSquirt protocol envelope / framing layer.
 *
 * Frame wire format:
 *   [0x5A] [command] [data…] [CRC_lo] [CRC_hi]
 *
 * Any byte equal to [0x5A] or [0x7D] that appears in command, data, or CRC
 * is escaped by inserting a 0x7D prefix and XOR-ing the byte with 0x20.
 *
 * CRC is CRC-16/CCITT-USB (XModem polynomial 0x1021, init 0xFFFF) computed
 * over the **un-escaped** command + payload bytes.
 */
object MsEnvelope {

    private const val HEADER: Byte = 0x5A
    private const val ESCAPE: Byte = 0x7D
    private const val ESCAPE_XOR: Byte = 0x20

    // ---- CRC-16/CCITT table (pre-computed for the XModem polynomial) ----

    private val crcTable = IntArray(256) { i ->
        var crc = i shl 8
        repeat(8) {
            crc = if ((crc and 0x8000) != 0) {
                (crc shl 1) xor 0x1021
            } else {
                crc shl 1
            }
        }
        crc and 0xFFFF
    }

    /**
     * Compute CRC-16/CCITT-USB over [data].
     *
     * The CRC is computed with init value 0xFFFF and no final XOR.
     * Returns a [Short] so the caller can easily treat it as two bytes.
     */
    fun calculateCrc(data: ByteArray): Short {
        var crc = 0xFFFF
        for (b in data) {
            crc = ((crc shl 8) xor crcTable[((crc ushr 8) xor (b.toInt() and 0xFF)) and 0xFF]) and 0xFFFF
        }
        return crc.toShort()
    }

    // ------------------------------------------------------------------
    // Public helpers
    // ------------------------------------------------------------------

    /** Whether a byte must be escaped before transmission. */
    private fun needsEscape(b: Byte): Boolean = b == HEADER || b == ESCAPE

    /** Write an un-escaped byte into [out], inserting escape when needed. */
    private fun writeByte(out: ArrayList<Byte>, b: Byte) {
        if (needsEscape(b)) {
            out.add(ESCAPE)
            out.add((b.toInt() xor ESCAPE_XOR.toInt()).toByte())
        } else {
            out.add(b)
        }
    }

    // ------------------------------------------------------------------
    // Frame  (encode)
    // ------------------------------------------------------------------

    /**
     * Build a fully-framed transmit packet.
     *
     * The resulting byte array is ready to be sent over the transport.
     * Format: 0x5A | escaped(command + data) | escaped(CRC_lo) | escaped(CRC_hi)
     *
     * @param command  Single command byte.
     * @param data     Payload bytes (may be empty).
     * @return Framed byte array.
     */
    fun frame(command: Byte, data: ByteArray): ByteArray {
        // Build the raw content that the CRC covers: command + payload
        val raw = ByteArray(1 + data.size)
        raw[0] = command
        if (data.isNotEmpty()) System.arraycopy(data, 0, raw, 1, data.size)

        val crc = calculateCrc(raw)
        val crcLo = (crc.toInt() and 0xFF).toByte()
        val crcHi = ((crc.toInt() ushr 8) and 0xFF).toByte()

        val out = ArrayList<Byte>(1 + (raw.size + 2) * 2) // worst-case every byte escaped
        out.add(HEADER)
        for (b in raw) writeByte(out, b)
        writeByte(out, crcLo)
        writeByte(out, crcHi)

        return out.toByteArray()
    }

    // ------------------------------------------------------------------
    // Deframe (decode)
    // ------------------------------------------------------------------

    /**
     * Attempt to parse one complete frame from [input] starting at [offset].
     *
     * The caller should feed bytes into an accumulating buffer and call this
     * repeatedly, advancing by [FrameResult.totalBytesConsumed] each time,
     * until it returns `null` (incomplete frame).
     *
     * @param input   Buffer containing received bytes.
     * @param offset  Byte index at which to start looking for a frame.
     * @param length  Number of valid bytes starting from [offset].
     * @return A [FrameResult] if a complete, CRC-valid frame was found, or `null`
     *   if the buffer does not yet contain a complete frame.
     */
    fun deframe(input: ByteArray, offset: Int, length: Int): FrameResult? {
        // ---- Step 1: find the header byte ----------------------------
        var pos = offset
        val end = offset + length

        // Skip non-header bytes (protocol sync / garbage)
        while (pos < end && input[pos] != HEADER) {
            pos++
        }
        if (pos >= end) return null

        // pos now points at a 0x5A header
        val headerPos = pos
        pos++

        // ---- Step 2: un-escape and collect until we have enough for
        //   at least command(1) + CRC(2) = 3 bytes -------------------
        val unescaped = ArrayList<Byte>()
        var escaping = false

        while (pos < end) {
            val b = input[pos]
            pos++

            if (escaping) {
                unescaped.add((b.toInt() xor ESCAPE_XOR.toInt()).toByte())
                escaping = false
            } else if (b == ESCAPE) {
                escaping = true
            } else if (b == HEADER) {
                // We hit what looks like the start of a *new* frame.
                // The current frame is incomplete — back up one byte so the
                // next call can re-scan from this header.
                pos--
                break
            } else {
                unescaped.add(b)
            }

            // Once we have at least 1 (command) + 2 (CRC) = 3 bytes we
            // can check whether the frame is complete.
            if (unescaped.size >= 3) {
                // The last two bytes of the unescaped stream are CRC.
                val payloadLen = unescaped.size - 3  // minus command, crcLo, crcHi
                // We don't know the intended payload length a-priori — we
                // rely on the CRC to validate.  To avoid consuming trailing
                // bytes that belong to the *next* frame, we verify the CRC
                // now.  If it matches, the frame is complete.
                val payload = ByteArray(payloadLen)
                for (i in 0 until payloadLen) {
                    payload[i] = unescaped[i + 1] // skip command byte at [0]
                }
                val command = unescaped[0]
                val crcLo = unescaped[unescaped.size - 2]
                val crcHi = unescaped[unescaped.size - 1]
                val receivedCrc = ((crcHi.toInt() and 0xFF) shl 8) or (crcLo.toInt() and 0xFF)

                // Recompute CRC over command + payload
                val raw = ByteArray(1 + payloadLen)
                raw[0] = command
                if (payloadLen > 0) System.arraycopy(payload, 0, raw, 1, payloadLen)
                val expectedCrc = calculateCrc(raw).toInt() and 0xFFFF

                if (receivedCrc == expectedCrc) {
                    return FrameResult(
                        command = command,
                        payload = payload,
                        totalBytesConsumed = pos - headerPos
                    )
                }
                // CRC mismatch — the frame is not valid yet (or ever).
                // Continue accumulating; the extra bytes may complete a
                // longer valid frame.
            }
        }

        // Reached end of input without a complete, valid frame.
        return null
    }
}
