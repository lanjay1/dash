package com.ztune.libretune.core.tune

/**
 * Pure-function table cell operations (like LibreTune's `table_ops` backend).
 *
 * All functions operate on [MutableList]<[MutableList]<[Double]>> (mutable 2D grid)
 * and mutate in place, returning the same reference for chaining.
 *
 * These are the same algorithms used by TunerStudio / LibreTune's Rust backend
 * for the standard table manipulation toolbar actions.
 */
object TableOperations {

    // ======================================================================
    // Set cells equal (average)
    // ======================================================================

    /**
     * Set every cell in [cells] to the arithmetic mean of their current values.
     *
     * If [values] is non-empty, each selected cell receives the corresponding
     * value from the [values] matrix (row-major, same order as [cells]); otherwise
     * the average of the selected cells' existing values is used.
     *
     * @param cells  List of (row, col) coordinates to modify.
     * @param values Optional flat list of replacement values, one per cell.
     *              If empty, the cells are set to their own average.
     * @param table  Mutable 2D table to modify in place.
     */
    fun setCellsEqual(
        cells: List<Pair<Int, Int>>,
        values: List<Double>,
        table: MutableList<MutableList<Double>>
    ): MutableList<MutableList<Double>> {
        if (cells.isEmpty()) return table
        val replacement = if (values.isNotEmpty()) values
        else {
            val avg = cells.mapNotNull { (r, c) ->
                table.getOrNull(r)?.getOrNull(c)
            }.average()
            List(cells.size) { avg }
        }
        cells.forEachIndexed { idx, (r, c) ->
            if (r in table.indices && c in table[r].indices && idx < replacement.size) {
                table[r][c] = replacement[idx]
            }
        }
        return table
    }

    // ======================================================================
    // Scale cells
    // ======================================================================

    /**
     * Multiply every cell in [cells] by [factor].
     *
     * @param cells  List of (row, col) coordinates.
     * @param factor Multiplication factor (e.g. 1.05 for +5%).
     * @param table  Mutable 2D table.
     */
    fun scaleCells(
        cells: List<Pair<Int, Int>>,
        factor: Double,
        table: MutableList<MutableList<Double>>
    ): MutableList<MutableList<Double>> {
        for ((r, c) in cells) {
            if (r in table.indices && c in table[r].indices) {
                table[r][c] = table[r][c] * factor
            }
        }
        return table
    }

    // ======================================================================
    // Add offset
    // ======================================================================

    /**
     * Add a fixed [offset] to every cell in [cells].
     *
     * @param cells  List of (row, col) coordinates.
     * @param offset Value to add to each cell.
     * @param table  Mutable 2D table.
     */
    fun addOffset(
        cells: List<Pair<Int, Int>>,
        offset: Double,
        table: MutableList<MutableList<Double>>
    ): MutableList<MutableList<Double>> {
        for ((r, c) in cells) {
            if (r in table.indices && c in table[r].indices) {
                table[r][c] = table[r][c] + offset
            }
        }
        return table
    }

    // ======================================================================
    // Fill region
    // ======================================================================

    /**
     * Fill every cell in [cells] with [value], regardless of their current content.
     *
     * @param cells  List of (row, col) coordinates.
     * @param value  The fill value.
     * @param table  Mutable 2D table.
     */
    fun fillRegion(
        cells: List<Pair<Int, Int>>,
        value: Double,
        table: MutableList<MutableList<Double>>
    ): MutableList<MutableList<Double>> {
        for ((r, c) in cells) {
            if (r in table.indices && c in table[r].indices) {
                table[r][c] = value
            }
        }
        return table
    }

    // ======================================================================
    // Smooth table (3×3 Gaussian kernel)
    // ======================================================================

    /**
     * Apply 3×3 Gaussian kernel smoothing to [selectedCells] in [table].
     *
     * Uses the standard 3×3 kernel:
     * ```
     * 1  2  1
     * 2  4  2
     * 1  2  1
     * ```
     * normalized by 16.
     *
     * Only cells within [selectedCells] are updated; neighboring cells
     * outside the selection contribute to the weighted average but are
     * not themselves modified.
     *
     * @param table          Mutable 2D table.
     * @param selectedCells  Cells to smooth.
     * @param passes         Number of smoothing passes (default 1).
     */
    fun smoothTable(
        table: MutableList<MutableList<Double>>,
        selectedCells: List<Pair<Int, Int>>,
        passes: Int = 1
    ): MutableList<MutableList<Double>> {
        val kernel = arrayOf(
            doubleArrayOf(1.0, 2.0, 1.0),
            doubleArrayOf(2.0, 4.0, 2.0),
            doubleArrayOf(1.0, 2.0, 1.0)
        )
        val kernelSum = 16.0
        val selection = selectedCells.toSet()

        repeat(passes) {
            // Snapshot current state for this pass so reads are from the
            // pre-smooth values, not partially modified ones.
            val snapshot = table.map { row -> row.toDoubleArray() }
            val rows = snapshot.size
            val cols = if (rows > 0) snapshot[0].size else 0

            for ((r, c) in selection) {
                if (r !in table.indices || c !in table[r].indices) continue
                var sum = 0.0
                for (kr in -1..1) {
                    for (kc in -1..1) {
                        val nr = r + kr
                        val nc = c + kc
                        val neighbor = if (nr in 0 until rows && nc in 0 until cols) {
                            snapshot[nr][nc]
                        } else {
                            snapshot[r][c] // edge: reflect self
                        }
                        sum += neighbor * kernel[kr + 1][kc + 1]
                    }
                }
                table[r][c] = sum / kernelSum
            }
        }
        return table
    }

    // ======================================================================
    // Interpolate cells (bilinear gap fill)
    // ======================================================================

    /**
     * Fill gaps in [selectedCells] using bilinear interpolation from
     * surrounding defined (non-NaN) cells.
     *
     * Cells whose value is NaN are treated as gaps and will be filled.
     * The algorithm runs iteratively: each pass fills the NaN cell with
     * the weighted average of its nearest non-NaN neighbors in the four
     * cardinal + diagonal directions, using inverse-distance weighting.
     *
     * @param table          Mutable 2D table.
     * @param selectedCells  Cells to potentially fill.
     * @param values         Current 2D values (must be same dimensions as [table]).
     * @param rows           Number of rows in the table.
     * @param cols           Number of columns in the table.
     */
    fun interpolateCells(
        table: MutableList<MutableList<Double>>,
        selectedCells: List<Pair<Int, Int>>,
        values: List<List<Double>>,
        rows: Int,
        cols: Int
    ): MutableList<MutableList<Double>> {
        // Sync table from values
        for (r in 0 until minOf(rows, table.size)) {
            for (c in 0 until minOf(cols, table[r].size)) {
                if (r < values.size && c < values[r].size) {
                    table[r][c] = values[r][c]
                }
            }
        }

        val selection = selectedCells.toSet()
        var changed = true
        var passCount = 0
        while (changed && passCount < 50) {
            changed = false
            passCount++
            for ((r, c) in selection) {
                if (r !in table.indices || c !in table[r].indices) continue
                if (!table[r][c].isNaN()) continue

                var weightedSum = 0.0
                var weightTotal = 0.0
                for (dr in -3..3) {
                    for (dc in -3..3) {
                        if (dr == 0 && dc == 0) continue
                        val nr = r + dr
                        val nc = c + dc
                        if (nr !in table.indices || nc !in table[nr].indices) continue
                        val neighborVal = table[nr][nc]
                        if (neighborVal.isNaN()) continue
                        val dist = kotlin.math.sqrt((dr * dr + dc * dc).toDouble())
                        val weight = 1.0 / dist
                        weightedSum += neighborVal * weight
                        weightTotal += weight
                    }
                }
                if (weightTotal > 0.0) {
                    table[r][c] = weightedSum / weightTotal
                    changed = true
                }
            }
        }
        return table
    }

    // ======================================================================
    // Bilinear interpolation (3D table lookup)
    // ======================================================================

    /**
     * Perform bilinear interpolation on a 3D table to find the value at
     * a non-exact (x, y) coordinate.
     *
     * This is the standard algorithm used by ECU firmware to compute fuel/
     * ignition values between bin centers. Given:
     *   - [xBins]: column axis values (e.g. RPM bins)
     *   - [yBins]: row axis values (e.g. load/MAP bins)
     *   - [values]: 2D grid of cell values, indexed as values[row][col]
     *   - [targetX]: the X coordinate to look up (e.g. current RPM)
     *   - [targetY]: the Y coordinate to look up (e.g. current MAP)
     *
     * The algorithm:
     *   1. Find the bin indices (i, j) such that xBins[i] <= targetX < xBins[i+1]
     *      and yBins[j] <= targetY < yBins[j+1].
     *   2. Compute the fractional position (fx, fy) within the cell.
     *   3. Bilinearly interpolate between the four corner cells:
     *      v = (1-fx)(1-fy)*v00 + fx*(1-fy)*v10 + (1-fx)*fy*v01 + fx*fy*v11
     *
     * If [targetX] or [targetY] is outside the axis range, the nearest edge
     * bin is used (clamping).
     *
     * @return The interpolated value, or `Double.NaN` if the table is empty
     *   or the axes are empty.
     */
    fun interpolateValue(
        xBins: List<Double>,
        yBins: List<Double>,
        values: List<List<Double>>,
        targetX: Double,
        targetY: Double
    ): Double {
        if (xBins.isEmpty() || yBins.isEmpty() || values.isEmpty()) return Double.NaN
        val cols = xBins.size
        val rows = yBins.size
        if (values.size < rows || values[0].size < cols) return Double.NaN

        // Find X bin interval (column indices)
        val (x0, x1) = findBinInterval(xBins, targetX)
        // Find Y bin interval (row indices)
        val (y0, y1) = findBinInterval(yBins, targetY)

        // Four corner values
        val v00 = values[y0][x0]
        val v10 = values[y0][x1]
        val v01 = values[y1][x0]
        val v11 = values[y1][x1]

        // Fractional positions
        val xRange = xBins[x1] - xBins[x0]
        val yRange = yBins[y1] - yBins[y0]
        val fx = if (xRange == 0.0) 0.0 else (targetX - xBins[x0]) / xRange
        val fy = if (yRange == 0.0) 0.0 else (targetY - yBins[y0]) / yRange

        // Bilinear interpolation
        return (1 - fx) * (1 - fy) * v00 +
               fx * (1 - fy) * v10 +
               (1 - fx) * fy * v01 +
               fx * fy * v11
    }

    /**
     * Find the bin interval [i, i+1] that contains [target], or clamp to
     * the nearest edge if [target] is outside the range.
     *
     * @return Pair of (lowerIndex, upperIndex). If target < bins[0], returns
     *   (0, 0). If target >= bins.last(), returns (last, last).
     */
    private fun findBinInterval(bins: List<Double>, target: Double): Pair<Int, Int> {
        if (bins.isEmpty()) return Pair(0, 0)
        if (target <= bins[0]) return Pair(0, 0)
        if (target >= bins.last()) return Pair(bins.size - 1, bins.size - 1)

        for (i in 0 until bins.size - 1) {
            if (target >= bins[i] && target < bins[i + 1]) {
                return Pair(i, i + 1)
            }
        }
        return Pair(bins.size - 1, bins.size - 1)
    }
}