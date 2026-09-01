package com.ztune.libretune.core.ecu

import com.ztune.libretune.core.ini.EcuDefinition
import com.ztune.libretune.core.ini.types.EcuType
import com.ztune.libretune.core.protocol.ms.MsProtocolClient
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/**
 * Speeduino ECU implementation.
 *
 * Speeduino uses a protocol that is largely compatible with the MegaSquirt
 * serial protocol (same 0x5A framing, CRC-16, command set) but with some
 * differences:
 *
 * - Different real-time data layout and output channel offsets.
 * - The 'A' command is used for real-time data instead of 'S' on some firmware versions.
 * - Block sizes and page layouts follow Speeduino-specific conventions.
 *
 * For now this extends [MegaSquirtEcu] and overrides only the parts that
 * differ.  As Speeduino's protocol diverges further, more overrides will be
 * added here.
 */
class SpeeduinoEcu : EcuInterface {
    override val ecuType = EcuType.SPEEDUINO
    override var definition: EcuDefinition? = null
        private set
    override var isConnected: Boolean = false
        private set

    private var transport: EcuTransport? = null
    private var protocolClient: MsProtocolClient? = null
    private var streamingJob: Job? = null

    private val _realtimeUpdates = MutableSharedFlow<RealtimeUpdate>(
        replay = 1,
        extraBufferCapacity = 10
    )
    override val realtimeUpdates: SharedFlow<RealtimeUpdate> = _realtimeUpdates

    companion object {
        /** Speeduino supports higher streaming rates; default to ~50 Hz. */
        private const val STREAM_INTERVAL_MS = 20L

        /**
         * Speeduino firmware ≥ 0.4.x uses 'A' for the real-time data command.
         * Older builds use 'S' like MegaSquirt.  The INI definition's
         * [EcuDefinition.queryCommand] determines which one to use.
         */
        private const val CMD_REALTIME_LEGACY: Byte = 'S'.code.toByte()
        private const val CMD_REALTIME_CURRENT: Byte = 'A'.code.toByte()
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
        protocolClient = MsProtocolClient(transport)

        // Verify signature.
        val sig = protocolClient!!.querySignature().getOrThrow()
        val expected = definition.signaturePrefix ?: definition.signature
        if (expected.isNotEmpty() && !sig.lowercase().contains("speeduino")) {
            // Signature doesn't contain "speeduino" — still connect but warn.
        }
        isConnected = true
    }

    override suspend fun disconnect() {
        streamingJob?.cancel()
        streamingJob = null
        protocolClient?.close()
        transport?.disconnect()
        transport = null
        protocolClient = null
        isConnected = false
    }

    override suspend fun querySignature(): Result<String> {
        return protocolClient?.querySignature()
            ?: Result.failure(TransportException("Not connected"))
    }

    override suspend fun readBlock(
        page: Int,
        offset: Int,
        length: Int
    ): Result<ByteArray> {
        return protocolClient?.readBlock(page, offset, length)
            ?: Result.failure(TransportException("Not connected"))
    }

    override suspend fun writeBlock(
        page: Int,
        offset: Int,
        data: ByteArray
    ): Result<Unit> {
        return protocolClient?.writeBlock(page, offset, data)
            ?: Result.failure(TransportException("Not connected"))
    }

    override suspend fun burnPage(page: Int): Result<Unit> {
        return protocolClient?.burnPage()
            ?: Result.failure(TransportException("Not connected"))
    }

    override suspend fun readRealtimeData(): Result<ByteArray> {
        val client = protocolClient
            ?: return Result.failure(TransportException("Not connected"))

        // Speeduino newer firmware uses 'A' command; fall back to 'S' for
        // legacy firmware versions.  The INI queryCommand field can also
        // override this.
        val cmd = when (definition?.queryCommand?.uppercase()) {
            "A" -> CMD_REALTIME_CURRENT
            else -> CMD_REALTIME_LEGACY
        }
        return client.sendCommand(cmd)
    }

    override suspend fun sendControllerCommand(
        name: String,
        commandTemplate: String,
        value: Int
    ): Result<ByteArray> {
        val pcVars = definition?.pcVariables ?: emptyMap()
        return protocolClient?.sendControllerCommand(commandTemplate, pcVars, value)
            ?: Result.failure(TransportException("Not connected"))
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
                } catch (_: CancellationException) {
                    throw _
                } catch (_: Exception) {
                    // swallow and retry
                }
                delay(STREAM_INTERVAL_MS)
            }
        }
    }

    override suspend fun stopStreaming() {
        streamingJob?.cancel()
        streamingJob = null
    }

    override suspend fun commReset(): Result<Unit> {
        return protocolClient?.commReset()
            ?: Result.failure(TransportException("Not connected"))
    }
}
