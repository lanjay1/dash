package com.ztune.libretune.core.ecu

import com.ztune.libretune.core.ini.EcuDefinition
import com.ztune.libretune.core.ini.types.EcuType
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.MutableSharedFlow

/**
 * epicEFI ECU implementation (stub).
 *
 * epicEFI is a relatively new open-source ECU platform.  Protocol details
 * are still being documented.  When available, this class will implement
 * the [EcuInterface] using epicEFI's native protocol.
 *
 * TODO: Implement epicEFI protocol client once the wire protocol is documented.
 *   Initial investigation suggests it may use a TS-compatible protocol layer
 *   similar to Speeduino, which would allow delegation to MsProtocolClient.
 */
class EpicEfiEcu : EcuInterface {
    override val ecuType = EcuType.EPICEFI
    override var definition: EcuDefinition? = null
        private set
    override var isConnected: Boolean = false
        private set

    private val _realtimeUpdates = MutableSharedFlow<RealtimeUpdate>(replay = 1)
    override val realtimeUpdates: SharedFlow<RealtimeUpdate> = _realtimeUpdates

    override suspend fun connect(transport: EcuTransport, definition: EcuDefinition): Result<Unit> {
        TODO("epicEFI protocol client not yet implemented")
    }

    override suspend fun disconnect() {
        TODO("epicEFI protocol client not yet implemented")
    }

    override suspend fun querySignature(): Result<String> {
        TODO("epicEFI protocol client not yet implemented")
    }

    override suspend fun readBlock(page: Int, offset: Int, length: Int): Result<ByteArray> {
        TODO("epicEFI protocol client not yet implemented")
    }

    override suspend fun writeBlock(page: Int, offset: Int, data: ByteArray): Result<Unit> {
        TODO("epicEFI protocol client not yet implemented")
    }

    override suspend fun burnPage(page: Int): Result<Unit> {
        TODO("epicEFI protocol client not yet implemented")
    }

    override suspend fun readRealtimeData(): Result<ByteArray> {
        TODO("epicEFI protocol client not yet implemented")
    }

    override suspend fun sendControllerCommand(name: String, commandTemplate: String, value: Int): Result<ByteArray> {
        TODO("epicEFI protocol client not yet implemented")
    }

    override suspend fun startStreaming() {
        TODO("epicEFI protocol client not yet implemented")
    }

    override suspend fun stopStreaming() {
        TODO("epicEFI protocol client not yet implemented")
    }

    override suspend fun commReset(): Result<Unit> {
        TODO("epicEFI protocol client not yet implemented")
    }
}
