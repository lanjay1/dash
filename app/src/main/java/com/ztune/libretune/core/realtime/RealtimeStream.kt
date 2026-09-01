package com.ztune.libretune.core.realtime

import com.ztune.libretune.core.ini.EcuDefinition
import com.ztune.libretune.core.ini.types.Constant
import com.ztune.libretune.core.ini.types.CurveDefinition
import com.ztune.libretune.core.ini.types.TableAxis
import com.ztune.libretune.core.ini.types.TableDefinition
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.*
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

// ============================================================================
// RealtimeData – a decoded snapshot
// ============================================================================

/**
 * An immutable snapshot of all decoded realtime channel values at a point in
 * time, together with lightweight metadata (update index, wall-clock timestamp,
 * and the set of channel names whose values changed since the previous
 * snapshot).
 *
 * This is the primary value type emitted by [RealtimeStream] and consumed by
 * the UI layer.
 *
 * Converted from LibreTune's Rust `RealtimeData` struct.
 */
data class RealtimeData(
    /** Monotonically increasing update counter (wraps at [Long.MAX_VALUE]). */
    val updateIndex: Long,
    /** Wall-clock epoch-millis when this snapshot was produced. */
    val timestamp: Long,
    /** Decoded channel values: `channelName → displayValue`. */
    val channels: Map<String, Double>,
    /**
     * Channel names whose value differs from the previous snapshot.
     * Empty on the very first update or when change detection is disabled.
     * Useful for the UI to skip re-rendering unchanged gauges.
     */
    val changedChannels: Set<String> = emptySet()
) {
    /** Convenience: get a channel value, returning 0.0 if absent or NaN. */
    fun getChannel(name: String, fallback: Double = 0.0): Double {
        val v = channels[name]
        return if (v != null && !v.isNaN()) v else fallback
    }

    /** True when this snapshot contains no channel data. */
    val isEmpty: Boolean get() = channels.isEmpty()
}

// ============================================================================
// RealtimeStream – reactive wrapper around RealtimeDecoder
// ============================================================================

/**
 * Manages the lifecycle of realtime data decoding and exposes the results as
 * reactive [StateFlow]s suitable for Android UI observation.
 *
 * Typical usage from the streaming loop:
 * ```kotlin
 * val stream = RealtimeStream(definition)
 * // ... in the polling loop:
 * stream.processRawData(rawBytes)
 * // ... UI collects:
 * stream.data.collect { snapshot -> updateGauges(snapshot) }
 * ```
 *
 * **Thread safety** – [processRawData] may be called from any thread
 * (e.g. a coroutine on `Dispatchers.IO`).  All state is updated atomically so
 * that collectors on the main thread observe consistent snapshots.
 *
 * Converted from LibreTune's Rust `realtime/` module streaming infrastructure.
 */
class RealtimeStream(
    definition: EcuDefinition,
    /** External coroutine scope for shared flow lifecycle. Falls back to a
     *  default scope using [Dispatchers.Default] if not provided. */
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
) {

    // ---- core decoder ----
    private val decoder = RealtimeDecoder(definition)

    // ---- atomic counters (lock-free, callable from any thread) ----
    private val _updateIndex = AtomicLong(0L)
    private val _totalBytesProcessed = AtomicLong(0L)
    private val _errorCount = AtomicInteger(0)

    // ---- previous snapshot for change detection ----
    @Volatile
    private var previousChannels: Map<String, Double> = emptyMap()

    // ---- public flows ----

    /**
     * Latest decoded snapshot, updated on every successful [processRawData] call.
     * Always contains the most-recently-decoded values.
     */
    val data: StateFlow<RealtimeData> = MutableStateFlow(
        RealtimeData(
            updateIndex = 0,
            timestamp = System.currentTimeMillis(),
            channels = emptyMap()
        )
    )

    /**
     * A shared flow that emits only when channel values actually change.
     *
     * Unlike [data], which emits on every poll (even if nothing changed),
     * [dataChanges] filters out no-op updates.  This is useful for triggering
     * work only when new data arrives.
     */
    val dataChanges: SharedFlow<RealtimeData> = data
        .filter { it.changedChannels.isNotEmpty() }
        .shareIn(
            scope = scope,
            started = SharingStarted.Eagerly,
            replay = 1
        )

    /**
     * Stream of raw byte arrays before decoding.
     * Useful for datalogging or debugging.
     */
    private val _rawDataFlow = MutableSharedFlow<ByteArray>(
        replay = 0,
        extraBufferCapacity = 64
    )
    val rawDataFlow: SharedFlow<ByteArray> get() = _rawDataFlow

    // ========================================================================
    // Public API
    // ========================================================================

    /**
     * Decode a raw ECU output-channel buffer and publish the result.
     *
     * This is the primary entry point called from the ECU streaming loop.
     * It decodes the bytes, computes change detection, and updates the
     * [data] state flow.
     *
     * @param rawData The raw byte buffer received from the ECU.
     * @return The decoded [RealtimeData] snapshot, or `null` on error.
     */
    fun processRawData(rawData: ByteArray): RealtimeData? {
        _rawDataFlow.tryEmit(rawData)
        _totalBytesProcessed.addAndGet(rawData.size.toLong())

        return try {
            val channels = decoder.decodeRealtimeData(rawData)
            val now = System.currentTimeMillis()
            val idx = _updateIndex.incrementAndGet()

            // Change detection: compare against previous snapshot
            val changed = mutableSetOf<String>()
            if (previousChannels.isNotEmpty()) {
                for ((name, value) in channels) {
                    val prev = previousChannels[name]
                    if (prev == null || prev != value) {
                        changed.add(name)
                    }
                }
            } else {
                // First update – everything is "new"
                changed.addAll(channels.keys)
            }
            previousChannels = channels

            val snapshot = RealtimeData(
                updateIndex = idx,
                timestamp = now,
                channels = channels,
                changedChannels = changed
            )

            (data as MutableStateFlow).value = snapshot
            snapshot
        } catch (e: Exception) {
            _errorCount.incrementAndGet()
            null
        }
    }

    // ---- convenience decode helpers (delegate to the internal decoder) ----

    /** Decode a single constant from page data. */
    fun decodeConstant(name: String, pageData: ByteArray, constant: Constant): Double =
        decoder.decodeConstant(name, pageData, constant)

    /** Encode a constant value back to raw bytes. */
    fun encodeConstant(value: Double, constant: Constant): ByteArray =
        decoder.encodeConstant(value, constant)

    /** Decode a table from page data. */
    fun decodeTable(pageData: ByteArray, table: TableDefinition): List<List<Double>> =
        decoder.decodeTable(pageData, table)

    /** Decode a curve from page data. */
    fun decodeCurve(pageData: ByteArray, curve: CurveDefinition): List<Double> =
        decoder.decodeCurve(pageData, curve)

    /** Decode table axis bins from page data. */
    fun decodeTableAxis(pageData: ByteArray, axis: TableAxis?): List<Double> =
        decoder.decodeTableAxis(pageData, axis)

    // ---- statistics ----

    /** Number of successful decode updates so far. */
    fun updateCount(): Long = _updateIndex.get()

    /** Total raw bytes fed into [processRawData]. */
    fun totalBytesProcessed(): Long = _totalBytesProcessed.get()

    /** Number of decode errors (exceptions caught in [processRawData]). */
    fun errorCount(): Int = _errorCount.get()

    /**
     * Compute the average update rate in updates/second over the lifetime
     * of this stream.
     *
     * @return Updates per second, or 0.0 if no updates have been processed.
     */
    fun averageUpdateRateHz(): Double {
        val count = _updateIndex.get()
        if (count < 2) return 0.0
        val firstTs = data.value.timestamp
        val elapsed = (System.currentTimeMillis() - firstTs) / 1000.0
        return if (elapsed > 0) count / elapsed else 0.0
    }

    /**
     * Reset all counters and state. Useful when switching ECUs or after
     * a disconnect/reconnect cycle.
     */
    fun reset() {
        _updateIndex.set(0L)
        _totalBytesProcessed.set(0L)
        _errorCount.set(0)
        previousChannels = emptyMap()
        (data as MutableStateFlow).value = RealtimeData(
            updateIndex = 0,
            timestamp = System.currentTimeMillis(),
            channels = emptyMap()
        )
    }

    /**
     * Create a derived flow that emits only the value of a single channel
     * whenever it changes.
     *
     * This is a convenience for UI code that only needs to observe one or
     * two channels (e.g. RPM for a shift light).
     *
     * @param channelName The output channel name from the INI.
     * @return A flow of `(timestamp, value)` pairs.
     */
    fun observeChannel(channelName: String): Flow<Pair<Long, Double>> =
        dataChanges
            .mapNotNull { snapshot ->
                val v = snapshot.channels[channelName]
                if (v != null) snapshot.timestamp to v else null
            }
}

// ============================================================================
// Extension: bridge RealtimeStream → RealtimeUpdate (EcuInterface compat)
// ============================================================================

/**
 * Convert a [RealtimeData] snapshot into a [com.ztune.libretune.core.ecu.RealtimeUpdate]
 * for compatibility with the existing [com.ztune.libretune.core.ecu.EcuInterface] streaming
 * contract.
 *
 * The `rawData` field is left empty because the `RealtimeStream` does not
 * retain raw bytes across updates.  If the caller needs raw data, it should
 * collect from [RealtimeStream.rawDataFlow] separately.
 */
fun RealtimeData.toRealtimeUpdate(): com.ztune.libretune.core.ecu.RealtimeUpdate =
    com.ztune.libretune.core.ecu.RealtimeUpdate(
        timestamp = timestamp,
        rawData = byteArrayOf(), // raw not retained in decoded snapshot
        channelValues = channels
    )
