package com.ztune.libretune.core.ecu

import com.ztune.libretune.core.ini.EcuDefinition
import com.ztune.libretune.core.ini.types.EcuType
import com.ztune.libretune.core.protocol.ms.MsProtocolClient
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/**
 * FOME (Frankenso Open Motor control Ecu) implementation.
 *
 * FOME extends the MegaSquirt serial protocol with additional features:
 * - Uses the same 0x5A framing and CRC-16 scheme as MS.
 * - Custom signature format that includes "FOME" identifier.
 * - Additional calibration commands for CAN bus configuration.
 * - Extended burn sequence that may require a page-specific command.
 * - Reads the INI's [ProtocolSettings.burnCommand] for burn overrides.
 *
 * This class delegates to [MsProtocolClient] for the core MS protocol
 * and adds FOME-specific behaviour on top.
 *
 * @see <a href="https://github.com/kaveman/FOME">FOME project</a>
 */
class FomeEcu : EcuInterface {
    override val ecuType = EcuType.FOME
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
        /** FOME streams at a moderate rate to avoid saturating slower CAN links. */
        private const val STREAM_INTERVAL_MS = 50L

        /**
         * FOME-specific calibration command: request CAN channel configuration.
         * Sent as a controller command that returns a binary payload describing
         * the CAN bus setup (baud rate, ID filter, etc.).
         */
        private const val CMD_FOME_CAN_CONFIG: Byte = 'C'.code.toByte()

        /**
         * FOME extended signature query command.
         * Some FOME firmware builds use 'F' instead of 'Q' to return an
         * extended signature that includes board revision and build date.
         */
        private const val CMD_FOME_EXTENDED_SIG: Byte = 'F'.code.toByte()
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

        // FOME first attempts the extended signature command ('F');
        // if the firmware doesn't support it, fall back to standard 'Q'.
        val sig = querySignature().getOrThrow()
        val expected = definition.signaturePrefix ?: definition.signature
        if (expected.isNotEmpty() && !sig.lowercase().contains("fome")) {
            // Signature doesn't contain "fome" — still connect; caller may warn.
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

    /**
     * FOME signature query: try the extended 'F' command first, fall back
     * to the standard 'Q' if the extended command is unsupported.
     */
    override suspend fun querySignature(): Result<String> {
        val client = protocolClient
            ?: return Result.failure(TransportException("Not connected"))

        // Attempt extended signature; if it fails or returns empty, use standard.
        val extendedResult = client.sendCommand(CMD_FOME_EXTENDED_SIG)
        if (extendedResult.isSuccess) {
            val payload = extendedResult.getOrThrow()
            val text = payload
                .takeWhile { it != 0.toByte() }
            .toByteArray()
            val sig = String(text, Charsets.US_ASCII).trim()
            if (sig.isNotEmpty() && sig.contains("fome", ignoreCase = true)) {
                return Result.success(sig)
            }
        }
        // Fall back to standard MS signature query.
        return client.querySignature()
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

    /**
     * FOME burn: uses the INI-defined burn command if available,
     * otherwise defaults to the standard 'B' command.
     */
    override suspend fun burnPage(page: Int): Result<Unit> {
        val client = protocolClient
            ?: return Result.failure(TransportException("Not connected"))

        val burnCmdStr = definition?.protocol?.burnCommand
        val burnCmdByte = if (
            burnCmdStr != null &&
            burnCmdStr.length == 1 &&
            burnCmdStr != "B"
        ) {
            burnCmdStr[0].code.toByte()
        } else {
            null
        }
        return client.burnPage(commandOverride = burnCmdByte)
    }

    /**
     * FOME real-time data: delegates to the standard 'S' command via MsProtocolClient.
     */
    override suspend fun readRealtimeData(): Result<ByteArray> {
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

    override suspend fun commReset(): Result<Unit> {
        return protocolClient?.commReset()
            ?: Result.failure(TransportException("Not connected"))
    }

    // ==================================================================
    // FOME-specific API
    // ==================================================================

    /**
     * Read the FOME CAN bus configuration block.
     *
     * Sends the 'C' command and returns a binary payload describing the
     * current CAN channel settings (baud rate, filter IDs, listen mode, etc.).
     */
    suspend fun readCanConfig(): Result<ByteArray> {
        return protocolClient?.sendCommand(CMD_FOME_CAN_CONFIG)
            ?: Result.failure(TransportException("Not connected"))
    }
}
