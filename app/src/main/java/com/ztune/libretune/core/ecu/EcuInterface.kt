package com.ztune.libretune.core.ecu

import com.ztune.libretune.core.ini.EcuDefinition

/**
 * High-level interface to any supported ECU.
 * Abstracts protocol differences so the UI works identically regardless of ECU type.
 *
 * Converted from LibreTune's Rust `ecu/` module which provides a trait-based
 * abstraction over MegaSquirt, Speeduino, rusEFI, FOME, and epicEFI.
 */
interface EcuInterface {
    /** The ECU type */
    val ecuType: com.ztune.libretune.core.ini.types.EcuType

    /** The parsed INI definition (null until connected and identified) */
    val definition: EcuDefinition?

    /** Whether the ECU is currently connected */
    val isConnected: Boolean

    /**
     * Connect to the ECU, identify it, and load the INI definition.
     *
     * @param transport   The transport channel (USB serial, Bluetooth, TCP, etc.)
     * @param definition  The INI definition that matches this ECU's firmware.
     * @return [Result.success] on successful connection, [Result.failure] otherwise.
     */
    suspend fun connect(transport: EcuTransport, definition: EcuDefinition): Result<Unit>

    /** Disconnect from the ECU and release all resources. */
    suspend fun disconnect()

    /** Read the ECU's signature string (e.g. "Megasquirt-Extra 3.1.x"). */
    suspend fun querySignature(): Result<String>

    /** Read a block of ECU memory. */
    suspend fun readBlock(page: Int, offset: Int, length: Int): Result<ByteArray>

    /** Write a block to ECU memory. */
    suspend fun writeBlock(page: Int, offset: Int, data: ByteArray): Result<Unit>

    /** Burn (persist) the current page to flash. */
    suspend fun burnPage(page: Int): Result<Unit>

    /** Read real-time data (output channels) as a raw byte array. */
    suspend fun readRealtimeData(): Result<ByteArray>

    /**
     * Send a controller command from the INI definition.
     *
     * @param name            Human-readable command name (for logging/debugging).
     * @param commandTemplate TunerStudio-style command template string.
     * @param value           Optional integer value to substitute into the template.
     * @return The ECU's response payload, or failure.
     */
    suspend fun sendControllerCommand(
        name: String,
        commandTemplate: String,
        value: Int = 0
    ): Result<ByteArray>

    /** Start data streaming for live dashboard. */
    suspend fun startStreaming()

    /** Stop data streaming. */
    suspend fun stopStreaming()

    /** Stream of real-time data updates (observed by the UI layer). */
    val realtimeUpdates: kotlinx.coroutines.flow.SharedFlow<RealtimeUpdate>

    /** Request a communication reset (clear buffers, resync). */
    suspend fun commReset(): Result<Unit>
}

/**
 * A single real-time data update from the ECU.
 *
 * @property timestamp      Wall-clock time when this update was received.
 * @property rawData        Raw bytes as read from the ECU (before any scaling).
 * @property channelValues  Optional pre-decoded channel name → scaled value map.
 *                         Populated by the streaming loop when output channel
 *                         definitions are available.
 */
data class RealtimeUpdate(
    val timestamp: Long = System.currentTimeMillis(),
    val rawData: ByteArray,
    val channelValues: Map<String, Double> = emptyMap()
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RealtimeUpdate) return false
        return timestamp == other.timestamp && rawData.contentEquals(other.rawData)
    }

    override fun hashCode(): Int = 31 * timestamp.hashCode() + rawData.contentHashCode()
}
