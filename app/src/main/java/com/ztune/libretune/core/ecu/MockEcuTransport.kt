package com.ztune.libretune.core.ecu

/** Mock transport for testing without hardware */
class MockEcuTransport(
    private val responseMap: Map<Byte, ByteArray> = emptyMap(),
    private val signature: String = "mockSignature 0.1.0",
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
            'Q'.code.toByte() -> {
                // Signature query: return padded 64-byte ASCII signature
                val sig = signature.toByteArray(Charsets.US_ASCII)
                sig + ByteArray(64 - sig.size)
            }
            'R'.code.toByte() -> {
                // Block read: page(1), offset(2LE), count(1)
                if (pendingData.size >= 4) {
                    val offset =
                        ((pendingData[2].toInt() and 0xFF shl 8) or (pendingData[1].toInt() and 0xFF))
                    val count = pendingData[3].toInt() and 0xFF
                    val end = (offset + count).coerceAtMost(memory.size)
                    val result = memory.copyOfRange(offset, end)
                    if (result.size < count) result + ByteArray(count - result.size) else result
                } else {
                    ByteArray(0)
                }
            }
            'W'.code.toByte() -> {
                // Block write: page(1), offset(2LE), data...
                if (pendingData.size >= 3) {
                    val offset =
                        ((pendingData[2].toInt() and 0xFF shl 8) or (pendingData[1].toInt() and 0xFF))
                    val writeData = pendingData.copyOfRange(3, pendingData.size)
                    val safeLen = writeData.size.coerceAtMost(memory.size - offset)
                    if (safeLen > 0) writeData.copyInto(memory, offset, 0, safeLen)
                }
                byteArrayOf(0x30) // ACK
            }
            'B'.code.toByte() -> byteArrayOf(0x30) // Burn ACK
            else -> responseMap[pendingCommand] ?: ByteArray(0)
        }
    }

    override fun description(): String = "Mock ECU Transport"

    override fun transportType(): TransportType = TransportType.MOCK

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
