package com.ztune.libretune.core.ecu

/**
 * Mock transport for testing without hardware.
 *
 * Implements the RAW MS serial protocol command set:
 *   - `Q` (0x51) → return 64-byte NUL-padded signature
 *   - `A` (0x41) → return a realistic realtime data block
 *   - `r` (0x72) → block read with big-endian offset (MS MCU standard)
 *   - `w` (0x77) → block write with big-endian offset
 *   - `B` (0x42) → return ACK byte
 *
 * This matches what [MsProtocolClient] in [ProtocolMode.RAW] sends,
 * so demo mode exercises the same code path as real MS/Speeduino hardware.
 *
 * NOTE: The mock does NOT implement FRAMED mode (0x5A + CRC-16). If a
 * FRAMED-mode MsProtocolClient sends a framed command, the mock will
 * see 0x5A as the first byte and return an empty response. This is
 * acceptable — FRAMED mode is for FOME/rusEFI which have their own
 * connection flow.
 *
 * @param responseMap Additional command→response mappings for custom test scenarios.
 * @param signature   The ECU signature string (should match a bundled INI definition).
 * @param memorySize  Size of the simulated ECU memory in bytes.
 */
class MockEcuTransport(
    private val responseMap: Map<Byte, ByteArray> = emptyMap(),
    private val signature: String = "Speeduino 202401",
    memorySize: Int = 8192
) : EcuTransport {

    private val memory = ByteArray(memorySize)
    private var _connected = false
    private var pendingCommand: Byte = 0
    private var pendingData: ByteArray = byteArrayOf()

    override suspend fun connect() {
        _connected = true
    }

    override suspend fun disconnect() {
        _connected = false
    }

    override fun isConnected(): Boolean = _connected

    override suspend fun send(data: ByteArray) {
        if (!_connected) throw TransportException("Not connected")
        if (data.isNotEmpty()) pendingCommand = data[0]
        if (data.size > 1) pendingData = data.copyOfRange(1, data.size)
        else pendingData = byteArrayOf()
    }

    override suspend fun receive(expectedLength: Int): ByteArray {
        if (!_connected) throw TransportException("Not connected")
        return when (pendingCommand) {
            // ---- Signature query: 'Q' (0x51) ----
            // Return 64-byte NUL-padded ASCII signature.
            'Q'.code.toByte() -> {
                val sig = signature.toByteArray(Charsets.US_ASCII)
                sig + ByteArray(64 - sig.size)
            }

            // ---- Realtime data burst: 'A' (0x41) ----
            // Return a realistic 20-byte block matching the placeholder INI's
            // [OutputChannels] layout (secl, rpm, tps, clt, iat, map, batt, afr, adv, pw).
            'A'.code.toByte() -> {
                generateRealtimeBlock()
            }

            // ---- Legacy realtime: 'S' (0x53) ----
            // Some older firmware uses 'S' instead of 'A'. Return same block.
            'S'.code.toByte() -> {
                generateRealtimeBlock()
            }

            // ---- Block read: 'r' (0x72) ----
            // Format: page(1) + offset(2 BE) + count(1)
            // Response: count bytes from memory.
            'r'.code.toByte() -> {
                if (pendingData.size >= 4) {
                    // Big-endian offset (MS MCU MC9S12 is big-endian)
                    val offset = ((pendingData[1].toInt() and 0xFF) shl 8) or
                                 (pendingData[2].toInt() and 0xFF)
                    val count = pendingData[3].toInt() and 0xFF
                    val end = (offset + count).coerceAtMost(memory.size)
                    val result = memory.copyOfRange(offset, end)
                    if (result.size < count) result + ByteArray(count - result.size) else result
                } else {
                    ByteArray(0)
                }
            }

            // ---- Block write: 'w' (0x77) ----
            // Format: page(1) + offset(2 BE) + data...
            // Response: single ACK byte (0x30).
            'w'.code.toByte() -> {
                if (pendingData.size >= 3) {
                    // Big-endian offset
                    val offset = ((pendingData[1].toInt() and 0xFF) shl 8) or
                                 (pendingData[2].toInt() and 0xFF)
                    val writeData = pendingData.copyOfRange(3, pendingData.size)
                    val safeLen = writeData.size.coerceAtMost(memory.size - offset)
                    if (safeLen > 0) writeData.copyInto(memory, offset, 0, safeLen)
                }
                byteArrayOf(0x30) // ACK
            }

            // ---- Burn: 'B' (0x42) ----
            // Response: single ACK byte (0x30).
            'B'.code.toByte() -> byteArrayOf(0x30)

            // ---- Comm reset: 'c' (0x63) ----
            // No meaningful response.
            'c'.code.toByte() -> ByteArray(0)

            // ---- Default: check responseMap, else empty ----
            else -> responseMap[pendingCommand] ?: ByteArray(0)
        }
    }

    override fun description(): String = "Mock ECU ($signature)"

    override fun transportType(): TransportType = TransportType.MOCK

    // ------------------------------------------------------------------
    // Realistic realtime data generation
    // ------------------------------------------------------------------

    /**
     * Generate a 20-byte realtime data block matching the placeholder
     * Speeduino INI's [OutputChannels] layout:
     *
     * ```
     * offset  type    channel          value (simulated)
     * ------  ----    -------          ----------------
     *   0     U08     secl             42 (seconds since boot)
     *   1     (pad)   —                0
     *   2     U16     rpm              850 (idle)
     *   4     U08     tps              5 (%)
     *   5     (pad)   —                0
     *   6     U16     coolant          85 (°C, raw = 85+40=125)
     *   8     U16     iat              35 (°C, raw = 35+40=75)
     *  10     U16     map              45 (kPa, idle vacuum)
     *  12     U08     batteryVoltage   142 (14.2V, raw = 142)
     *  13     (pad)   —                0
     *  14     U16     afr              147 (14.7 AFR, raw = 147)
     *  16     S16     ignitionAdvance  15 (deg, raw = 15)
     *  18     U16     pulseWidth       35 (3.5ms, raw = 35)
     * ```
     *
     * The RealtimeDecoder will apply `raw * scale + translate` to each
     * channel according to the INI definition. The raw values above are
     * chosen so that the decoded values are realistic.
     */
    private fun generateRealtimeBlock(): ByteArray {
        val block = ByteArray(20)
        // secl (U08 @ offset 0)
        block[0] = 42
        // rpm (U16 @ offset 2, big-endian for MS, little-endian for Speeduino INI placeholder)
        // Speeduino INI placeholder uses little-endian, so we encode LE
        val rpm = 850
        block[2] = (rpm and 0xFF).toByte()
        block[3] = ((rpm ushr 8) and 0xFF).toByte()
        // tps (U08 @ offset 4)
        block[4] = 5
        // coolant (U16 @ offset 6) — raw value depends on INI scale/translate
        // Placeholder INI: coolant = scalar, U16, 6, "C", 1.0, -40.0
        // So raw = (85 - (-40)) / 1.0 = 125
        val cltRaw = 125
        block[6] = (cltRaw and 0xFF).toByte()
        block[7] = ((cltRaw ushr 8) and 0xFF).toByte()
        // iat (U16 @ offset 8) — raw = (35 - (-40)) / 1.0 = 75
        val iatRaw = 75
        block[8] = (iatRaw and 0xFF).toByte()
        block[9] = ((iatRaw ushr 8) and 0xFF).toByte()
        // map (U16 @ offset 10) — raw = 45
        val map = 45
        block[10] = (map and 0xFF).toByte()
        block[11] = ((map ushr 8) and 0xFF).toByte()
        // batteryVoltage (U08 @ offset 12) — scale 0.1, so raw = 142 → 14.2V
        block[12] = 142.toByte()
        // afr (U16 @ offset 14) — scale 0.1, so raw = 147 → 14.7 AFR
        val afrRaw = 147
        block[14] = (afrRaw and 0xFF).toByte()
        block[15] = ((afrRaw ushr 8) and 0xFF).toByte()
        // ignitionAdvance (S16 @ offset 16) — raw = 15
        val adv = 15
        block[16] = (adv and 0xFF).toByte()
        block[17] = ((adv ushr 8) and 0xFF).toByte()
        // pulseWidth (U16 @ offset 18) — scale 0.1, so raw = 35 → 3.5ms
        val pw = 35
        block[18] = (pw and 0xFF).toByte()
        block[19] = ((pw ushr 8) and 0xFF).toByte()
        return block
    }

    // ------------------------------------------------------------------
    // Test helpers
    // ------------------------------------------------------------------

    /** Directly set memory contents for testing */
    fun setMemory(offset: Int, data: ByteArray) {
        val safeLen = data.size.coerceAtMost(memory.size - offset.coerceIn(0, memory.size))
        if (safeLen > 0) data.copyInto(memory, offset, 0, safeLen)
    }

    /** Read memory contents for testing */
    fun getMemory(offset: Int, length: Int): ByteArray {
        val end = (offset + length).coerceAtMost(memory.size)
        return memory.copyOfRange(offset, end)
    }
}
