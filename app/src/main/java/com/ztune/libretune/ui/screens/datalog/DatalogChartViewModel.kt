package com.ztune.libretune.ui.screens.datalog

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ztune.libretune.core.datalog.DataLogPlayback
import com.ztune.libretune.core.datalog.DataLogSession
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

data class ChannelStats(
    val min: Float,
    val max: Float,
    val avg: Float,
)

/**
 * UI state for the [DatalogChartScreen].
 *
 * @property channelNames    All channel names parsed from the CSV.
 * @property selectedChannels Currently selected channel set.
 * @property channelSeries    Time-series data per selected channel: `time(s) → value`.
 * @property channelStats     Computed min/max/avg per selected channel.
 * @property duration         Total logged duration in seconds.
 * @property isPlaying        Whether playback is active.
 * @property playbackSpeed    Current playback speed multiplier.
 * @property playbackTime     Current playback cursor time in seconds.
 * @property crosshairTime    Time at the crosshair, or null when hidden.
 * @property crosshairValues  Interpolated values per channel at [crosshairTime].
 * @property viewStart        Left edge of the visible time window.
 * @property viewEnd          Right edge of the visible time window.
 * @property isLoading        Whether the CSV is still being parsed.
 * @property loadError        Error message if loading failed.
 */
data class DatalogChartUiState(
    val channelNames: List<String> = emptyList(),
    val selectedChannels: Set<String> = emptySet(),
    val channelSeries: Map<String, List<Pair<Float, Float>>> = emptyMap(),
    val channelStats: Map<String, ChannelStats> = emptyMap(),
    val duration: Float = 0f,
    val isPlaying: Boolean = false,
    val playbackSpeed: Float = 1f,
    val playbackTime: Float = 0f,
    val crosshairTime: Float? = null,
    val crosshairValues: Map<String, Float> = emptyMap(),
    val viewStart: Float = 0f,
    val viewEnd: Float = 0f,
    val isLoading: Boolean = true,
    val loadError: String? = null,
)

@HiltViewModel
class DatalogChartViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val _uiState = MutableStateFlow(DatalogChartUiState())
    val uiState: StateFlow<DatalogChartUiState> = _uiState.asStateFlow()

    private var playbackJob: Job? = null
    private var allSeries: Map<String, List<Pair<Float, Float>>> = emptyMap()

    init {
        val filePath: String? = savedStateHandle["filePath"]
        if (filePath != null) {
            loadFile(filePath)
        } else {
            _uiState.value = _uiState.value.copy(
                isLoading = false,
                loadError = "No file path provided",
            )
        }
    }

    // -----------------------------------------------------------------------
    // Loading
    // -----------------------------------------------------------------------

    /** Load a [DataLogSession] (uses its [DataLogSession.filePath]). */
    fun loadSession(session: DataLogSession) {
        val path = session.filePath ?: return
        loadFile(path)
    }

    /** Parse the CSV at [filePath] and populate channel data. */
    fun loadFile(filePath: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, loadError = null)
            val playback = DataLogPlayback(File(filePath))
            val result = playback.load()
            if (result.isFailure) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    loadError = result.exceptionOrNull()?.message ?: "Failed to load file",
                )
                return@launch
            }
            val channelNames = playback.getChannelNames()
            val totalSamples = playback.totalSamples()
            if (totalSamples == 0) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    loadError = "File contains no samples",
                )
                return@launch
            }
            val firstTs = playback.getTimestamp(0) ?: 0L
            val lastTs = playback.getTimestamp(totalSamples - 1) ?: firstTs
            val durationSec = ((lastTs - firstTs) / 1000.0f).coerceAtLeast(0.001f)
            val builders = channelNames.associateWith { mutableListOf<Pair<Float, Float>>() }
            for (i in 0 until totalSamples) {
                val ts = playback.getTimestamp(i) ?: continue
                val sample = playback.getSample(i) ?: continue
                val relTime = ((ts - firstTs) / 1000.0f)
                for (ch in channelNames) {
                    val v = sample[ch]
                    if (v != null && !v.isNaN()) {
                        builders[ch]?.add(relTime to v.toFloat())
                    }
                }
            }
            allSeries = builders.mapValues { it.value.toList() }
            _uiState.value = _uiState.value.copy(
                channelNames = channelNames,
                duration = durationSec,
                viewStart = 0f,
                viewEnd = durationSec,
                isLoading = false,
            )
        }
    }

    // -----------------------------------------------------------------------
    // Channel selection
    // -----------------------------------------------------------------------

    fun toggleChannel(name: String) {
        val current = _uiState.value.selectedChannels.toMutableSet()
        if (name in current) current.remove(name) else current.add(name)
        applySelection(current)
    }

    fun selectAllChannels() = applySelection(_uiState.value.channelNames.toSet())

    fun deselectAllChannels() = applySelection(emptySet())

    private fun applySelection(selected: Set<String>) {
        val series = allSeries.filterKeys { it in selected }
        val stats = series.mapValues { (_, data) -> computeStats(data) }
        _uiState.value = _uiState.value.copy(
            selectedChannels = selected,
            channelSeries = series,
            channelStats = stats,
        )
    }

    private fun computeStats(data: List<Pair<Float, Float>>): ChannelStats {
        if (data.isEmpty()) return ChannelStats(0f, 0f, 0f)
        var min = Float.MAX_VALUE
        var max = -Float.MAX_VALUE
        var sum = 0f
        for ((_, v) in data) {
            if (v < min) min = v
            if (v > max) max = v
            sum += v
        }
        return ChannelStats(min = min, max = max, avg = sum / data.size)
    }

    // -----------------------------------------------------------------------
    // Playback
    // -----------------------------------------------------------------------

    fun togglePlayback() {
        if (_uiState.value.isPlaying) stopPlayback() else startPlayback()
    }

    private fun startPlayback() {
        val s = _uiState.value
        if (s.playbackTime >= s.duration) {
            _uiState.value = s.copy(playbackTime = 0f)
        }
        _uiState.value = _uiState.value.copy(isPlaying = true)
        playbackJob = viewModelScope.launch {
            while (true) {
                delay(16L)
                val cur = _uiState.value
                val next = cur.playbackTime + 0.016f * cur.playbackSpeed
                if (next >= cur.duration) {
                    _uiState.value = cur.copy(isPlaying = false, playbackTime = cur.duration)
                    break
                }
                _uiState.value = cur.copy(playbackTime = next)
            }
        }
    }

    private fun stopPlayback() {
        playbackJob?.cancel()
        _uiState.value = _uiState.value.copy(isPlaying = false)
    }

    fun setSpeed(speed: Float) {
        _uiState.value = _uiState.value.copy(playbackSpeed = speed)
    }

    fun seekTo(fraction: Float) {
        _uiState.value = _uiState.value.copy(playbackTime = fraction * _uiState.value.duration)
    }

    // -----------------------------------------------------------------------
    // Crosshair
    // -----------------------------------------------------------------------

    fun updateCrosshair(xFraction: Float) {
        val s = _uiState.value
        val time = s.viewStart + xFraction * (s.viewEnd - s.viewStart)
        val values = mutableMapOf<String, Float>()
        for ((ch, data) in s.channelSeries) {
            val v = interpolateValue(data, time)
            if (v != null) values[ch] = v
        }
        _uiState.value = s.copy(crosshairTime = time, crosshairValues = values)
    }

    fun clearCrosshair() {
        _uiState.value = _uiState.value.copy(
            crosshairTime = null,
            crosshairValues = emptyMap(),
        )
    }

    /** Binary-search + linear interpolation for a value at [time]. */
    private fun interpolateValue(
        data: List<Pair<Float, Float>>,
        time: Float,
    ): Float? {
        if (data.isEmpty()) return null
        val idx = data.binarySearchBy(time) { it.first }
        if (idx >= 0) return data[idx].second
        val ip = -(idx + 1)
        if (ip == 0 || ip >= data.size) return null
        val (t0, v0) = data[ip - 1]
        val (t1, v1) = data[ip]
        return v0 + (v1 - v0) * ((time - t0) / (t1 - t0))
    }

    // -----------------------------------------------------------------------
    // Viewport (zoom / pan)
    // -----------------------------------------------------------------------

    /** Pinch-to-zoom centred on [centerFrac] (0‥1 across the canvas width). */
    fun handleZoom(centerFrac: Float, scale: Float) {
        val s = _uiState.value
        val range = s.viewEnd - s.viewStart
        if (range <= 0f) return
        val center = s.viewStart + centerFrac * range
        val minRange = 0.05f
        val maxRange = s.duration.coerceAtLeast(minRange)
        var newRange = (range / scale).coerceIn(minRange, maxRange)
        var ns = center - centerFrac * newRange
        var ne = center + (1f - centerFrac) * newRange
        if (ns < 0f) { ne -= ns; ns = 0f }
        if (ne > s.duration) { ns -= ne - s.duration; ne = s.duration }
        ns = ns.coerceAtLeast(0f)
        _uiState.value = s.copy(viewStart = ns, viewEnd = ne)
    }

    /** Single-finger pan. Positive [deltaPx] = finger moved right. */
    fun handlePan(deltaPx: Float, canvasWidth: Float) {
        val s = _uiState.value
        val range = s.viewEnd - s.viewStart
        if (range <= 0f || canvasWidth <= 0f) return
        // Grab-and-move: drag right → chart shifts right → earlier time visible
        val delta = -(deltaPx / canvasWidth) * range
        var ns = s.viewStart + delta
        var ne = s.viewEnd + delta
        if (ns < 0f) { ne -= ns; ns = 0f }
        if (ne > s.duration) { ns -= ne - s.duration; ne = s.duration }
        ns = ns.coerceAtLeast(0f)
        _uiState.value = s.copy(viewStart = ns, viewEnd = ne)
    }

    fun resetView() {
        _uiState.value = _uiState.value.copy(
            viewStart = 0f,
            viewEnd = _uiState.value.duration,
        )
    }

    // -----------------------------------------------------------------------
    // Per-channel Y range (auto-scale within the visible window)
    // -----------------------------------------------------------------------

    /** Compute the Y range for [channel] considering only data inside [viewStart]‥[viewEnd]. */
    fun getChannelVisibleRange(
        channel: String,
        viewStart: Float,
        viewEnd: Float,
    ): ClosedFloatingPointRange<Float> {
        val data = allSeries[channel] ?: return 0f..1f
        var mn = Float.MAX_VALUE
        var mx = -Float.MAX_VALUE
        var found = false
        for ((t, v) in data) {
            if (t < viewStart || t > viewEnd) continue
            if (v < mn) mn = v
            if (v > mx) mx = v
            found = true
        }
        if (!found) return 0f..1f
        val pad = (mx - mn).coerceAtLeast(0.01f) * 0.08f
        return (mn - pad)..(mx + pad)
    }

    override fun onCleared() {
        super.onCleared()
        playbackJob?.cancel()
    }
}
