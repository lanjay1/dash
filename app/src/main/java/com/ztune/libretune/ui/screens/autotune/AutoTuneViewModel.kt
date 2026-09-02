package com.ztune.libretune.ui.screens.autotune

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.ztune.libretune.core.autotune.AutoTuneCellInfo
import com.ztune.libretune.core.autotune.AutoTuneEngine
import com.ztune.libretune.core.autotune.AutoTuneState
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream

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

class AutoTuneViewModel @AssistedInject constructor(
    @Assisted private val tuneSessionId: String,
    private val engine: AutoTuneEngine,
    private val dataStore: com.ztune.libretune.data.DataStoreManager,
) : ViewModel() {

    private val _settings = MutableStateFlow(AutoTuneSettings())
    val settings: StateFlow<AutoTuneSettings> = _settings.asStateFlow()

    private val _uiState = MutableStateFlow(AutoTuneUiState())
    val uiState: StateFlow<AutoTuneUiState> = _uiState.asStateFlow()

    val heatmapData: StateFlow<Map<Pair<Int, Int>, Float>> = engine.state
        .map { state ->
            val mode = _uiState.value.heatmapMode
            state.cellInfo.mapValues { (_, cell) ->
                when (mode) {
                    HeatmapMode.WEIGHTING -> cell.weight
                    HeatmapMode.CHANGE -> cell.proposedChange
                    HeatmapMode.COVERAGE -> cell.hits.toFloat()
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    private val engineState: StateFlow<AutoTuneState> = engine.state
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AutoTuneState())

    init {
        viewModelScope.launch { loadSettings() }
        viewModelScope.launch { observeEngineState() }
    }

    private suspend fun observeEngineState() {
        engineState.collect { state ->
            val cellStatsMap = state.cellInfo.mapValues { (_, cell) ->
                CellStats(
                    hits = cell.hits,
                    proposedChange = cell.proposedChange,
                    currentVe = cell.currentVe,
                    proposedVe = cell.proposedVe,
                    weight = cell.weight,
                    confidence = cell.confidence,
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
            )
        }
    }

    fun startAutoTune() {
        val s = _settings.value
        engine.configure(
            targetAfr = s.targetAfr,
            algorithm = when (s.algorithm) {
                Algorithm.PROPORTIONAL -> com.ztune.libretune.core.autotune.AutoTuneAlgorithm.PROPORTIONAL
                Algorithm.INTEGRAL -> com.ztune.libretune.core.autotune.AutoTuneAlgorithm.INTEGRAL
            },
            maxChangePct = s.maxChangePct,
            maxChangeAbs = s.maxChangeAbs,
            lambdaDelayMs = s.lambdaDelayMs,
            minCellSamples = s.minCellSamples,
            smoothingPasses = s.smoothingPasses,
        )
        engine.start()
        _uiState.value = _uiState.value.copy(isRunning = true)
    }

    fun stopAutoTune() {
        engine.stop()
        _uiState.value = _uiState.value.copy(isRunning = false)
    }

    fun feedSample(rpm: Int, map: Float, clt: Float, tps: Float, lambda: Float, tpsRate: Float) {
        val s = _settings.value
        if (rpm < s.minRpm || rpm > s.maxRpm) return
        if (clt < s.minClt) return
        if (tpsRate > s.maxTpsRate) return
        engine.feedSample(rpm, map, clt, tps, lambda)
    }

    fun sendRecommendations(onComplete: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            val locked = _uiState.value.lockedCells
            val result = engine.buildRecommendations(excludeCells = locked)
            onComplete(result)
            if (result) {
                engine.reset()
                _uiState.value = _uiState.value.copy(
                    recommendationsReady = false,
                    selectedCell = null,
                )
            }
        }
    }

    fun toggleCellLock(rpmBin: Int, loadBin: Int) {
        val key = rpmBin to loadBin
        val current = _uiState.value.lockedCells.toMutableSet()
        if (current.contains(key)) current.remove(key) else current.add(key)
        _uiState.value = _uiState.value.copy(lockedCells = current)
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
        viewModelScope.launch { saveSettings() }
    }

    private suspend fun loadSettings() {
        withContext(Dispatchers.IO) {
            val saved = dataStore.readAutoTuneSettings(tuneSessionId)
            if (saved != null) _settings.value = saved
        }
    }

    private suspend fun saveSettings() {
        withContext(Dispatchers.IO) {
            dataStore.writeAutoTuneSettings(tuneSessionId, _settings.value)
        }
    }

    fun resetAll() {
        stopAutoTune()
        engine.reset()
        _uiState.value = AutoTuneUiState()
        _settings.value = AutoTuneSettings()
        viewModelScope.launch { saveSettings() }
    }

    override fun onCleared() {
        super.onCleared()
        stopAutoTune()
    }

    @AssistedFactory
    interface Factory {
        fun create(tuneSessionId: String): AutoTuneViewModel
    }

    companion object {
        @Suppress("UNCHECKED_CAST")
        fun provideFactory(assistedFactory: Factory, tuneSessionId: String): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    assistedFactory.create(tuneSessionId) as T
            }
    }
}
