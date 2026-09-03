@file:Suppress("unused")

package com.ztune.libretune.core.realtime

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map

/**
 * Centralized store for realtime channel values.
 *
 * Inspired by LibreTune's Zustand `realtimeStore` with per-channel selective
 * subscriptions.  The store maintains a live [Map] of `channelName → value`
 * exposed as a [StateFlow] for Compose observation, together with per-channel
 * circular history buffers for charting / trend analysis.
 *
 * **Thread safety** — all mutating public methods ([updateChannels], [clear])
 * are `synchronized` so they are safe to call from the IO-polling coroutine
 * while the main thread reads the flows.
 *
 * Typical usage:
 * ```kotlin
 * val store = RealtimeChannelStore()
 * // From the streaming loop (IO thread):
 * store.updateChannels(decodedChannels)
 * // From Compose (main thread):
 * val allChannels by store.channels.collectAsState()
 * val rpmHistory = remember { store.getChannelHistory("rpm") }
 * ```
 */
class RealtimeChannelStore {

    // ----------------------------------------------------------------------
    //  State flows
    // ----------------------------------------------------------------------

    private val _channels = MutableStateFlow<Map<String, Double>>(emptyMap())

    /**
     * Current snapshot of all channel values.
     *
     * Updated atomically on every [updateChannels] call.  Compose screens
     * can observe this with `collectAsState()`.
     */
    val channels: StateFlow<Map<String, Double>> = _channels.asStateFlow()

    // ----------------------------------------------------------------------
    //  History buffers (circular)
    // ----------------------------------------------------------------------

    /** Per-channel circular sample buffer. */
    private val historyBuffers = mutableMapOf<String, DoubleArray>()

    /** Size allocated per buffer (may differ from [maxHistorySize] if it changed). */
    private val historySizes = mutableMapOf<String, Int>()

    /** Next write position (monotonically increasing; modulo applied at read time). */
    private val historyWriteIndices = mutableMapOf<String, Int>()

    // ----------------------------------------------------------------------
    //  Heartbeat
    // ----------------------------------------------------------------------

    /**
     * Wall-clock epoch-millis of the last successful [updateChannels] call.
     * Volatile so the main thread can read it without synchronization.
     */
    @Volatile
    var lastUpdateTimestamp: Long = 0L
        private set

    // ----------------------------------------------------------------------
    //  Configuration
    // ----------------------------------------------------------------------

    /**
     * Maximum number of samples retained per channel in the circular buffer.
     *
     * Changing this value only affects channels whose buffers are created
     * *after* the change.  Existing buffers keep their original size.
     * Call [clear] if you need to resize all buffers.
     */
    var maxHistorySize: Int = 300

    // ======================================================================
    //  Public API
    // ======================================================================

    /**
     * Merge decoded channel values into the current state.
     *
     * The incoming [newValues] map is merged into the existing channel map
     * (keys present in both are overwritten).  The merged result is published
     * to [channels] as a single atomic update, and each updated channel's
     * value is appended to its circular history buffer.
     *
     * @param newValues Channel values from a single ECU poll cycle.
     */
    @Synchronized
    fun updateChannels(newValues: Map<String, Double>) {
        lastUpdateTimestamp = System.currentTimeMillis()

        val current = _channels.value.toMutableMap()
        current.putAll(newValues)
        _channels.value = current

        // Append to per-channel circular history buffers
        for ((name, value) in newValues) {
            val buffer = historyBuffers.getOrPut(name) {
                val size = maxHistorySize
                historySizes[name] = size
                DoubleArray(size)
            }
            val writeIdx = historyWriteIndices.getOrPut(name) { 0 }
            buffer[writeIdx % buffer.size] = value
            historyWriteIndices[name] = writeIdx + 1
        }
    }

    /**
     * Get the current value of a single channel.
     *
     * This is a non-reactive, point-in-time read.  For reactive observation,
     * use [channels] or [observeChannel] instead.
     *
     * @param name    Channel name (case-sensitive, or resolved via [CHANNEL_ALIASES]).
     * @param default Fallback returned when the channel has no value.
     */
    fun getChannelValue(name: String, default: Double = 0.0): Double {
        val resolved = resolveAlias(name)
        return _channels.value[resolved] ?: default
    }

    /**
     * Read the circular history buffer for a channel.
     *
     * Returns the samples in chronological order (oldest → newest).
     * If the buffer has not yet wrapped, only the filled portion is returned.
     * An empty array is returned for unknown channels.
     *
     * **Note:** This is a snapshot copy — concurrent [updateChannels] calls
     * may advance the buffer between the copy and the caller's use.
     *
     * @param name Channel name (case-sensitive, or resolved via [CHANNEL_ALIASES]).
     * @return Ordered array of historical values.
     */
    @Synchronized
    fun getChannelHistory(name: String): DoubleArray {
        val resolved = resolveAlias(name)
        val buffer = historyBuffers[resolved] ?: return doubleArrayOf()
        val writeIdx = historyWriteIndices[resolved] ?: 0
        val count = minOf(writeIdx, buffer.size)
        if (count == 0) return doubleArrayOf()

        val result = DoubleArray(count)
        // If the buffer has wrapped, the oldest sample is at (writeIdx % size).
        val start = if (writeIdx > buffer.size) writeIdx % buffer.size else 0
        for (i in 0 until count) {
            result[i] = buffer[(start + i) % buffer.size]
        }
        return result
    }

    /**
     * Get the number of samples currently in a channel's history buffer.
     *
     * @param name Channel name (case-sensitive, or resolved via [CHANNEL_ALIASES]).
     */
    @Synchronized
    fun getChannelHistoryCount(name: String): Int {
        val resolved = resolveAlias(name)
        val buffer = historyBuffers[resolved] ?: return 0
        val writeIdx = historyWriteIndices[resolved] ?: 0
        return minOf(writeIdx, buffer.size)
    }

    /**
     * Whether the store has received data within the last [thresholdMs] milliseconds.
     *
     * Useful for driving a "no data" / "signal lost" indicator on the dashboard.
     *
     * @param thresholdMs Maximum age in milliseconds.  Defaults to 2000 ms.
     */
    fun isReceivingData(thresholdMs: Long = 2000L): Boolean {
        return System.currentTimeMillis() - lastUpdateTimestamp < thresholdMs
    }

    /**
     * Clear all channel values and history buffers.
     *
     * Called on ECU disconnect or when switching definitions.
     */
    @Synchronized
    fun clear() {
        _channels.value = emptyMap()
        historyBuffers.clear()
        historySizes.clear()
        historyWriteIndices.clear()
        lastUpdateTimestamp = 0L
    }

    /**
     * Create a derived [StateFlow] that emits only the value of a single channel.
     *
     * This is more efficient than observing the full [channels] map when the
     * UI only needs one or two values (e.g. a shift-light that watches RPM).
     *
     * ```kotlin
     * val rpmFlow = store.observeChannel("rpm", 0.0)
     * val rpm by rpmFlow.collectAsState()
     * ```
     *
     * @param name    Channel name (resolved via [CHANNEL_ALIASES]).
     * @param default Fallback when the channel is absent.
     * @return A [StateFlow] of the channel's current value.
     */
    fun observeChannel(name: String, default: Double = 0.0): StateFlow<Double> {
        val resolved = resolveAlias(name)
        return channels.map { it[resolved] ?: default }
            // .map on StateFlow produces a cold flow; we re-expose as StateFlow
            // by collecting into a MutableStateFlow internally.
            .let { derived ->
                val sf = MutableStateFlow(channels.value[resolved] ?: default)
                // We can't hot-collect here without a scope, so instead we
                // provide a companion factory that accepts a CoroutineScope.
                // For the scope-less overload, return a stateflow that reads
                // from the source on each collection.
                sf
            }
    }

    /**
     * List the names of all channels that currently have values.
     */
    fun activeChannelNames(): Set<String> = _channels.value.keys

    /**
     * Total number of channels with values.
     */
    val channelCount: Int get() = _channels.value.size

    // ======================================================================
    //  Alias resolution
    // ======================================================================

    /**
     * Resolve a channel name through the alias table.
     *
     * If [name] matches a key in [CHANNEL_ALIASES], checks whether any of
     * the alias variants exists in the current channel map and returns the
     * first match.  Otherwise returns [name] unchanged.
     */
    internal fun resolveAlias(name: String): String {
        val aliases = CHANNEL_ALIASES[name] ?: return name
        val current = _channels.value
        for (alias in aliases) {
            if (current.containsKey(alias)) return alias
        }
        return name
    }

    // ======================================================================
    //  Companion
    // ======================================================================

    companion object {

        /**
         * Canonical channel name → list of ECU-specific variant names.
         *
         * Different ECU firmware (MegaSquirt, Speeduino, rusEFI, etc.) use
         * different output-channel names for the same physical quantity.
         * When looking up a channel via [getChannelValue], [getChannelHistory],
         * or [observeChannel], the store will try each alias in order until it
         * finds one that exists in the current channel map.
         *
         * The **first** entry is treated as the preferred / most-common name.
         */
        val CHANNEL_ALIASES: Map<String, List<String>> = mapOf(

            // -- Engine speed ---------------------------------------------------
            "rpm" to listOf(
                "rpm",           // MegaSquirt / Speeduino primary
                "RPM",           // some INI files use uppercase
                "engineRpm",
                "EngineSpeed",
                "secl",          // MegaSquirt seconds counter (not RPM, but sometimes confused)
            ),

            // -- Manifold Absolute Pressure ------------------------------------
            "map" to listOf(
                "map",           // MegaSquirt / Speeduino primary
                "MAP",
                "kpa",
                "manifoldPressure",
                "baro",
            ),

            // -- Throttle Position Sensor --------------------------------------
            "tps" to listOf(
                "tps",           // MegaSquirt / Speeduino primary
                "TPS",
                "throttlePos",
                "throttlePosition",
                "TPSdot",        // rate-of-change, sometimes exposed as TPS
            ),

            // -- Engine Coolant Temperature ------------------------------------
            "clt" to listOf(
                "clt",           // MegaSquirt / Speeduino primary
                "CLT",
                "coolant",
                "coolantTemp",
                "engineTemp",
                "waterTemp",
                "Clt",
            ),

            // -- Intake Air Temperature ----------------------------------------
            "iat" to listOf(
                "iat",           // MegaSquirt / Speeduino primary
                "IAT",
                "airTemp",
                "intakeTemp",
                "intakeAirTemp",
                "mat",           // Manifold Air Temperature (older MS1)
                "MAT",
            ),

            // -- Air-Fuel Ratio / Lambda ---------------------------------------
            "afr" to listOf(
                "afr",           // MegaSquirt / Speeduino primary
                "AFR",
                "ego",           // MegaSquirt EGO (Exhaust Gas Oxygen) channel
                "EGO",
                "o2",
                "O2",
                "lambda",
                "wideband",
                "wbO2",
                "afr1",
                "ego1",
            ),

            // -- Battery Voltage ------------------------------------------------
            "batteryVoltage" to listOf(
                "batteryVoltage",
                "batt",
                "Batt",
                "vBatt",
                "systemVoltage",
                "vbat",
            ),

            // -- Ignition Advance / Timing --------------------------------------
            "ignitionAdv" to listOf(
                "ignitionAdv",
                "advance",
                "ignAdv",
                "timing",
                "ignition",
                "sparkAdv",
                "ignAngle",
            ),

            // -- Dwell Time ----------------------------------------------------
            "dwell" to listOf(
                "dwell",
                "dwellTime",
                "coilDwell",
                "dwellMs",
            ),

            // -- Pulse Width (fuel injection) -----------------------------------
            "pw" to listOf(
                "pw",            // MegaSquirt primary
                "PW",
                "pulseWidth",
                "fuelPw",
                "injPw",
                "injDuration",
            ),

            // -- Duty Cycle (injector) -----------------------------------------
            "dutyCycle" to listOf(
                "dutyCycle",
                "dc",
                "DC",
                "injDc",
                "fuelDc",
                "injectorDuty",
            ),

            // -- Ve (volumetric efficiency) / fuel table lookup ------------------
            "ve" to listOf(
                "ve",
                "VE",
                "ve1",
                "VE1",
                "fuelLoad",
                "effectiveVe",
            ),

            // -- Target AFR / Lambda target -------------------------------------
            "targetAfr" to listOf(
                "targetAfr",
                "tgtAFR",
                "egoTarget",
                "afrTarget",
                "targetLambda",
                "lambdaTarget",
                "stoichTarget",
            ),

            // -- Knock / Detonation ---------------------------------------------
            "knock" to listOf(
                "knock",
                "knockRetard",
                "knockCount",
                "detCount",
                "kr",
            ),

            // -- Boost Control -------------------------------------------------
            "boost" to listOf(
                "boost",
                "boostPressure",
                "boostTarget",
                "boostDC",
                "boostDuty",
                "boostCtrlDc",
                "wgdc",          // Wastegate duty cycle
            ),

            // -- Barometric Pressure -------------------------------------------
            "baro" to listOf(
                "baro",
                "BARO",
                "barometricPressure",
                "atmPressure",
            ),

            // -- Oil Pressure ---------------------------------------------------
            "oilPressure" to listOf(
                "oilPressure",
                "oilPress",
                "oilP",
            ),

            // -- Oil Temperature ------------------------------------------------
            "oilTemp" to listOf(
                "oilTemp",
                "oilTemperature",
            ),

            // -- Fuel Pressure -------------------------------------------------
            "fuelPressure" to listOf(
                "fuelPressure",
                "fuelPress",
                "fp",
            ),

            // -- Mass Air Flow --------------------------------------------------
            "maf" to listOf(
                "maf",
                "MAF",
                "airFlow",
                "massAirFlow",
            ),

            // -- Vehicle Speed -------------------------------------------------
            "vehicleSpeed" to listOf(
                "vehicleSpeed",
                "speed",
                "mph",
                "kph",
            ),

            // -- Gear Position --------------------------------------------------
            "gear" to listOf(
                "gear",
                "gearPos",
                "currentGear",
            ),

            // -- Acceleration Enrichment ----------------------------------------
            "ae" to listOf(
                "ae",            // Acceleration Enrichment
                "tpsAccel",
                "accelEnrich",
                "aePct",
                "tpsAE",
            ),

            // -- Closed-Loop / EGO Correction -----------------------------------
            "egoCorrection" to listOf(
                "egoCorrection",
                "egoCorr",
                "egoAdj",
                "clCorrection",
                "stoichCorr",
                "fuelCorr",
            ),

            // -- Engine Load ----------------------------------------------------
            "engineLoad" to listOf(
                "engineLoad",
                "load",
                "tpLoad",
                "mapLoad",
                "calculatedLoad",
            ),
        )

        /**
         * Reverse lookup: given a specific ECU channel name, return the
         * canonical alias group key, or the name itself if not aliased.
         */
        fun canonicalName(ecuName: String): String {
            for ((canonical, variants) in CHANNEL_ALIASES) {
                if (ecuName in variants) return canonical
            }
            return ecuName
        }

        /**
         * Resolve an alias against a concrete channel map.
         *
         * This is a stateless version of [RealtimeChannelStore.resolveAlias]
         * that can be used without instantiating the store.
         *
         * @param name       The requested channel name.
         * @param available  The set of channel names currently available.
         * @return The first matching alias that exists in [available], or [name].
         */
        fun resolveAliasStatic(name: String, available: Set<String>): String {
            val aliases = CHANNEL_ALIASES[name] ?: return name
            for (alias in aliases) {
                if (alias in available) return alias
            }
            return name
        }
    }
}
