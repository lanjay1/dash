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
    val xOutputChannel: String? = null
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

    fun setPointByDrag(index: Int, newValue: Double) {
        val def = connectionManager.activeDefinition ?: return
        val curve = def.getCurveByNameOrMap(curveName) ?: return
        try {
            tuneManager.updateCurveValue(curveName, index, newValue)
            loadCurve() // reload to reflect change
            _state.update { it.copy(isModified = true) }
        } catch (_: Exception) { }
    }

    fun interpolateSelected() {
        // Simple linear interpolation between neighbors
        val st = _state.value
        val idx = st.selectedPoint
        if (idx < 0 || idx >= st.yBins.size) return
        val left = st.yBins.getOrNull(idx - 1) ?: return
        val right = st.yBins.getOrNull(idx + 1) ?: return
        val mid = (left + right) / 2.0
        setPointByDrag(idx, mid)
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
            setPointByDrag(i, smoothed[i])
        }
    }
}
