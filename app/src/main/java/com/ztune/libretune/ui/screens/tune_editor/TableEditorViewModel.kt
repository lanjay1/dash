package com.ztune.libretune.ui.screens.tune_editor

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ztune.libretune.core.TuneManager
import com.ztune.libretune.core.ini.EcuDefinition
import com.ztune.libretune.core.ini.types.TableDefinition
import com.ztune.libretune.core.realtime.RealtimeChannelStore
import com.ztune.libretune.core.tune.TableOperations
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@HiltViewModel
class TableEditorViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val tuneManager: TuneManager,
    private val channelStore: RealtimeChannelStore
) : ViewModel() {

    private val _uiState = MutableStateFlow(TableEditorUiState())
    val uiState: StateFlow<TableEditorUiState> = _uiState

    private val undoStack = ArrayDeque<List<List<Double>>>()
    private val redoStack = ArrayDeque<List<List<Double>>>()
    private val maxHistoryDepth = 100
    private val lockedCells = mutableSetOf<Pair<Int, Int>>()

    private var definition: EcuDefinition? = null
    private var tableDef: TableDefinition? = null
    private var xOutputChannel: String? = null
    private var yOutputChannel: String? = null

    private val tableName: String = savedStateHandle["tableName"] ?: ""

    init {
        if (tableName.isNotEmpty()) loadTable(tableName)
        observeLiveCursors()
    }

    private fun observeLiveCursors() {
        if (xOutputChannel == null && yOutputChannel == null) return
        viewModelScope.launch {
            channelStore.channels.collect { channels ->
                val xVal = xOutputChannel?.let { channels[it] }
                val yVal = yOutputChannel?.let { channels[it] }
                if (xVal != null || yVal != null) {
                    _uiState.update {
                        it.copy(liveXValue = xVal, liveYValue = yVal)
                    }
                    updateLiveCursor()
                }
            }
        }
    }

    private fun updateLiveCursor() {
        val st = _uiState.value
        val xVal = st.liveXValue ?: return
        val yVal = st.liveYValue ?: return
        val xBins = st.xBins
        val yBins = st.yBins
        if (xBins.size < 2 || yBins.size < 2) return
        var bestCol = 0
        var minDistX = Double.MAX_VALUE
        for (i in xBins.indices) {
            val d = kotlin.math.abs(xBins[i] - xVal)
            if (d < minDistX) { minDistX = d; bestCol = i }
        }
        var bestRow = 0
        var minDistY = Double.MAX_VALUE
        for (i in yBins.indices) {
            val d = kotlin.math.abs(yBins[i] - yVal)
            if (d < minDistY) { minDistY = d; bestRow = i }
        }
        _uiState.update { it.copy(liveCell = Pair(bestRow, bestCol)) }
    }

    fun loadTable(name: String) {
        val def = definition ?: tuneManager.activeDefinition
        if (def == null) { loadDemoTable(name); return }
        val tbl = def.getTableByNameOrMap(name)
        if (tbl == null) { loadDemoTable(name); return }
        definition = def
        tableDef = tbl
        xOutputChannel = null
        yOutputChannel = null
        val tune = tuneManager.currentTune
        val pageData = tune?.getPageData(tbl.page)
        val decoder = com.ztune.libretune.core.realtime.RealtimeDecoder(def)
        val xBins = if (pageData != null) decoder.decodeTableAxis(pageData, tbl.xAxis) else (0 until tbl.cols).map { it.toDouble() }
        val yBins = if (pageData != null) decoder.decodeTableAxis(pageData, tbl.yAxis) else (0 until tbl.rows).map { it.toDouble() }
        val values = if (pageData != null) decoder.decodeTable(pageData, tbl) else List(tbl.rows) { List(tbl.cols) { 0.0 } }
        undoStack.clear()
        redoStack.clear()
        _uiState.update {
            it.copy(
                tableName = name,
                title = tbl.name.ifEmpty { name },
                rows = tbl.rows,
                cols = tbl.cols,
                xBins = xBins,
                yBins = yBins,
                values = values,
                units = tbl.units,
                format = if (tbl.scale == 1.0 && tbl.translate == 0.0) "0" else "0.0",
                min = 0.0,
                max = 0.0,
                selectedCell = null,
                isModified = false,
                canUndo = false,
                canRedo = false
            )
        }
    }

    private fun loadDemoTable(name: String) {
        val demo = demoTable(name)
        undoStack.clear(); redoStack.clear()
        _uiState.update {
            it.copy(tableName = name, title = demo.title, rows = demo.rows, cols = demo.cols,
                xBins = demo.xBins, yBins = demo.yBins, values = demo.values,
                units = demo.units, format = demo.format, min = demo.min, max = demo.max,
                selectedCell = null, isModified = false, canUndo = false, canRedo = false)
        }
    }

    fun selectCell(row: Int, col: Int) { _uiState.update { it.copy(selectedCell = Pair(row, col)) } }
    fun clearSelection() { _uiState.update { it.copy(selectedCell = null) } }

    fun setCellValue(row: Int, col: Int, value: Double) {
        val current = _uiState.value
        if (row !in 0 until current.rows || col !in 0 until current.cols) return
        if (Pair(row, col) in lockedCells) return
        if (current.values[row][col] == value) return
        pushUndo()
        val newValues = current.values.mapIndexed { r, rowList ->
            if (r == row) rowList.mapIndexed { c, v -> if (c == col) value else v } else rowList
        }
        _uiState.update { it.copy(values = newValues, isModified = true, canUndo = true, canRedo = false) }
        tableDef?.let { tuneManager.updateTableCell(tableName, row, col, value) }
    }

    fun scaleSelected(factor: Double) {
        val cells = getSelectedCells() ?: return
        pushUndo()
        val mutable = toMutableValues()
        TableOperations.scaleCells(cells, factor, mutable)
        applyValues(mutable)
    }

    fun smoothSelected(passes: Int = 1) {
        val st = _uiState.value
        val mutable = toMutableValues()
        TableOperations.smoothTable(mutable, (0 until st.rows).flatMap { r -> (0 until st.cols).map { c -> r to c } }, passes)
        pushUndo()
        applyValues(mutable)
    }

    fun interpolateSelected() {
        val st = _uiState.value
        pushUndo()
        val mutable = toMutableValues()
        TableOperations.interpolateCells(
            table = mutable,
            selectedCells = (0 until st.rows).flatMap { r -> (0 until st.cols).map { c -> r to c } },
            values = st.values,
            rows = st.rows,
            cols = st.cols
        )
        applyValues(mutable)
    }

    fun setCellsEqual() {
        val cells = getSelectedCells() ?: return
        pushUndo()
        val mutable = toMutableValues()
        TableOperations.setCellsEqual(cells, emptyList(), mutable)
        applyValues(mutable)
    }

    fun addOffset(offset: Double) {
        val cells = getSelectedCells() ?: return
        pushUndo()
        val mutable = toMutableValues()
        TableOperations.addOffset(cells, offset, mutable)
        applyValues(mutable)
    }

    fun toggleCellLock() {
        val cell = _uiState.value.selectedCell ?: return
        if (cell in lockedCells) lockedCells.remove(cell) else lockedCells.add(cell)
    }

    fun undo() {
        if (undoStack.isEmpty()) return
        redoStack.addLast(_uiState.value.values.map { it.toList() })
        val prev = undoStack.removeLast()
        _uiState.update { it.copy(values = prev, isModified = true, canUndo = undoStack.isNotEmpty(), canRedo = true) }
    }

    fun redo() {
        if (redoStack.isEmpty()) return
        undoStack.addLast(_uiState.value.values.map { it.toList() })
        val next = redoStack.removeLast()
        _uiState.update { it.copy(values = next, isModified = true, canUndo = true, canRedo = redoStack.isNotEmpty()) }
    }

    fun burnTable() {
        viewModelScope.launch {
            _uiState.update { it.copy(isBurning = true) }
            _uiState.update { it.copy(isBurning = false, isModified = false) }
        }
    }

    private fun getSelectedCells(): List<Pair<Int, Int>>? {
        val cell = _uiState.value.selectedCell ?: return null
        return listOf(cell)
    }

    private fun pushUndo() {
        if (undoStack.size >= maxHistoryDepth) undoStack.removeFirst()
        undoStack.addLast(_uiState.value.values.map { it.toList() })
        redoStack.clear()
    }

    private fun toMutableValues(): MutableList<MutableList<Double>> {
        return _uiState.value.values.map { it.toMutableList() }.toMutableList()
    }

    private fun applyValues(mutable: MutableList<MutableList<Double>>) {
        val immutable = mutable.map { it.toList() }
        _uiState.update { it.copy(values = immutable, isModified = true, canUndo = true, canRedo = false) }
        tableDef?.let { tbl ->
            for (r in immutable.indices) {
                for (c in immutable[r].indices) {
                    tuneManager.updateTableCell(tableName, r, c, immutable[r][c])
                }
            }
        }
    }

    // --- Demo data ---
    private data class DemoTable(val title: String, val rows: Int, val cols: Int,
        val xBins: List<Double>, val yBins: List<Double>, val values: List<List<Double>>,
        val units: String, val format: String, val min: Double, val max: Double)

    private fun demoTable(name: String): DemoTable = when (name.lowercase()) {
        "ve", "vetable" -> demoVeTable()
        "ignition", "ign", "spark" -> demoIgnitionTable()
        "afr", "afrtarget" -> demoAfrTable()
        else -> demoGenericTable()
    }

    private fun demoVeTable(): DemoTable {
        val rpm = listOf(500.0,800.0,1000.0,1200.0,1500.0,1800.0,2000.0,2500.0,3000.0,3500.0,4000.0,4500.0,5000.0,5500.0,6000.0,6500.0)
        val map = listOf(20.0,30.0,40.0,50.0,60.0,70.0,80.0,90.0,100.0,105.0,110.0,115.0,120.0,130.0,140.0,150.0)
        val vals = List(map.size) { r -> List(rpm.size) { c ->
            val rn = c/(rpm.size-1.0); val mn = r/(map.size-1.0)
            (40.0 + Math.exp(-((rn-0.6)*(rn-0.6)*4.0+(mn-0.7)*(mn-0.7)*4.0))*90.0 + ((r*7+c*13)%5-2)).coerceIn(20.0,130.0)
        }}
        return DemoTable("VE Table",map.size,rpm.size,rpm,map,vals,"%","0.0",20.0,130.0)
    }
    private fun demoIgnitionTable(): DemoTable {
        val rpm = listOf(500.0,800.0,1000.0,1200.0,1500.0,1800.0,2000.0,2500.0,3000.0,3500.0,4000.0,4500.0,5000.0,5500.0,6000.0,6500.0)
        val map = listOf(20.0,30.0,40.0,50.0,60.0,70.0,80.0,90.0,100.0,105.0,110.0,115.0,120.0,130.0,140.0,150.0)
        val vals = List(map.size) { r -> List(rpm.size) { c ->
            (10.0 + c/(rpm.size-1.0)*35.0 - r/(map.size-1.0)*25.0 + ((r*3+c*11)%4-2)*0.5).coerceIn(5.0,45.0)
        }}
        return DemoTable("Ignition Timing",map.size,rpm.size,rpm,map,vals,"°","0.0",5.0,45.0)
    }
    private fun demoAfrTable(): DemoTable {
        val rpm = listOf(500.0,800.0,1000.0,1200.0,1500.0,1800.0,2000.0,2500.0,3000.0,3500.0,4000.0,4500.0,5000.0,5500.0,6000.0,6500.0)
        val map = listOf(20.0,40.0,60.0,80.0,100.0,120.0,140.0,160.0)
        val vals = List(map.size) { r -> List(rpm.size) { c ->
            (15.5 - r/(map.size-1.0)*2.0 - c/(rpm.size-1.0)*0.5 + ((r*5+c*9)%3-1)*0.1).coerceIn(10.5,16.0)
        }}
        return DemoTable("AFR Target",map.size,rpm.size,rpm,map,vals,":1","0.0",10.5,16.0)
    }
    private fun demoGenericTable(): DemoTable {
        val s = 8; val x = List(s){it*1000.0}; val y = List(s){20.0+it*10.0}
        return DemoTable("Table",s,s,x,y,List(s){r->List(s){c->50.0+r*3.0+c*2.0}},"","0.0",40.0,120.0)
    }

    data class TableEditorUiState(
        val tableName: String = "", val title: String = "", val rows: Int = 0, val cols: Int = 0,
        val xBins: List<Double> = emptyList(), val yBins: List<Double> = emptyList(),
        val values: List<List<Double>> = emptyList(), val selectedCell: Pair<Int, Int>? = null,
        val isModified: Boolean = false, val units: String = "", val format: String = "0.0",
        val min: Double = 0.0, val max: Double = 255.0, val canUndo: Boolean = false,
        val canRedo: Boolean = false, val isBurning: Boolean = false,
        val liveXValue: Double? = null, val liveYValue: Double? = null,
        val liveCell: Pair<Int, Int>? = null
    )
}