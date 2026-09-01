package com.ztune.libretune.ui.screens.tune_editor

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

@HiltViewModel
class TableEditorViewModel @Inject constructor() : ViewModel() {

    private val _uiState = MutableStateFlow(TableEditorUiState())
    val uiState: StateFlow<TableEditorUiState> = _uiState

    // --------------------------------------------------------------------------
    //  Undo / Redo stacks — each entry is a full snapshot of the values grid.
    // --------------------------------------------------------------------------

    private val undoStack = ArrayDeque<List<List<Double>>>()
    private val redoStack = ArrayDeque<List<List<Double>>>()

    // Maximum depth for each stack to keep memory bounded.
    private val maxHistoryDepth = 100

    // --------------------------------------------------------------------------
    //  Public API
    // --------------------------------------------------------------------------

    /**
     * Loads (or reloads) a table by [tableName].
     *
     * For now this populates demo data so the UI is functional.
     * Once the tune-data pipeline is wired, this will pull values from
     * [com.ztune.libretune.core.TuneManager].
     */
    fun loadTable(tableName: String) {
        // TODO: Replace demo data with actual tune data from TuneManager.
        val demo = demoTable(tableName)
        undoStack.clear()
        redoStack.clear()
        _uiState.update { state ->
            state.copy(
                tableName = tableName,
                title = demo.title,
                rows = demo.rows,
                cols = demo.cols,
                xBins = demo.xBins,
                yBins = demo.yBins,
                values = demo.values,
                selectedCell = null,
                isModified = false,
                units = demo.units,
                format = demo.format,
                min = demo.min,
                max = demo.max,
                canUndo = false,
                canRedo = false
            )
        }
    }

    /**
     * Selects the cell at ([row], [col]). If the cell is already selected
     * the caller (screen) should interpret this as a "request to edit".
     */
    fun selectCell(row: Int, col: Int) {
        _uiState.update { it.copy(selectedCell = Pair(row, col)) }
    }

    /**
     * Clears the current selection.
     */
    fun clearSelection() {
        _uiState.update { it.copy(selectedCell = null) }
    }

    /**
     * Sets the value at ([row], [col]) and pushes the previous state
     * onto the undo stack.
     */
    fun setCellValue(row: Int, col: Int, value: Double) {
        val current = _uiState.value
        if (row !in 0 until current.rows || col !in 0 until current.cols) return

        val previousValues = current.values.map { it.toList() }
        val oldValue = current.values[row][col]
        if (oldValue == value) return // nothing changed

        // Push snapshot to undo stack.
        if (undoStack.size >= maxHistoryDepth) undoStack.removeFirst()
        undoStack.addLast(previousValues)
        // Any new edit invalidates the redo branch.
        redoStack.clear()

        val newValues = current.values.mapIndexed { r, rowList ->
            if (r == row) rowList.mapIndexed { c, v -> if (c == col) value else v }
            else rowList
        }

        _uiState.update {
            it.copy(
                values = newValues,
                isModified = true,
                canUndo = undoStack.isNotEmpty(),
                canRedo = false
            )
        }
    }

    /**
     * Reverts the most recent edit.
     */
    fun undo() {
        if (undoStack.isEmpty()) return
        val currentValues = _uiState.value.values.map { it.toList() }
        redoStack.addLast(currentValues)
        val previousValues = undoStack.removeLast()

        _uiState.update {
            it.copy(
                values = previousValues,
                isModified = true,
                canUndo = undoStack.isNotEmpty(),
                canRedo = redoStack.isNotEmpty()
            )
        }
    }

    /**
     * Re-applies the most recently undone edit.
     */
    fun redo() {
        if (redoStack.isEmpty()) return
        val currentValues = _uiState.value.values.map { it.toList() }
        undoStack.addLast(currentValues)
        val nextValues = redoStack.removeLast()

        _uiState.update {
            it.copy(
                values = nextValues,
                isModified = true,
                canUndo = undoStack.isNotEmpty(),
                canRedo = redoStack.isNotEmpty()
            )
        }
    }

    // --------------------------------------------------------------------------
    //  Demo data
    // --------------------------------------------------------------------------

    private data class DemoTable(
        val title: String,
        val rows: Int,
        val cols: Int,
        val xBins: List<Double>,
        val yBins: List<Double>,
        val values: List<List<Double>>,
        val units: String,
        val format: String,
        val min: Double,
        val max: Double
    )

    /**
     * Returns a demo table. Known names get realistic-shaped data;
     * unknown names get a generic 8×8 grid.
     */
    private fun demoTable(name: String): DemoTable {
        return when (name.lowercase()) {
            "ve", "vetable", "volumetric efficiency" -> demoVeTable()
            "ignition", "ign", "spark", "timing", "ignition timing" -> demoIgnitionTable()
            "afr", "afrtarget", "afr target", "target afr" -> demoAfrTable()
            else -> demoGenericTable()
        }
    }

    /** Typical 16×16 VE table (RPM × kPa). */
    private fun demoVeTable(): DemoTable {
        val rpmBins = listOf(
            500.0, 800.0, 1000.0, 1200.0, 1500.0, 1800.0,
            2000.0, 2500.0, 3000.0, 3500.0, 4000.0, 4500.0,
            5000.0, 5500.0, 6000.0, 6500.0
        )
        val mapBins = listOf(
            20.0, 30.0, 40.0, 50.0, 60.0, 70.0,
            80.0, 90.0, 100.0, 105.0, 110.0, 115.0,
            120.0, 130.0, 140.0, 150.0
        )
        // Generate a realistic VE map: low RPM + low MAP = low VE,
        // peak around mid-RPM / mid-MAP.
        val rows = mapBins.size
        val cols = rpmBins.size
        val values = List(rows) { r ->
            List(cols) { c ->
                val rpmNorm = c / (cols - 1.0)
                val mapNorm = r / (rows - 1.0)
                // Bell-curve-ish shape peaking around 60-70% RPM, 60-80% MAP
                val peak = Math.exp(-((rpmNorm - 0.6) * (rpmNorm - 0.6) * 4.0 +
                        (mapNorm - 0.7) * (mapNorm - 0.7) * 4.0))
                val base = 40.0 + peak * 90.0
                // Add slight noise for realism
                val noise = ((r * 7 + c * 13) % 5 - 2).toDouble()
                (base + noise).coerceIn(20.0, 130.0)
            }
        }
        return DemoTable(
            title = "VE Table",
            rows = rows,
            cols = cols,
            xBins = rpmBins,
            yBins = mapBins,
            values = values,
            units = "%",
            format = "0.0",
            min = 20.0,
            max = 130.0
        )
    }

    /** Typical 16×16 ignition timing table (RPM × kPa). */
    private fun demoIgnitionTable(): DemoTable {
        val rpmBins = listOf(
            500.0, 800.0, 1000.0, 1200.0, 1500.0, 1800.0,
            2000.0, 2500.0, 3000.0, 3500.0, 4000.0, 4500.0,
            5000.0, 5500.0, 6000.0, 6500.0
        )
        val mapBins = listOf(
            20.0, 30.0, 40.0, 50.0, 60.0, 70.0,
            80.0, 90.0, 100.0, 105.0, 110.0, 115.0,
            120.0, 130.0, 140.0, 150.0
        )
        val rows = mapBins.size
        val cols = rpmBins.size
        // Ignition timing: higher at low MAP (vacuum), lower at high MAP.
        // Advances with RPM up to a point.
        val values = List(rows) { r ->
            List(cols) { c ->
                val rpmNorm = c / (cols - 1.0)
                val mapNorm = r / (rows - 1.0)
                val baseTiming = 10.0 + rpmNorm * 35.0 - mapNorm * 25.0
                val noise = ((r * 3 + c * 11) % 4 - 2).toDouble() * 0.5
                (baseTiming + noise).coerceIn(5.0, 45.0)
            }
        }
        return DemoTable(
            title = "Ignition Timing",
            rows = rows,
            cols = cols,
            xBins = rpmBins,
            yBins = mapBins,
            values = values,
            units = "°",
            format = "0.0",
            min = 5.0,
            max = 45.0
        )
    }

    /** Typical 16×8 AFR target table (RPM × kPa, fewer MAP rows). */
    private fun demoAfrTable(): DemoTable {
        val rpmBins = listOf(
            500.0, 800.0, 1000.0, 1200.0, 1500.0, 1800.0,
            2000.0, 2500.0, 3000.0, 3500.0, 4000.0, 4500.0,
            5000.0, 5500.0, 6000.0, 6500.0
        )
        val mapBins = listOf(
            20.0, 40.0, 60.0, 80.0, 100.0, 120.0, 140.0, 160.0
        )
        val rows = mapBins.size
        val cols = rpmBins.size
        // AFR targets: leaner at low load, richer at high load & high RPM.
        val values = List(rows) { r ->
            List(cols) { c ->
                val rpmNorm = c / (cols - 1.0)
                val mapNorm = r / (rows - 1.0)
                val baseAfr = 15.5 - mapNorm * 2.0 - rpmNorm * 0.5
                val noise = ((r * 5 + c * 9) % 3 - 1).toDouble() * 0.1
                (baseAfr + noise).coerceIn(10.5, 16.0)
            }
        }
        return DemoTable(
            title = "AFR Target",
            rows = rows,
            cols = cols,
            xBins = rpmBins,
            yBins = mapBins,
            values = values,
            units = ":1",
            format = "0.0",
            min = 10.5,
            max = 16.0
        )
    }

    /** Generic 8×8 table with linearly varying values. */
    private fun demoGenericTable(): DemoTable {
        val size = 8
        val xBins = List(size) { (it * 1000.0) }
        val yBins = List(size) { 20.0 + it * 10.0 }
        val values = List(size) { r ->
            List(size) { c ->
                50.0 + r * 3.0 + c * 2.0
            }
        }
        return DemoTable(
            title = "Table",
            rows = size,
            cols = size,
            xBins = xBins,
            yBins = yBins,
            values = values,
            units = "",
            format = "0.0",
            min = 40.0,
            max = 120.0
        )
    }

    // --------------------------------------------------------------------------
    //  UI state
    // --------------------------------------------------------------------------

    data class TableEditorUiState(
        val tableName: String = "",
        val title: String = "",
        val rows: Int = 0,
        val cols: Int = 0,
        val xBins: List<Double> = emptyList(),
        val yBins: List<Double> = emptyList(),
        val values: List<List<Double>> = emptyList(),
        val selectedCell: Pair<Int, Int>? = null,
        val isModified: Boolean = false,
        val units: String = "",
        val format: String = "0.0",
        val min: Double = 0.0,
        val max: Double = 255.0,
        val canUndo: Boolean = false,
        val canRedo: Boolean = false,
        val isBurning: Boolean = false
    )
}
