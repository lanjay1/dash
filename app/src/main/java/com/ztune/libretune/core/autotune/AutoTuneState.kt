package com.ztune.libretune.core.autotune

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Snapshot of the AutoTune UI / controller state, exposed as a [StateFlow].
 *
 * Every field is a plain `val` — **no** `const val`, because data class
 * properties are instance-level and `const val` is only legal for top-level
 * compile-time constants in Kotlin.
 */
data class AutoTuneState(
    /** Whether an autotune session is currently active (accepting samples). */
    val isRunning: Boolean = false,
    /** Total number of samples fed since the last [reset]. */
    val sampleCount: Long = 0,
    /** Number of table cells that have accumulated enough samples to be adjustable. */
    val activeCells: Int = 0,
    /** Total number of cells in the VE table (rows × cols). */
    val totalCells: Int = 0,
    /** Number of cells currently locked. */
    val lockedCells: Int = 0,
    /** Result of the most recent [AutoTuneController.computeResult], or null. */
    val lastResult: AutoTuneResult? = null
)

/**
 * Stateful controller that bridges the [AutoTuneEngine] to the UI layer.
 *
 * The controller owns a single [AutoTuneEngine] instance and exposes
 * reactive state via a [StateFlow][state]. Typical usage:
 *
 * ```kotlin
 * val controller = AutoTuneController()
 * controller.configure(AutoTuneConfig(
 *     veTableName = "VEtable1",
 *     targetAfrTableName = "afrTable1",
 *     afrChannelName = "afr",
 *     rpmChannelName = "rpm",
 *     loadChannelName = "map"
 * ))
 * // … later, on each realtime tick:
 * controller.feedSample(mapOf("rpm" to 3200.0, "map" to 85.0, "afr" to 13.8, "afrTarget" to 14.0))
 * // … when done:
 * val result = controller.computeResult(veTable, rpmBins, loadBins)
 * ```
 */
class AutoTuneController {

    private val _state = MutableStateFlow(AutoTuneState())

    /**
     * Reactive state for UI observation.
     *
     * Collect this in a `LaunchedEffect` or `lifecycleScope` to drive the
     * autotune overlay / heat-map visualisation.
     */
    val state: StateFlow<AutoTuneState> = _state.asStateFlow()

    /** The underlying engine, created by [configure]. */
    private var engine: AutoTuneEngine? = null

    /** Cached config so we can re-create the engine on [reset]. */
    private var cachedConfig: AutoTuneConfig? = null

    // ------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------

    /**
     * Configure the autotune engine and activate the session.
     *
     * Creates a fresh [AutoTuneEngine] with the supplied [config].
     * Any previously accumulated data is discarded.
     *
     * @param config The autotune configuration.
     */
    fun configure(config: AutoTuneConfig) {
        cachedConfig = config
        engine = AutoTuneEngine(config)
        _state.update { it.copy(isRunning = true, sampleCount = 0, lastResult = null) }
        refreshLockedCellCount()
    }

    /**
     * Feed a single realtime data sample into the autotune engine.
     *
     * The [channelValues] map must contain at minimum the channels named
     * in the current [AutoTuneConfig] (`afrChannelName`, `rpmChannelName`,
     * `loadChannelName`). It should also contain a target AFR channel;
     * the controller looks for `afrTarget` by convention, but you can also
     * include the target AFR table name from the config.
     *
     * If any required channel is missing the sample is silently dropped.
     * If the engine has not been configured yet, this is a no-op.
     *
     * @param channelValues Map of channel name → current value.
     */
    fun feedSample(channelValues: Map<String, Double>) {
        val eng = engine ?: return
        val cfg = cachedConfig ?: return

        val rpm = channelValues[cfg.rpmChannelName] ?: return
        val load = channelValues[cfg.loadChannelName] ?: return
        val measuredAfr = channelValues[cfg.afrChannelName] ?: return

        // Look up target AFR. Try several conventional names.
        val targetAfr = channelValues["afrTarget"]
            ?: channelValues["targetAfr"]
            ?: channelValues["target_afr"]
            ?: channelValues["lambdaTarget"]
            ?: channelValues[cfg.targetAfrTableName.ifEmpty { "afrTarget" }]
            ?: return

        eng.addSample(rpm, load, measuredAfr, targetAfr)

        _state.update { prev ->
            val newCount = eng.sampleCount().toLong()
            if (newCount != prev.sampleCount) {
                prev.copy(sampleCount = newCount)
            } else {
                prev
            }
        }
    }

    /**
     * Stop the autotune session and compute final adjustments.
     *
     * After this call the controller transitions to `isRunning = false`.
     * Further calls to [feedSample] will be no-ops until [configure] or
     * [reset] is called again.
     *
     * @param currentTableValues Current VE table values (`rows × cols`).
     * @param rpmBins RPM axis breakpoints (ascending, length = columns).
     * @param loadBins Load axis breakpoints (ascending, length = rows).
     * @return The [AutoTuneResult], or `null` if the engine was never configured
     *   or the table is empty.
     */
    fun computeResult(
        currentTableValues: List<List<Double>>,
        rpmBins: List<Double>,
        loadBins: List<Double>
    ): AutoTuneResult? {
        val eng = engine ?: return null
        val rows = currentTableValues.size
        val cols = if (rows > 0) currentTableValues[0].size else 0
        if (rows == 0 || cols == 0) return null

        val result = eng.computeAdjustments(currentTableValues, rpmBins, loadBins)

        _state.update {
            it.copy(
                isRunning = false,
                activeCells = countActiveCells(result.heatMap),
                totalCells = rows * cols,
                lastResult = result
            )
        }

        return result
    }

    /**
     * Reset all accumulated data and return to the idle state.
     *
     * If a [AutoTuneConfig] was previously supplied via [configure],
     * the engine is re-created so the session can be started again
     * with a fresh call to [configure].
     */
    fun reset() {
        engine?.clearData()
        cachedConfig?.let { engine = AutoTuneEngine(it) }
        _state.update {
            AutoTuneState()
        }
    }

    /**
     * Lock or unlock a specific VE table cell.
     *
     * Delegates to the underlying [AutoTuneEngine.setCellLocked] and
     * updates the [lockedCells] count in the flow.
     *
     * @param row Row index.
     * @param col Column index.
     * @param locked `true` to lock, `false` to unlock.
     */
    fun setCellLocked(row: Int, col: Int, locked: Boolean) {
        engine?.setCellLocked(row, col, locked)
        refreshLockedCellCount()
    }

    /**
     * Check whether a cell is locked.
     *
     * @return `true` if the cell is locked, `false` otherwise. Returns `false`
     *   if the engine has not been configured.
     */
    fun isCellLocked(row: Int, col: Int): Boolean =
        engine?.isCellLocked(row, col) ?: false

    /**
     * Get the current heat map from the engine.
     *
     * @return Heat map from the most recent computation, or an empty map.
     */
    fun getHeatMap(): Map<Pair<Int, Int>, AutoTuneCellInfo> =
        engine?.getHeatMap() ?: emptyMap()

    // ------------------------------------------------------------------
    // Internal helpers
    // ------------------------------------------------------------------

    /** Count cells with sampleCount >= minCellSamples (i.e. adjustable). */
    private fun countActiveCells(heatMap: List<List<AutoTuneCellInfo>>): Int {
        val minSamples = cachedConfig?.minCellSamples ?: 5
        return heatMap.sumOf { row ->
            row.count { it.sampleCount >= minSamples && !it.isLocked }
        }
    }

    /** Recount locked cells and push the count into the state flow. */
    private fun refreshLockedCellCount() {
        val eng = engine ?: return
        val heatMap = eng.getHeatMap()
        val count = if (heatMap.isNotEmpty()) {
            heatMap.values.count { it.isLocked }
        } else {
            cachedConfig?.lockedCells?.size ?: 0
        }
        _state.update { it.copy(lockedCells = count) }
    }
}
