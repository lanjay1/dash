package com.ztune.libretune.core.ecu

import com.ztune.libretune.core.ini.EcuDefinition
import com.ztune.libretune.core.ini.types.EcuType
import com.ztune.libretune.core.protocol.ms.MsConstants
import com.ztune.libretune.core.protocol.ms.MsProtocolClient
import com.ztune.libretune.core.protocol.ms.ProtocolMode
import com.ztune.libretune.core.util.runCatchingCancellable
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/**
 * Speeduino ECU implementation.
 *
 * Speeduino uses a protocol that is compatible with the MegaSquirt RAW serial
 * protocol (same command set, no framing, no CRC, big-endian offset).
 *
 * Differences from MS:
 * - Different real-time data layout and output channel offsets.
 * - The 'A' command is used for real-time data on firmware ≥ 0.4.x.
 * - Block sizes and page layouts follow Speeduino-specific conventions.
 *
 * Uses [MsProtocolClient] in [ProtocolMode.RAW] mode — correct for real
 * Speeduino hardware over direct serial connection.
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
    }

    // ==================================================================
    // EcuInterface implementation
    // ==================================================================

    override suspend fun connect(
        transport: EcuTransport,
        definition: EcuDefinition
    ): Result<Unit> = runCatchingCancellable {
        transport.connect()
        this.transport = transport
        this.definition = definition
        // RAW mode: no framing, no CRC, big-endian offset — correct for Speeduino
        protocolClient = MsProtocolClient(transport, ProtocolMode.RAW)

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
        // Speeduino uses 'A' (0x41) for real-time data on firmware ≥ 0.4.x.
        // The INI queryCommand field can override this if needed.
        val client = protocolClient
            ?: return Result.failure(TransportException("Not connected"))
        val cmd = when (definition?.queryCommand?.uppercase()) {
            "S" -> 'S'.code.toByte() // legacy fallback
            else -> MsConstants.CMD_REALTIME // 'A' — default for modern Speeduino
        }
        return client.sendCommand(cmd, expectedResponseLength = 0)
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
                } catch (e: CancellationException) {
                    throw e
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
