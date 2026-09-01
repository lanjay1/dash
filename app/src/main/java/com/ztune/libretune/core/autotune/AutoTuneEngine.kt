package com.ztune.libretune.core.autotune

import kotlin.math.abs

/**
 * Configuration for the AutoTune engine.
 */
data class AutoTuneConfig(
    val veTableName: String = "",
    val targetAfrTableName: String = "",
    val lambdaTargetTables: List<String> = emptyList(),
    /** Maximum fractional change per autotune pass (e.g. 0.1 = 10%). */
    val authority: Float = 0.1f,
    /** Minimum number of samples in a cell before it will be adjusted. */
    val minCellSamples: Int = 5,
    /** Number of smoothing passes applied to the adjustment grid. */
    val smoothingPasses: Int = 3,
    /** Cells locked out of adjustment, stored as (row, col) pairs. */
    val lockedCells: Set<Pair<Int, Int>> = emptySet(),
    /** Channel name for the measured AFR/Lambda input. */
    val afrChannelName: String = "afr",
    /** Channel name for the RPM input. */
    val rpmChannelName: String = "rpm",
    /** Channel name for the load (MAP/TPS) input. */
    val loadChannelName: String = "map"
)

/**
 * Result of a single autotune computation pass.
 *
 * @property adjustments 2D array of percentage adjustments matching the table dimensions.
 *   Each value represents the suggested percent change to the VE cell
 *   (e.g. +5.0 means "increase this cell by 5%").
 * @property heatMap 2D array of per-cell diagnostic info, matching table dimensions.
 * @property totalAdjustments Number of cells that received a non-zero adjustment.
 * @property lockedCellCount Number of cells that were skipped due to being locked.
 */
data class AutoTuneResult(
    val adjustments: List<List<Double>>,
    val heatMap: List<List<AutoTuneCellInfo>>,
    val totalAdjustments: Int,
    val lockedCellCount: Int
)

/**
 * Diagnostic information about a single cell's autotune state.
 *
 * @property row Row index in the VE table.
 * @property col Column index in the VE table.
 * @property sampleCount Number of logged data points that fell into this cell.
 * @property averageError Average (measured AFR − target AFR) across all samples in this cell.
 *   Positive → engine running richer than target; negative → leaner.
 * @property totalAdjustment Cumulative percentage adjustment applied to this cell.
 * @property isLocked Whether this cell is locked and excluded from adjustment.
 */
data class AutoTuneCellInfo(
    val row: Int,
    val col: Int,
    val sampleCount: Int,
    val averageError: Double,
    val totalAdjustment: Double,
    val isLocked: Boolean
)

/**
 * Core AutoTune engine.
 *
 * Adjusts VE table cells based on logged AFR vs target AFR data.
 *
 * The engine works in two phases:
 *
 * 1. **Data collection** — [addSample] accumulates raw (rpm, load, measuredAfr, targetAfr)
 *    tuples. Samples are stored un-binned so the same dataset can be re-binned against
 *    different table axis configurations.
 *
 * 2. **Computation** — [computeAdjustments] bins every accumulated sample into the
 *    VE table grid defined by `rpmBins × loadBins`, computes per-cell average error,
 *    derives a percentage VE correction, clamps by [AutoTuneConfig.authority], and
 *    optionally runs smoothing passes.
 *
 * ## Algorithm per cell
 * 1. `avgError = mean(measuredAfr − targetAfr)` for all samples in the cell
 * 2. Skip the cell if sample count < [AutoTuneConfig.minCellSamples]
 * 3. `avgTarget = mean(targetAfr)` for all samples in the cell
 * 4. `adjustment = −avgError / avgTarget × 100` (percent correction to VE)
 * 5. Clamp to `[−authority × 100, +authority × 100]`
 * 6. Apply [AutoTuneConfig.smoothingPasses] passes of 3×3 weighted-average smoothing
 *
 * Locked cells (see [AutoTuneConfig.lockedCells] / [setCellLocked]) always receive
 * a 0 % adjustment and act as anchors during smoothing.
 */
class AutoTuneEngine(private val config: AutoTuneConfig) {

    // ------------------------------------------------------------------
    // Internal state
    // ------------------------------------------------------------------

    /** Raw logged samples, stored un-binned. */
    private val samples = mutableListOf<RawSample>()

    /** Runtime set of locked cells (starts from config, mutable via [setCellLocked]). */
    private val lockedCells = config.lockedCells.toMutableSet()

    /** Cached heat map from the most recent [computeAdjustments] call. */
    private var cachedHeatMap: Map<Pair<Int, Int>, AutoTuneCellInfo> = emptyMap()

    // ------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------

    /**
     * Accumulate a single data sample.
     *
     * The sample is stored as-is and will be binned into table cells the next
     * time [computeAdjustments] is called.
     *
     * @param rpm Engine speed in RPM.
     * @param load Load value (kPa MAP, %TPS, etc.).
     * @param measuredAfr Measured air-fuel ratio from the wideband.
     * @param targetAfr Target AFR for this operating point.
     */
    fun addSample(rpm: Double, load: Double, measuredAfr: Double, targetAfr: Double) {
        samples.add(RawSample(rpm, load, measuredAfr, targetAfr))
    }

    /**
     * Run the autotune algorithm and compute adjustments for every cell.
     *
     * @param currentTableValues Current VE table values as a 2D list (`rows × cols`).
     *   Used for dimension checking; the percentage adjustment is computed
     *   independently of the current value.
     * @param rpmBins RPM axis breakpoints in ascending order. Length must equal
     *   `currentTableValues[0].size` (number of columns).
     * @param loadBins Load axis breakpoints in ascending order. Length must equal
     *   `currentTableValues.size` (number of rows).
     * @return The computed [AutoTuneResult], or a zero-adjustment result when
     *   no valid data is available.
     */
    fun computeAdjustments(
        currentTableValues: List<List<Double>>,
        rpmBins: List<Double>,
        loadBins: List<Double>
    ): AutoTuneResult {
        val rows = currentTableValues.size
        val cols = if (rows > 0) currentTableValues[0].size else 0

        // Early return for degenerate inputs.
        if (rows == 0 || cols == 0 || samples.isEmpty()) {
            return zeroResult(currentTableValues)
        }

        // --- Step 1: Bin samples into cells and accumulate statistics ---
        val acc = Array(rows) { Array(cols) { CellAccumulator() } }

        for (sample in samples) {
            val row = binIndex(sample.load, loadBins)
            val col = binIndex(sample.rpm, rpmBins)
            if (row in 0 until rows && col in 0 until cols) {
                val a = acc[row][col]
                val error = sample.measuredAfr - sample.targetAfr
                a.sumError += error
                a.sumSquaredError += error * error
                a.sumTargetAfr += sample.targetAfr
                a.count++
            }
        }

        // --- Step 2: Compute raw percentage adjustments per cell ---
        val authorityPct = config.authority.toDouble() * 100.0
        val rawAdjustments = MutableList(rows) { r ->
            MutableList(cols) { c ->
                val a = acc[r][c]
                val locked = (r to c) in lockedCells

                if (a.count < config.minCellSamples || locked) {
                    0.0
                } else {
                    val avgError = a.sumError / a.count
                    val avgTarget = a.sumTargetAfr / a.count
                    // If measured AFR is higher than target (lean), we need MORE fuel
                    // → positive VE adjustment.  Hence: adjustment = −error / target × 100.
                    val adjustment = -avgError / avgTarget * 100.0
                    adjustment.coerceIn(-authorityPct, authorityPct)
                }
            }
        }

        // --- Step 3: Smoothing passes ---
        if (config.smoothingPasses > 0) {
            smooth(rawAdjustments, config.smoothingPasses)
        }

        // --- Step 4: Build heat map & aggregate stats ---
        val heatMap = mutableListOf<MutableList<AutoTuneCellInfo>>()
        var totalAdjustments = 0
        var lockedCellCount = 0

        for (r in 0 until rows) {
            val rowList = mutableListOf<AutoTuneCellInfo>()
            for (c in 0 until cols) {
                val a = acc[r][c]
                val locked = (r to c) in lockedCells
                val avgError = if (a.count > 0) a.sumError / a.count else 0.0
                val adj = rawAdjustments[r][c]

                if (locked) lockedCellCount++
                if (adj != 0.0) totalAdjustments++

                rowList.add(
                    AutoTuneCellInfo(
                        row = r,
                        col = c,
                        sampleCount = a.count,
                        averageError = avgError,
                        totalAdjustment = adj,
                        isLocked = locked
                    )
                )
            }
            heatMap.add(rowList)
        }

        // Cache for [getHeatMap].
        cachedHeatMap = buildMap {
            for (r in 0 until rows) {
                for (c in 0 until cols) {
                    put(r to c, heatMap[r][c])
                }
            }
        }

        return AutoTuneResult(
            adjustments = rawAdjustments.map { it.toList() },
            heatMap = heatMap.map { it.toList() },
            totalAdjustments = totalAdjustments,
            lockedCellCount = lockedCellCount
        )
    }

    /**
     * Clear all accumulated sample data.
     *
     * Does **not** clear locked cells; use [setCellLocked] individually
     * or create a new engine instance for that.
     */
    fun clearData() {
        samples.clear()
        cachedHeatMap = emptyMap()
    }

    /**
     * Return the heat map from the most recent [computeAdjustments] call.
     *
     * If no computation has been performed yet, returns an empty map.
     */
    fun getHeatMap(): Map<Pair<Int, Int>, AutoTuneCellInfo> = cachedHeatMap

    /**
     * Lock or unlock a single cell.
     *
     * Locked cells are excluded from autotune adjustments and act as anchors
     * during smoothing (they always contribute 0.0 to their neighbours'
     * weighted average, causing adjustments to taper toward locked regions).
     *
     * @param row Row index of the cell.
     * @param col Column index of the cell.
     * @param locked `true` to lock, `false` to unlock.
     */
    fun setCellLocked(row: Int, col: Int, locked: Boolean) {
        val key = row to col
        if (locked) lockedCells.add(key) else lockedCells.remove(key)
    }

    /**
     * Check whether a specific cell is currently locked.
     */
    fun isCellLocked(row: Int, col: Int): Boolean = (row to col) in lockedCells

    /** Return the current number of accumulated (un-binned) samples. */
    fun sampleCount(): Int = samples.size

    // ------------------------------------------------------------------
    // Smoothing
    // ------------------------------------------------------------------

    /**
     * Apply [passes] iterations of 3×3 weighted-average smoothing **in-place**.
     *
     * The kernel is a discrete Gaussian approximation:
     * ```
     *  1  2  1
     *  2  4  2      (total weight = 16)
     *  1  2  1
     * ```
     *
     * Edge cells use whatever neighbours are available (reduced kernel).
     * Locked cells are pinned to 0.0 after every pass so they act as anchors.
     */
    private fun smooth(
        adjustments: MutableList<MutableList<Double>>,
        passes: Int
    ) {
        val rows = adjustments.size
        val cols = if (rows > 0) adjustments[0].size else 0
        if (rows == 0 || cols == 0) return

        val lockedSet = lockedCells

        // Pre-allocated kernel: (deltaRow, deltaCol, weight)
        val kernel = arrayOf(
            intArrayOf(-1, -1), intArrayOf(0, -1), intArrayOf(1, -1),
            intArrayOf(-1,  0), intArrayOf(0,  0), intArrayOf(1,  0),
            intArrayOf(-1,  1), intArrayOf(0,  1), intArrayOf(1,  1)
        )
        val weights = doubleArrayOf(1.0, 2.0, 1.0, 2.0, 4.0, 2.0, 1.0, 2.0, 1.0)

        repeat(passes) {
            // Snapshot current state so each pass reads from the previous.
            val src = Array(rows) { r -> DoubleArray(cols) { adjustments[r][it] } }

            for (r in 0 until rows) {
                for (c in 0 until cols) {
                    if ((r to c) in lockedSet) {
                        adjustments[r][c] = 0.0
                        continue
                    }

                    var wSum = 0.0
                    var wTotal = 0.0
                    for (k in kernel.indices) {
                        val nr = r + kernel[k][0]
                        val nc = c + kernel[k][1]
                        if (nr in 0 until rows && nc in 0 until cols) {
                            wSum += src[nr][nc] * weights[k]
                            wTotal += weights[k]
                        }
                    }
                    adjustments[r][c] = if (wTotal > 0.0) wSum / wTotal else 0.0
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Internal helpers
    // ------------------------------------------------------------------

    /**
     * Find the nearest-neighbour bin index for [value] in a sorted [bins] list.
     *
     * Uses binary search to locate the insertion point, then picks the closer
     * of the two adjacent bins. Returns -1 if [bins] is empty.
     */
    private fun binIndex(value: Double, bins: List<Double>): Int {
        val n = bins.size
        if (n == 0) return -1
        if (n == 1) return 0

        // Binary search for the first element >= value.
        var lo = 0
        var hi = n - 1
        while (lo < hi) {
            val mid = lo + (hi - lo) / 2
            if (bins[mid] < value) lo = mid + 1 else hi = mid
        }

        // lo is the index of the first bin >= value (or the last bin if value
        // exceeds all).  Pick the closer of lo and lo-1.
        val prev = if (lo > 0) lo - 1 else lo
        return if (abs(value - bins[lo]) <= abs(value - bins[prev])) lo else prev
    }

    /** Produce a zero-adjustment result with an empty heat map. */
    private fun zeroResult(table: List<List<Double>>): AutoTuneResult {
        val heatMap = table.mapIndexed { r, row ->
            row.mapIndexed { c, _ ->
                AutoTuneCellInfo(
                    row = r, col = c,
                    sampleCount = 0,
                    averageError = 0.0,
                    totalAdjustment = 0.0,
                    isLocked = (r to c) in lockedCells
                )
            }
        }
        return AutoTuneResult(
            adjustments = table.map { row -> row.map { 0.0 } },
            heatMap = heatMap,
            totalAdjustments = 0,
            lockedCellCount = 0
        )
    }

    // ------------------------------------------------------------------
    // Internal data types
    // ------------------------------------------------------------------

    /** A single logged data point, stored before binning. */
    private data class RawSample(
        val rpm: Double,
        val load: Double,
        val measuredAfr: Double,
        val targetAfr: Double
    )

    /**
     * Running statistics for all samples that fall into a single table cell.
     */
    private class CellAccumulator {
        var sumError: Double = 0.0
        var sumSquaredError: Double = 0.0
        var sumTargetAfr: Double = 0.0
        var count: Int = 0
    }
}
