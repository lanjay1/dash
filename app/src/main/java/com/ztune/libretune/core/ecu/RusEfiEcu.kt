package com.ztune.libretune.core.ecu

import com.ztune.libretune.core.ini.EcuDefinition
import com.ztune.libretune.core.ini.types.EcuType
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.MutableSharedFlow

/**
 * rusEFI ECU implementation (stub).
 *
 * rusEFI uses a completely different protocol from MegaSquirt:
 * - TunerStudio protocol over serial with its own framing format.
 * - Binary packet structure with a different header/CRC scheme.
 * - Supports an interactive console (tsConsole) that MS-based ECUs lack.
 * - Uses CAN bus extensively for inter-module communication.
 *
 * TODO: Implement the rusEFI TS protocol client.
 *   Key differences from MS protocol:
 *   - Frame format: `[header][packet_type][payload_len(2 LE)][payload...][crc(2)]`
 *   - CRC-32 (not CRC-16 like MS)
 *   - Separate channels for tuning data vs. real-time data
 *   - Online/Offline tuning modes
 *   - SD card file browsing and logging
 *
 * @see <a href="https://github.com/rusefi/rusefi/wiki/TunerStudio-protocol">rusefi TS protocol</a>
 */
class RusEfiEcu : EcuInterface {
    override val ecuType = EcuType.RUSEFI
    override var definition: EcuDefinition? = null
        private set
    override var isConnected: Boolean = false
        private set

    private val _realtimeUpdates = MutableSharedFlow<RealtimeUpdate>(replay = 1)
    override val realtimeUpdates: SharedFlow<RealtimeUpdate> = _realtimeUpdates

    override suspend fun connect(transport: EcuTransport, definition: EcuDefinition): Result<Unit> {
        TODO("rusEFI protocol client not yet implemented")
    }

    override suspend fun disconnect() {
        TODO("rusEFI protocol client not yet implemented")
    }

    override suspend fun querySignature(): Result<String> {
        TODO("rusEFI protocol client not yet implemented")
    }

    override suspend fun readBlock(page: Int, offset: Int, length: Int): Result<ByteArray> {
        TODO("rusEFI protocol client not yet implemented")
    }

    override suspend fun writeBlock(page: Int, offset: Int, data: ByteArray): Result<Unit> {
        TODO("rusEFI protocol client not yet implemented")
    }

    override suspend fun burnPage(page: Int): Result<Unit> {
        TODO("rusEFI protocol client not yet implemented")
    }

    override suspend fun readRealtimeData(): Result<ByteArray> {
        TODO("rusEFI protocol client not yet implemented")
    }

    override suspend fun sendControllerCommand(name: String, commandTemplate: String, value: Int): Result<ByteArray> {
        TODO("rusEFI protocol client not yet implemented")
    }

    override suspend fun startStreaming() {
        TODO("rusEFI protocol client not yet implemented")
    }

    override suspend fun stopStreaming() {
        TODO("rusEFI protocol client not yet implemented")
    }

    override suspend fun commReset(): Result<Unit> {
        TODO("rusEFI protocol client not yet implemented")
    }
}
