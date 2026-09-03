package com.ztune.libretune.ui.screens.diagnostics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ztune.libretune.core.EcuConnectionManager
import com.ztune.libretune.core.ecu.EcuInterface
import com.ztune.libretune.core.ecu.ToothLoggerTransport
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** UI-visible statistics computed from the captured tooth pattern. */
data class ToothPatternStats(
    val toothCount: Int = 0,
    val rpm: Float = 0f,
    val missingTeethCount: Int = 0,
    val gapRatio: Float = 0f,
)

data class ToothLoggerState(
    val isCapturing: Boolean = false,
    val events: List<ToothLoggerTransport.ToothEvent> = emptyList(),
    val stats: ToothPatternStats = ToothPatternStats(),
    val error: String? = null,
)

@HiltViewModel
class ToothLoggerViewModel @Inject constructor(
    private val connectionManager: EcuConnectionManager,
) : ViewModel() {

    private val _state = MutableStateFlow(ToothLoggerState())
    val state: StateFlow<ToothLoggerState> = _state.asStateFlow()

    // ------------------------------------------------------------------
    // Public actions
    // ------------------------------------------------------------------

    fun startCapture() {
        viewModelScope.launch {
            val ecu: EcuInterface = connectionManager.ecuInterface ?: run {
                _state.update { it.copy(error = "ECU not connected") }
                return@launch
            }
            val transport = ToothLoggerTransport(ecu)
            _state.update { it.copy(isCapturing = true, error = null) }
            try {
                transport.startCapture()
            } catch (e: Exception) {
                _state.update { it.copy(isCapturing = false, error = e.message) }
            }
        }
    }

    fun stopCapture() {
        viewModelScope.launch {
            val ecu: EcuInterface = connectionManager.ecuInterface ?: run {
                _state.update { it.copy(error = "ECU not connected") }
                return@launch
            }
            val transport = ToothLoggerTransport(ecu)
            try {
                val events = transport.stopCapture()
                val stats = analyzePattern(events)
                _state.update {
                    it.copy(
                        isCapturing = false,
                        events = events,
                        stats = stats,
                        error = null,
                    )
                }
            } catch (e: Exception) {
                _state.update { it.copy(isCapturing = false, error = e.message) }
            }
        }
    }

    fun clearError() {
        _state.update { it.copy(error = null) }
    }

    // ------------------------------------------------------------------
    // Pattern analysis
    // ------------------------------------------------------------------

    /**
     * Derive RPM, missing-tooth count and gap ratio from raw events.
     *
     * - RPM is calculated from the primary-tooth period spanning one full
     *   crank revolution (360 °).  We look for the largest consistent gap
     *   and treat everything between two such gaps as one revolution.
     * - Missing teeth are detected when the time delta between consecutive
     *   primary rising edges exceeds 1.8 × the median delta.
     * - Gap ratio = (largest primary gap) / (smallest primary gap).
     */
    private fun analyzePattern(events: List<ToothLoggerTransport.ToothEvent>): ToothPatternStats {
        val primaryEdges = events.filter { it.isPrimary }
        if (primaryEdges.size < 4) return ToothPatternStats(toothCount = events.size)

        // Compute deltas between consecutive primary edges
        val deltas = primaryEdges.zipWithNext { a, b ->
            (b.timeUs - a.timeUs).toFloat()
        }

        val median = deltas.sorted().let { it[it.size / 2] }
        val missingThreshold = median * 1.8f

        // Count missing teeth: each gap > threshold counts as (gap/median - 1) teeth
        var missingCount = 0
        var largestGap = 0f
        var smallestGap = Float.MAX_VALUE
        for (d in deltas) {
            if (d > missingThreshold) {
                missingCount += (d / median).toInt().coerceAtLeast(1)
            }
            if (d > largestGap) largestGap = d
            if (d < smallestGap) smallestGap = d
        }

        // RPM from one revolution: find the total period between two largest gaps
        val largestIndices = deltas.mapIndexedNotNull { i, d ->
            if (d > missingThreshold) i else null
        }
        val rpm = if (largestIndices.size >= 2) {
            val idx1 = largestIndices.first()
            val idx2 = largestIndices[1]
            val revPeriodUs = deltas.subList(idx1, idx2).sum()
            60_000_000f / revPeriodUs
        } else {
            // Fallback: use average delta × estimated teeth per rev
            val avgDelta = deltas.average().toFloat()
            60_000_000f / (avgDelta * deltas.size)
        }

        val gapRatio = if (smallestGap > 0f) largestGap / smallestGap else 0f

        return ToothPatternStats(
            toothCount = events.size,
            rpm = rpm,
            missingTeethCount = missingCount,
            gapRatio = gapRatio,
        )
    }
}
