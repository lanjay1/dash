package com.ztune.libretune.ui.screens.curve_editor

import androidx.compose.ui.geometry.Offset
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ztune.libretune.core.EcuConnectionManager
import com.ztune.libretune.core.TuneManager
import com.ztune.libretune.core.realtime.RealtimeChannelStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class CurveEditorState(
    val curveName: String = "",
    val title: String = "",
    val xBins: List<Double> = emptyList(),
    val yBins: List<Double> = emptyList(),
    val xLabel: String = "",
    val yLabel: String = "",
    val selectedPoint: Int = -1,
    val isModified: Boolean = false,
    val liveXValue: Double? = null,
    val xOutputChannel: String? = null,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false
)

/**
 * Phase 30: Converted to @HiltViewModel.
 *
 * Injects [EcuConnectionManager] and [TuneManager] to get the active
 * definition and tune at runtime. The curve name comes from
 * [SavedStateHandle] (navigation argument).
 */
@HiltViewModel
class CurveEditorViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val connectionManager: EcuConnectionManager,
    private val tuneManager: TuneManager,
    private val channelStore: RealtimeChannelStore
) : ViewModel() {

    private val curveName: String = savedStateHandle["name"] ?: ""

    private val _state = MutableStateFlow(CurveEditorState(curveName = curveName))
    val state: StateFlow<CurveEditorState> = _state

    private val undoStack = ArrayDeque<List<Double>>()
    private val redoStack = ArrayDeque<List<Double>>()
    private val maxHistoryDepth = 100

    init {
        loadCurve()
        observeLiveX()
    }

    private fun loadCurve() {
        val def = connectionManager.activeDefinition ?: return
        val tune = tuneManager.currentTune ?: return
        val curve = def.getCurveByNameOrMap(curveName) ?: return
        val pageData = tune.getPageData(curve.valuesPage) ?: return
        val decoder = com.ztune.libretune.core.realtime.RealtimeDecoder(def)
        val yBins = decoder.decodeCurve(pageData, curve)
        val xBins = decoder.decodeTableAxis(pageData, def.tables.values.firstOrNull()?.xAxis)

        _state.update {
            it.copy(
                title = curve.title.ifEmpty { curveName },
                xBins = xBins,
                yBins = yBins,
                xLabel = "X",
                yLabel = curve.units
            )
        }
    }

    private fun observeLiveX() {
        viewModelScope.launch {
            channelStore.channels.collect { channels ->
                val xVal = _state.value.xOutputChannel?.let { channels[it] }
                if (xVal != null) {
                    _state.update { it.copy(liveXValue = xVal) }
                }
            }
        }
    }

    fun selectPoint(index: Int) {
        _state.update { it.copy(selectedPoint = index) }
    }

    /**
     * Set a point's value by drag. The Screen passes an Offset and Rect,
     * but we only need the point index and new value.
     */
    fun setPointByDrag(offset: androidx.compose.ui.geometry.Offset, chartBounds: androidx.compose.ui.geometry.Rect) {
        val st = _state.value
        if (st.xBins.isEmpty() || st.yBins.isEmpty()) return
        // Find nearest x bin
        val xRatio = if (chartBounds.width > 0) (offset.x - chartBounds.left) / chartBounds.width else 0.0
        val idx = (xRatio * st.xBins.size).toInt().coerceIn(0, st.yBins.size - 1)
        // Compute new y value from y position
        val yRatio = if (chartBounds.height > 0) 1.0 - (offset.y - chartBounds.top) / chartBounds.height else 0.5
        val yMin = st.yBins.minOrNull() ?: 0.0
        val yMax = st.yBins.maxOrNull() ?: 100.0
        val newValue = yMin + yRatio * (yMax - yMin)
        setPointValue(idx, newValue)
    }

    /** Set a point's value by index. */
    fun setPointValue(index: Int, newValue: Double) {
        pushUndo()
        val def = connectionManager.activeDefinition ?: return
        val curve = def.getCurveByNameOrMap(curveName) ?: return
        try {
            tuneManager.updateCurveValue(curveName, index, newValue)
            loadCurve()
            _state.update { it.copy(isModified = true, canUndo = true, canRedo = false) }
        } catch (_: Exception) { }
    }

    fun undo() {
        if (undoStack.isEmpty()) return
        redoStack.addLast(_state.value.yBins)
        val prev = undoStack.removeLast()
        // Restore values
        val def = connectionManager.activeDefinition ?: return
        for (i in prev.indices) {
            try { tuneManager.updateCurveValue(curveName, i, prev[i]) } catch (_: Exception) { }
        }
        loadCurve()
        _state.update { it.copy(isModified = true, canUndo = undoStack.isNotEmpty(), canRedo = true) }
    }

    fun redo() {
        if (redoStack.isEmpty()) return
        undoStack.addLast(_state.value.yBins)
        val next = redoStack.removeLast()
        val def = connectionManager.activeDefinition ?: return
        for (i in next.indices) {
            try { tuneManager.updateCurveValue(curveName, i, next[i]) } catch (_: Exception) { }
        }
        loadCurve()
        _state.update { it.copy(isModified = true, canUndo = true, canRedo = redoStack.isNotEmpty()) }
    }

    private fun pushUndo() {
        if (undoStack.size >= maxHistoryDepth) undoStack.removeFirst()
        undoStack.addLast(_state.value.yBins)
        redoStack.clear()
    }

    fun canUndo(): Boolean = undoStack.isNotEmpty()
    fun canRedo(): Boolean = redoStack.isNotEmpty()

    fun interpolateSelected() {
        val st = _state.value
        val idx = st.selectedPoint
        if (idx < 0 || idx >= st.yBins.size) return
        val left = st.yBins.getOrNull(idx - 1) ?: return
        val right = st.yBins.getOrNull(idx + 1) ?: return
        val mid = (left + right) / 2.0
        setPointValue(idx, mid)
    }

    fun smoothCurve() {
        val st = _state.value
        val yBins = st.yBins.toMutableList()
        if (yBins.size < 3) return
        val smoothed = yBins.toMutableList()
        for (i in 1 until yBins.size - 1) {
            smoothed[i] = (yBins[i - 1] + 2 * yBins[i] + yBins[i + 1]) / 4.0
        }
        for (i in smoothed.indices) {
            setPointValue(i, smoothed[i])
        }
    }
}
