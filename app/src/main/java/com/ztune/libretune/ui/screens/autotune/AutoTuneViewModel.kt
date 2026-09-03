package com.ztune.libretune.ui.screens.autotune

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ztune.libretune.core.autotune.AutoTuneController
import com.ztune.libretune.core.autotune.AutoTuneState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class HeatmapMode { WEIGHTING, CHANGE, COVERAGE }

enum class Algorithm(val label: String) { PROPORTIONAL("Proportional"), INTEGRAL("Integral") }

data class AutoTuneSettings(
    val targetAfr: Float = 14.7f,
    val minRpm: Int = 1500,
    val maxRpm: Int = 7500,
    val minClt: Int = 60,
    val maxTpsRate: Float = 80f,
    val minSteadyMs: Long = 200L,
    val maxChangePct: Float = 15f,
    val algorithm: Algorithm = Algorithm.PROPORTIONAL,
    val updateRateMs: Long = 100L,
    val lambdaDelayMs: Long = 50L,
    val minCellSamples: Int = 5,
    val smoothingPasses: Int = 2,
    val maxChangeAbs: Float = 5f,
    val customExpression: String = "",
)

data class CellStats(
    val hits: Int = 0,
    val proposedChange: Float = 0f,
    val currentVe: Float = 0f,
    val proposedVe: Float = 0f,
    val weight: Float = 0f,
    val confidence: Float = 0f,
)

data class AutoTuneUiState(
    val isRunning: Boolean = false,
    val heatmapMode: HeatmapMode = HeatmapMode.WEIGHTING,
    val lockedCells: Set<Pair<Int, Int>> = emptySet(),
    val cellStats: Map<Pair<Int, Int>, CellStats> = emptyMap(),
    val totalSamples: Long = 0L,
    val activeCells: Int = 0,
    val avgCorrection: Float = 0f,
    val recommendationsReady: Boolean = false,
    val selectedCell: Pair<Int, Int>? = null,
)

/**
 * ViewModel for the AutoTune screen.
 *
 * Wraps [AutoTuneController] and exposes UI-friendly state derived from
 * the controller's reactive [AutoTuneState] flow. The controller itself
 * is provided by Hilt as a singleton.
 *
 * NOTE: The full autotune workflow (configure → feed samples → compute
 * result → apply to ECU) requires the active ECU definition and VE table
 * data, which is owned by [com.ztune.libretune.core.TuneManager]. Wiring
 * that up is left as a follow-up task; for now this ViewModel exposes
 * the screen-facing API and basic cell-locking / state propagation.
 */
@HiltViewModel
class AutoTuneViewModel @Inject constructor(
    private val controller: AutoTuneController,
) : ViewModel() {

    private val _settings = MutableStateFlow(AutoTuneSettings())
    val settings: StateFlow<AutoTuneSettings> = _settings.asStateFlow()

    private val _uiState = MutableStateFlow(AutoTuneUiState())
    val uiState: StateFlow<AutoTuneUiState> = _uiState.asStateFlow()

    val heatmapData: StateFlow<Map<Pair<Int, Int>, Float>> = controller.state
        .map { state ->
            val mode = _uiState.value.heatmapMode
            val heatMap = controller.getHeatMap()
            val denom = state.sampleCount.coerceAtLeast(1L).toFloat()
            heatMap.mapValues { (_, cell) ->
                when (mode) {
                    HeatmapMode.WEIGHTING -> cell.sampleCount.toFloat() / denom
                    HeatmapMode.CHANGE -> cell.totalAdjustment.toFloat()
                    HeatmapMode.COVERAGE -> cell.sampleCount.toFloat()
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    private val engineState: StateFlow<AutoTuneState> = controller.state
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AutoTuneState())

    init {
        viewModelScope.launch { observeEngineState() }
    }

    private suspend fun observeEngineState() {
        engineState.collect { state ->
            val heatMap = controller.getHeatMap()
            val cellStatsMap = heatMap.mapValues { (_, cell) ->
                CellStats(
                    hits = cell.sampleCount,
                    proposedChange = cell.totalAdjustment.toFloat(),
                    currentVe = 0f,           // requires VE table data
                    proposedVe = 0f,          // requires VE table data
                    weight = 0f,              // requires weighting model
                    confidence = if (cell.sampleCount > 0) 1f else 0f,
                )
            }
            val totalHits = cellStatsMap.values.sumOf { it.hits.toLong() }
            val activeCount = cellStatsMap.values.count { it.hits >= _settings.value.minCellSamples }
            val avgCorr = if (cellStatsMap.isNotEmpty()) {
                cellStatsMap.values.filter { it.hits >= _settings.value.minCellSamples }
                    .map { it.proposedChange }.average().toFloat()
            } else 0f

            _uiState.value = _uiState.value.copy(
                cellStats = cellStatsMap,
                totalSamples = totalHits,
                activeCells = activeCount,
                avgCorrection = avgCorr,
                recommendationsReady = activeCount > 0,
                isRunning = state.isRunning,
            )
        }
    }

    fun startAutoTune() {
        // TODO: convert AutoTuneSettings → AutoTuneConfig and call controller.configure(config).
        //       Requires VE table name / target AFR table name resolution against active definition.
        _uiState.value = _uiState.value.copy(isRunning = true)
    }

    fun stopAutoTune() {
        _uiState.value = _uiState.value.copy(isRunning = false)
    }

    fun feedSample(rpm: Int, map: Float, clt: Float, tps: Float, lambda: Float, tpsRate: Float) {
        val s = _settings.value
        if (rpm < s.minRpm || rpm > s.maxRpm) return
        if (clt < s.minClt) return
        if (tpsRate > s.maxTpsRate) return
        // TODO: feed the controller once it has been configured; for now this is a no-op.
    }

    fun sendRecommendations(onComplete: (Boolean) -> Unit = {}) {
        // TODO: call controller.computeResult(veTable, rpmBins, loadBins) and write adjustments
        //       back to the ECU via TuneManager. For now we report not-ready.
        onComplete(false)
    }

    fun toggleCellLock(rpmBin: Int, loadBin: Int) {
        val key = rpmBin to loadBin
        val current = _uiState.value.lockedCells.toMutableSet()
        val nowLocked = key !in current
        if (nowLocked) current.add(key) else current.remove(key)
        _uiState.value = _uiState.value.copy(lockedCells = current)
        controller.setCellLocked(rpmBin, loadBin, nowLocked)
    }

    fun selectCell(rpmBin: Int, loadBin: Int) {
        _uiState.value = _uiState.value.copy(selectedCell = rpmBin to loadBin)
    }

    fun clearSelection() {
        _uiState.value = _uiState.value.copy(selectedCell = null)
    }

    fun setHeatmapMode(mode: HeatmapMode) {
        _uiState.value = _uiState.value.copy(heatmapMode = mode)
    }

    fun updateSettings(block: (AutoTuneSettings) -> AutoTuneSettings) {
        _settings.value = block(_settings.value)
    }

    fun resetAll() {
        stopAutoTune()
        controller.reset()
        _uiState.value = AutoTuneUiState()
        _settings.value = AutoTuneSettings()
    }

    override fun onCleared() {
        super.onCleared()
        stopAutoTune()
    }
}
