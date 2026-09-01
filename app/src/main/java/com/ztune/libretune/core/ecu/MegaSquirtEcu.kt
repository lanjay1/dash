package com.ztune.libretune.core.ecu

import com.ztune.libretune.core.ini.EcuDefinition
import com.ztune.libretune.core.ini.types.EcuType
import com.ztune.libretune.core.protocol.ms.MsProtocolClient
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/**
 * MegaSquirt ECU implementation using the msEnvelope protocol.
 *
 * This is the primary ECU backend for MS1, MS2, and MS3 variants.
 * All communication goes through [MsProtocolClient] which handles the
 * 0x5A header framing, 0x7D byte-stuffing, and CRC-16 integrity checks.
 */
class MegaSquirtEcu : EcuInterface {
    override val ecuType = EcuType.MEGASQUIRT
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
        /** Default polling interval for real-time data streaming (~20 Hz). */
        private const val STREAM_INTERVAL_MS = 50L
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

        // Query signature to verify the connection and firmware match.
        val sig = protocolClient!!.querySignature().getOrThrow()
        val expected = definition.signaturePrefix ?: definition.signature
        if (expected.isNotEmpty() && !sig.startsWith(expected)) {
            // Signature mismatch — still connect but the caller may want
            // to warn the user.  Some ECUs report version-specific strings
            // that don't exactly match the INI's static signature.
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
        // TODO: pass page-specific burn command overrides from the INI definition
        // when ProtocolSettings.burnCommand is populated.
        return protocolClient?.burnPage()
            ?: Result.failure(TransportException("Not connected"))
    }

    override suspend fun readRealtimeData(): Result<ByteArray> {
        // MS protocol: send 'S' (0x53) to request a single real-time data burst.
        // The ECU responds with a framed payload containing all output channels.
        val client = protocolClient
            ?: return Result.failure(TransportException("Not connected"))
        return client.sendCommand('S'.code.toByte())
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
                    throw _ // re-throw so coroutine cancellation propagates
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

    override suspend fun commReset(): Result<Unit> {
        return protocolClient?.commReset()
            ?: Result.failure(TransportException("Not connected"))
    }
}
