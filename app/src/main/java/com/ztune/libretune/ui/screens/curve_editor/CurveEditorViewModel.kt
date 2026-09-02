package com.ztune.libretune.ui.screens.curve_editor

import androidx.compose.ui.geometry.Offset
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ztune.libretune.core.ini.EcuDefinition
import com.ztune.libretune.core.ini.types.CurveDefinition
import com.ztune.libretune.core.realtime.RealtimeChannelStore
import com.ztune.libretune.core.tune.Tune
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

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
    val xOutputChannel: String? = null
)

class CurveEditorViewModel(
    private val curveName: String,
    private val definition: EcuDefinition?,
    private val tune: Tune?,
    private val channelStore: RealtimeChannelStore?,
    private val onValuesChanged: ((String, List<Double>, List<Double>) -> Unit)?
) : ViewModel() {

    private val _state = MutableStateFlow(CurveEditorState(curveName = curveName))
    val state: StateFlow<CurveEditorState> = _state

    private val undoStack = mutableListOf<Pair<List<Double>, List<Double>>>()
    private val redoStack = mutableListOf<Pair<List<Double>, List<Double>>>()

    init {
        loadCurveData()
        observeLiveChannel()
    }

    private fun loadCurveData() {
        val def = definition ?: return
        val curve = def.getCurveByNameOrMap(curveName) ?: return
        val pageData = tune?.getPageData(curve.page)
        val xBins = if (pageData != null && def != null) {
            val decoder = com.ztune.libretune.core.realtime.RealtimeDecoder(def)
            decoder.decodeTableAxis(pageData, curve.xAxis)
        } else {
            (0 until curve.size).map { it.toDouble() * 100.0 / maxOf(curve.size - 1, 1) }
        }
        val yBins = tune?.curveValues?.get(curveName) ?: (0 until curve.size).map { 50.0 }
        _state.value = _state.value.copy(
            curveName = curveName,
            title = curve.name.ifEmpty { curveName },
            xBins = xBins,
            yBins = yBins,
            xLabel = curve.xAxis?.units ?: "X",
            yLabel = curve.units,
            xOutputChannel = curve.xOutputChannel
        )
    }

    private fun observeLiveChannel() {
        val xChannel = _state.value.xOutputChannel ?: return
        val store = channelStore ?: return
        viewModelScope.launch {
            store.channels.collect { channels ->
                val xVal = channels[xChannel]
                _state.value = _state.value.copy(liveXValue = xVal)
            }
        }
    }

    fun selectPoint(index: Int) {
        _state.value = _state.value.copy(selectedPoint = index)
    }

    fun setPointValue(index: Int, newY: Double) {
        pushUndo()
        val newBins = _state.value.yBins.toMutableList()
        if (index in newBins.indices) {
            newBins[index] = newY
        }
        _state.value = _state.value.copy(yBins = newBins, isModified = true)
        onValuesChanged?.invoke(curveName, _state.value.xBins, newBins)
    }

    fun setPointByDrag(position: Offset, chartBounds: androidx.compose.ui.geometry.Rect) {
        val st = _state.value
        if (st.xBins.isEmpty()) return
        val xRatio = ((position.x - chartBounds.left) / chartBounds.width).coerceIn(0.0, 1.0)
        val yRatio = 1.0 - ((position.y - chartBounds.top) / chartBounds.height).coerceIn(0.0, 1.0)
        val targetX = st.xBins.first() + xRatio * (st.xBins.last() - st.xBins.first())
        var closestIdx = 0
        var minDist = Double.MAX_VALUE
        st.xBins.forEachIndexed { i, x ->
            val d = kotlin.math.abs(x - targetX)
            if (d < minDist) { minDist = d; closestIdx = i }
        }
        val yMin = st.yBins.minOrNull() ?: 0.0
        val yMax = st.yBins.maxOrNull() ?: 100.0
        val yRange = yMax - yMin
        val newValue = if (yRange > 0) yMin + yRatio * yRange else 50.0
        setPointValue(closestIdx, newValue)
        _state.value = _state.value.copy(selectedPoint = closestIdx)
    }

    fun interpolateSelected() {
        val st = _state.value
        val sel = st.selectedPoint
        if (sel < 0 || sel >= st.yBins.size) return
        pushUndo()
        val newBins = st.yBins.toMutableList()
        var leftIdx = sel - 1
        while (leftIdx >= 0 && newBins[leftIdx].isNaN()) leftIdx--
        var rightIdx = sel + 1
        while (rightIdx < newBins.size && newBins[rightIdx].isNaN()) rightIdx++
        val leftVal = if (leftIdx >= 0) newBins[leftIdx] else 0.0
        val rightVal = if (rightIdx < newBins.size) newBins[rightIdx] else leftVal
        val leftX = if (leftIdx >= 0) leftIdx.toDouble() else sel.toDouble()
        val rightX = if (rightIdx < newBins.size) rightIdx.toDouble() else sel.toDouble()
        val range = rightX - leftX
        val interpolated = if (range > 0) {
            leftVal + (rightVal - leftVal) * ((sel - leftX) / range)
        } else leftVal
        newBins[sel] = interpolated
        _state.value = _state.value.copy(yBins = newBins, isModified = true)
    }

    fun smoothCurve(passes: Int = 1) {
        pushUndo()
        var bins = _state.value.yBins.toMutableList()
        repeat(passes) {
            val smoothed = bins.toMutableList()
            for (i in 1 until smoothed.size - 1) {
                smoothed[i] = (bins[i - 1] + bins[i] + bins[i + 1]) / 3.0
            }
            bins = smoothed
        }
        _state.value = _state.value.copy(yBins = bins, isModified = true)
        onValuesChanged?.invoke(curveName, _state.value.xBins, bins)
    }

    fun undo() {
        if (undoStack.isEmpty()) return
        redoStack.add(_state.value.xBins to _state.value.yBins)
        val (x, y) = undoStack.removeAt(undoStack.size - 1)
        _state.value = _state.value.copy(xBins = x, yBins = y, isModified = true)
    }

    fun redo() {
        if (redoStack.isEmpty()) return
        undoStack.add(_state.value.xBins to _state.value.yBins)
        val (x, y) = redoStack.removeAt(redoStack.size - 1)
        _state.value = _state.value.copy(xBins = x, yBins = y, isModified = true)
    }

    fun canUndo(): Boolean = undoStack.isNotEmpty()
    fun canRedo(): Boolean = redoStack.isNotEmpty()

    private fun pushUndo() {
        undoStack.add(_state.value.xBins to _state.value.yBins)
        redoStack.clear()
        if (undoStack.size > 100) undoStack.removeAt(0)
    }
}
