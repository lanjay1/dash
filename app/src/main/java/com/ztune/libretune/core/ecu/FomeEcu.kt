package com.ztune.libretune.core.ecu

import com.ztune.libretune.core.ini.EcuDefinition
import com.ztune.libretune.core.ini.types.EcuType
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.MutableSharedFlow

/**
 * FOME (Frankenso Open Motor control Ecu) implementation (stub).
 *
 * FOME extends the MegaSquirt serial protocol with some additions:
 * - Uses the same 0x5A framing and CRC-16 scheme as MS.
 * - Additional commands for CAN bus configuration.
 * - Extended output channel set beyond the standard MS layout.
 * - May use a modified burn sequence.
 *
 * TODO: Implement FOME-specific protocol extensions on top of MsProtocolClient.
 *   Until then this is a functional stub that will delegate to MsProtocolClient
 *   once the overrides are filled in.  The base MS protocol should work for
 *   basic tuning operations (read/write/burn/query).
 *
 * @see <a href="https://github.com/kaveman/FOME">FOME project</a>
 */
class FomeEcu : EcuInterface {
    override val ecuType = EcuType.FOME
    override var definition: EcuDefinition? = null
        private set
    override var isConnected: Boolean = false
        private set

    private val _realtimeUpdates = MutableSharedFlow<RealtimeUpdate>(replay = 1)
    override val realtimeUpdates: SharedFlow<RealtimeUpdate> = _realtimeUpdates

    override suspend fun connect(transport: EcuTransport, definition: EcuDefinition): Result<Unit> {
        TODO("FOME protocol client not yet implemented — delegates to MsProtocolClient with extensions")
    }

    override suspend fun disconnect() {
        TODO("FOME protocol client not yet implemented")
    }

    override suspend fun querySignature(): Result<String> {
        TODO("FOME protocol client not yet implemented")
    }

    override suspend fun readBlock(page: Int, offset: Int, length: Int): Result<ByteArray> {
        TODO("FOME protocol client not yet implemented")
    }

    override suspend fun writeBlock(page: Int, offset: Int, data: ByteArray): Result<Unit> {
        TODO("FOME protocol client not yet implemented")
    }

    override suspend fun burnPage(page: Int): Result<Unit> {
        TODO("FOME protocol client not yet implemented")
    }

    override suspend fun readRealtimeData(): Result<ByteArray> {
        TODO("FOME protocol client not yet implemented")
    }

    override suspend fun sendControllerCommand(name: String, commandTemplate: String, value: Int): Result<ByteArray> {
        TODO("FOME protocol client not yet implemented")
    }

    override suspend fun startStreaming() {
        TODO("FOME protocol client not yet implemented")
    }

    override suspend fun stopStreaming() {
        TODO("FOME protocol client not yet implemented")
    }

    override suspend fun commReset(): Result<Unit> {
        TODO("FOME protocol client not yet implemented")
    }
}
