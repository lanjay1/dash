package com.ztune.libretune.ui.screens.calibration

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ztune.libretune.core.ecu.EcuInterface
import com.ztune.libretune.core.ini.EcuDefinition
import com.ztune.libretune.core.ini.types.ReferenceTable
import com.ztune.libretune.core.realtime.RealtimeChannelStore
import com.ztune.libretune.core.tune.ByteOrderReader
import com.ztune.libretune.core.tune.ByteOrderWriter
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

enum class CalibrationType {
    TPS, AFR, CLT, IAT, MAP, BARO
}

enum class CalibrationStep {
    IDLE, CAPTURE_LOW, CAPTURE_HIGH, PREVIEW, WRITING, VERIFYING, DONE, ERROR
}

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

class SensorCalibrationViewModel(
    private val ecu: EcuInterface?,
    private val definition: EcuDefinition?,
    private val channelStore: RealtimeChannelStore?
) : ViewModel() {

    private val _state = MutableStateFlow(CalibrationState())
    val state: StateFlow<CalibrationState> = _state

    private var refTable: ReferenceTable? = null

    init {
        observeLiveAdc()
    }

    private fun observeLiveAdc() {
        val store = channelStore ?: return
        viewModelScope.launch {
            store.channels.collect { channels ->
                val adcChannel = when (_state.value.type) {
                    CalibrationType.TPS -> channels["tpsADC"] ?: channels["TPSADC"] ?: channels["adcCh1"]
                    CalibrationType.CLT -> channels["cltADC"] ?: channels["CLTADC"]
                    CalibrationType.IAT -> channels["iatADC"] ?: channels["IATADC"]
                    CalibrationType.MAP -> channels["mapADC"] ?: channels["MAPADC"]
                    CalibrationType.AFR -> channels["o2ADC"] ?: channels["O2ADC"]
                    CalibrationType.BARO -> channels["baroADC"] ?: channels["BAROADC"]
                } ?: return@collect

                _state.value = _state.value.copy(liveAdcValue = adcChannel)
            }
        }
    }

    fun setCalibrationType(type: CalibrationType) {
        refTable = definition?.referenceTables?.entries?.firstOrNull {
            it.key.contains(type.name, ignoreCase = true)
        }?.value
        _state.value = CalibrationState(type = type)
    }

    fun captureLow() {
        val ecu_ = ecu ?: return
        _state.value = _state.value.copy(
            step = CalibrationStep.CAPTURE_LOW,
            adcLow = _state.value.liveAdcValue
        )
    }

    fun captureHigh() {
        _state.value = _state.value.copy(
            step = CalibrationStep.CAPTURE_HIGH,
            adcHigh = _state.value.liveAdcValue
        )
    }

    fun generateCalibration() {
        val st = _state.value
        val low = st.adcLow
        val high = st.adcHigh
        val refSize = refTable?.valuesSize ?: 32

        val values = when (st.type) {
            CalibrationType.TPS -> (0 until refSize).map { i ->
                val t = i.toDouble() / (refSize - 1)
                low + t * (high - low)
            }
            CalibrationType.AFR, CalibrationType.MAP, CalibrationType.BARO -> (0 until refSize).map { i ->
                val t = i.toDouble() / (refSize - 1)
                when (st.type) {
                    CalibrationType.AFR -> 7.35 + t * 22.05  // 7.35:1 to 29.4:1
                    CalibrationType.MAP -> 10.0 + t * 240.0  // 10-250 kPa
                    CalibrationType.BARO -> 80.0 + t * 40.0  // 80-120 kPa
                    else -> 0.0
                }
            }
            CalibrationType.CLT, CalibrationType.IAT -> generateThermistorCurve(low, high, refSize)
        }

        _state.value = _state.value.copy(
            step = CalibrationStep.PREVIEW,
            newValues = values
        )
    }

    private fun generateThermistorCurve(adcLow: Double, adcHigh: Double, size: Int): List<Double> {
        // Steinhart-Hart simplified: linear interpolation as base, apply inverse
        val biasResistor = 2490.0 // Typical GM bias resistor
        val refAdc = (0 until size).map { i ->
            adcLow + (adcHigh - adcLow) * i / (size - 1)
        }
        return refAdc.map { adc ->
            if (adc < 1.0) 150.0
            else {
                val resistance = biasResistor * (1023.0 / adc - 1.0)
                // Simplified Steinhart-Hart for NTC thermistor (beta = 3435)
                val beta = 3435.0
                val refTempK = 298.15 // 25C in Kelvin
                val refResistance = 2500.0
                val tempK = 1.0 / (1.0 / refTempK + kotlin.math.ln(resistance / refResistance) / beta)
                tempK - 273.15
            }
        }
    }

    fun writeCalibration() {
        val ecu_ = ecu ?: return
        val rt = refTable ?: return
        val st = _state.value

        _state.value = st.copy(step = CalibrationStep.WRITING, progress = 0f)

        viewModelScope.launch {
            try {
                val pageData = ecu_.readBlock(
                    rt.valuesPage, 0,
                    definition?.pageSizes?.get(rt.valuesPage)?.toInt() ?: 4096
                ).getOrThrow()

                val writer = ByteOrderWriter(
                    pageData.size,
                    definition?.endianness ?: com.ztune.libretune.core.ini.types.Endianness.DEFAULT
                )

                // Write calibration values
                for ((i, val_) in st.newValues.withIndex()) {
                    val offset = rt.valuesOffset + i * 2 // Assume U16
                    val rawVal = (val_ * 10).toInt().toShort()
                    writer.setPosition(offset)
                    writer.writeValue(com.ztune.libretune.core.ini.types.DataType.U16, rawVal.toDouble())
                }

                val newData = writer.toByteArray()
                ecu_.writeBlock(rt.valuesPage, 0, newData).getOrThrow()

                _state.value = _state.value.copy(progress = 0.5f)

                // Burn
                ecu_.burnPage(rt.valuesPage).getOrThrow()

                _state.value = _state.value.copy(progress = 0.8f)

                // Verify
                val verifyData = ecu_.readBlock(rt.valuesPage, 0, newData.size).getOrThrow()
                val verified = verifyData.contentEquals(newData)

                _state.value = _state.value.copy(
                    step = if (verified) CalibrationStep.DONE else CalibrationStep.ERROR,
                    progress = 1f,
                    isVerified = verified,
                    errorMessage = if (verified) null else "Verification failed: written data does not match"
                )
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    step = CalibrationStep.ERROR,
                    errorMessage = e.message
                )
            }
        }
    }

    fun reset() {
        _state.value = CalibrationState(type = _state.value.type)
    }
}
