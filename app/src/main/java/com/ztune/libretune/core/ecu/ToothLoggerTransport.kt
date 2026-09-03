package com.ztune.libretune.core.ecu

import com.ztune.libretune.core.ecu.EcuInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Transport layer for the rusEFI / FOME tooth (trigger) logger.
 *
 * Captures raw trigger-wheel events from the ECU so that the trigger
 * pattern can be inspected and validated on the device.
 */
class ToothLoggerTransport(
    private val ecu: EcuInterface,
) {

    /** A single tooth event decoded from the ECU response. */
    data class ToothEvent(
        val timeUs: Long,
        val isPrimary: Boolean,
        val isSecondary: Boolean,
    )

    companion object {
        // rusEFI TS-protocol command codes for the tooth logger.
        private const val TS_CMD_TOOTH_LOGGER_START: Int = 0x44
        private const val TS_CMD_TOOTH_LOGGER_STOP: Int = 0x45
    }

    /**
 * Start the on-ECU tooth logger.
 *
 * After this call the ECU begins recording every trigger edge
 * into a ring-buffer that is later retrieved with [stopCapture].
 */
    suspend fun startCapture() {
        withContext(Dispatchers.IO) {
            // sendControllerCommand returns Result<ByteArray>; throw on failure.
            ecu.sendControllerCommand(
                name = "tooth_logger_start",
                commandTemplate = "",
                value = TS_CMD_TOOTH_LOGGER_START,
            ).getOrThrow()
        }
    }

    /**
 * Stop the on-ECU tooth logger and download the captured data.
 *
 * @return The list of decoded [ToothEvent]s in chronological order.
 */
    suspend fun stopCapture(): List<ToothEvent> {
        return withContext(Dispatchers.IO) {
            val raw = ecu.sendControllerCommand(
                name = "tooth_logger_stop",
                commandTemplate = "",
                value = TS_CMD_TOOTH_LOGGER_STOP,
            ).getOrThrow()
            parseToothLog(raw)
        }
    }

    // ------------------------------------------------------------------
    // Response parsing
    // ------------------------------------------------------------------

    /**
 * Decode the raw byte payload returned by the ECU.
 *
 * rusEFI packs the tooth log as a sequence of 32-bit entries:
 *   bits 0-23  – time delta in µs (24-bit)
 *   bit  24     – primary trigger state (1 = rising, 0 = falling)
 *   bit  25     – secondary trigger state (1 = rising, 0 = falling)
 *   bits 26-31  – reserved
 *
 * Each entry is little-endian.
 */
    private fun parseToothLog(data: ByteArray): List<ToothEvent> {
        if (data.size < 4) return emptyList()

        val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        val events = mutableListOf<ToothEvent>()
        var cumulativeTime = 0L

        while (buf.remaining() >= 4) {
            val word = buf.getInt()
            val deltaUs = word and 0x00FF_FFFF          // 24-bit time delta
            val primary  = (word and (1 shl 24)) != 0    // bit 24
            val secondary = (word and (1 shl 25)) != 0   // bit 25

            cumulativeTime += deltaUs
            events.add(
                ToothEvent(
                    timeUs = cumulativeTime,
                    isPrimary = primary,
                    isSecondary = secondary,
                )
            )
        }

        return events
    }
}
