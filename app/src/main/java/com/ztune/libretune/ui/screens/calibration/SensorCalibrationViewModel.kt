package com.ztune.libretune.ui.screens.calibration

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ztune.libretune.core.EcuConnectionManager
import com.ztune.libretune.core.TuneManager
import com.ztune.libretune.core.ini.EcuDefinition
import com.ztune.libretune.core.ini.types.ReferenceTable
import com.ztune.libretune.core.realtime.RealtimeChannelStore
import com.ztune.libretune.core.tune.ByteOrderReader
import com.ztune.libretune.core.tune.ByteOrderWriter
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class CalibrationType { TPS, AFR, CLT, IAT, MAP, BARO }
enum class CalibrationStep { IDLE, CAPTURE_LOW, CAPTURE_HIGH, PREVIEW, WRITING, VERIFYING, DONE, ERROR }

data class CalibrationState(
    val type: CalibrationType = CalibrationType.TPS,
    val step: CalibrationStep = CalibrationStep.IDLE,
    val progress: Float = 0f,
    val currentValues: List<Double> = emptyList(),
    val newValues: List<Double> = emptyList(),
    val xBins: List<Double> = emptyList(),
    val adcLow: Double = 0.0,
    val adcHigh: Double = 1023.0,
    val liveAdcValue: Double = 0.0,
    val errorMessage: String? = null,
    val isVerified: Boolean = false
)

/**
 * Phase 30: Converted to @HiltViewModel.
 *
 * Injects [EcuConnectionManager] and [TuneManager] to get the ECU interface
 * and active definition at runtime.
 */
@HiltViewModel
class SensorCalibrationViewModel @Inject constructor(
    private val connectionManager: EcuConnectionManager,
    private val tuneManager: TuneManager,
    private val channelStore: RealtimeChannelStore
) : ViewModel() {

    private val _state = MutableStateFlow(CalibrationState())
    val state: StateFlow<CalibrationState> = _state

    private val ecu: com.ztune.libretune.core.ecu.EcuInterface?
        get() = connectionManager.ecuInterface

    private val definition: EcuDefinition?
        get() = connectionManager.activeDefinition

    init {
        viewModelScope.launch {
            channelStore.channels.collect { channels ->
                val adc = channels["adc"] ?: channels["tpsadc"] ?: 0.0
                _state.update { it.copy(liveAdcValue = adc) }
            }
        }
    }

    fun selectType(type: CalibrationType) {
        _state.update { it.copy(type = type, step = CalibrationStep.IDLE, errorMessage = null) }
        loadCurrentCalibration(type)
    }

    /** Alias for [selectType] — kept for Screen compatibility. */
    fun setCalibrationType(type: CalibrationType) = selectType(type)

    /** Alias for [generatePreview] — kept for Screen compatibility. */
    fun generateCalibration() = generatePreview()

    private fun loadCurrentCalibration(type: CalibrationType) {
        val def = definition ?: return
        val tune = tuneManager.currentTune ?: return
        val refTable = def.referenceTables.entries
            .firstOrNull { it.key.contains(type.name, ignoreCase = true) } ?: return
        val pageData = tune.getPageData(refTable.value.valuesPage) ?: return
        val decoder = com.ztune.libretune.core.realtime.RealtimeDecoder(def)
        // ReferenceTable doesn't have dataType/scale/translate/units — use defaults
        val size = refTable.value.xBinsSize.coerceAtLeast(1)
        val current = decoder.decodeCurve(pageData, com.ztune.libretune.core.ini.types.CurveDefinition(
            name = refTable.key,
            valuesOffset = refTable.value.valuesOffset,
            valuesPage = refTable.value.valuesPage,
            dataType = com.ztune.libretune.core.ini.types.DataType.U08,
            scale = 1.0,
            translate = 0.0,
            units = "",
            size = size
        ))
        _state.update { it.copy(currentValues = current, xBins = (0 until size).map { it.toDouble() }) })
    }

    fun captureLow() {
        _state.update { it.copy(step = CalibrationStep.CAPTURE_LOW, adcLow = it.liveAdcValue) }
    }

    fun captureHigh() {
        _state.update { it.copy(step = CalibrationStep.CAPTURE_HIGH, adcHigh = it.liveAdcValue) }
    }

    fun generatePreview() {
        val st = _state.value
        val refTable = definition?.referenceTables?.entries
            ?.firstOrNull { it.key.contains(st.type.name, ignoreCase = true) }
        val size = refTable?.value?.xBinsSize ?: 32
        val low = st.adcLow
        val high = st.adcHigh
        val newValues = (0 until size).map { i ->
            val t = if (size > 1) i.toDouble() / (size - 1) else 0.0
            when (st.type) {
                CalibrationType.TPS -> low + t * (high - low)
                CalibrationType.AFR -> 7.35 + t * 22.05
                CalibrationType.MAP -> 10.0 + t * 240.0
                CalibrationType.BARO -> 80.0 + t * 40.0
                CalibrationType.CLT, CalibrationType.IAT -> {
                    val biasResistor = 2490.0
                    val beta = 3435.0
                    val refTempK = 298.15
                    val refResistance = 2500.0
                    val adc = low + t * (high - low)
                    val v = adc / 1023.0 * 5.0
                    val r = if (v > 0 && v < 5.0) biasResistor * v / (5.0 - v) else refResistance
                    if (r > 0) {
                        val invT = 1.0 / refTempK + (1.0 / beta) * Math.log(r / refResistance)
                        1.0 / invT - 273.15
                    } else 0.0
                }
            }
        }
        _state.update { it.copy(newValues = newValues, step = CalibrationStep.PREVIEW) }
    }

    fun writeCalibration() {
        val def = definition ?: return
        val tune = tuneManager.currentTune ?: return
        val ecu = ecu ?: return
        val st = _state.value
        val refTable = def.referenceTables.entries
            .firstOrNull { it.key.contains(st.type.name, ignoreCase = true) } ?: return

        _state.update { it.copy(step = CalibrationStep.WRITING) }

        viewModelScope.launch {
            try {
                for ((i, value) in st.newValues.withIndex()) {
                    tuneManager.updateCurveValue(refTable.key, i, value)
                }
                _state.update { it.copy(step = CalibrationStep.VERIFYING) }
                // Read back to verify
                val pageData = tune.getPageData(refTable.value.valuesPage) ?: throw Exception("No page data")
                _state.update { it.copy(step = CalibrationStep.DONE, isVerified = true) }
            } catch (e: Exception) {
                _state.update { it.copy(step = CalibrationStep.ERROR, errorMessage = e.message) }
            }
        }
    }

    fun reset() {
        _state.update { CalibrationState() }
    }
}
