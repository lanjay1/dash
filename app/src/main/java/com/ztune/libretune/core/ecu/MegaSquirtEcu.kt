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
 * MegaSquirt ECU implementation using the RAW MS serial protocol.
 *
 * This is the primary ECU backend for MS1, MS2, and MS3 variants.
 * Communication goes through [MsProtocolClient] in [ProtocolMode.RAW]:
 *   - No framing (no 0x5A header, no byte-stuffing, no CRC)
 *   - Commands: `Q` (signature), `A` (realtime), `r` (read), `w` (write), `B` (burn), `c` (reset)
 *   - Big-endian offset encoding (MS MCU MC9S12 is big-endian)
 *
 * This matches what real MegaSquirt firmware expects over a direct serial
 * connection. The previous implementation used TS-BP framing (0x5A + CRC-16)
 * which is the rusEFI/FOME protocol, NOT the MS raw serial protocol.
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
    ): Result<Unit> = runCatchingCancellable {
        transport.connect()
        this.transport = transport
        this.definition = definition
        // RAW mode: no framing, no CRC, big-endian offset — correct for real MS hardware
        protocolClient = MsProtocolClient(transport, ProtocolMode.RAW)

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
        return protocolClient?.burnPage()
            ?: Result.failure(TransportException("Not connected"))
    }

    override suspend fun readRealtimeData(): Result<ByteArray> {
        // MS protocol: send 'A' (0x41) to request a real-time data burst.
        // The ECU responds with a raw payload containing all output channels
        // (layout defined by the INI [OutputChannels] section).
        //
        // Previous code used 'S' (0x53) which is incorrect — 'S' is not a
        // standard MS realtime command. 'A' is the correct command for
        // MS1/MS2/MS3 firmware.
        val client = protocolClient
            ?: return Result.failure(TransportException("Not connected"))
        // expectedResponseLength=0 → read until timeout gap (realtime block
        // size varies by firmware; the decoder handles variable length)
        return client.sendCommand(MsConstants.CMD_REALTIME, expectedResponseLength = 0)
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
