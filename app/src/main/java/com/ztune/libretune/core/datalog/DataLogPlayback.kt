package com.ztune.libretune.core.datalog

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.File
import java.io.FileReader

/**
 * Reads and plays back a CSV datalog file.
 *
 * The CSV is expected to match the format written by [DataLogRecorder]:
 * ```
 * timestamp,channel1,channel2,...
 * 1234567890,12.5,85.0,...
 * ```
 *
 * Playback emulates realtime by emitting samples at intervals derived from
 * the timestamps in the file, scaled by the [speed] multiplier.
 *
 * Converted from LibreTune's Rust `datalog::playback` module.
 */
class DataLogPlayback(private val file: File) {

    /** Playback states. */
    enum class PlaybackState { STOPPED, PLAYING, PAUSED }

    // ---------------------------------------------------------------------
    // Public flows
    // ---------------------------------------------------------------------

    private val _state = MutableStateFlow(PlaybackState.STOPPED)
    val state: StateFlow<PlaybackState> = _state

    private val _position = MutableStateFlow(0)
    val position: StateFlow<Int> = _position

    private val _speed = MutableStateFlow(1.0f)
    val speed: StateFlow<Float> = _speed

    // ---------------------------------------------------------------------
    // Internal state
    // ---------------------------------------------------------------------

    /** All parsed samples, one per CSV data row. */
    private var samples: List<Map<String, Double>> = emptyList()

    /** Ordered channel names (excluding the `timestamp` column). */
    private var channelNames: List<String> = emptyList()

    /** Parsed timestamp for each row, aligned 1:1 with [samples]. */
    private var timestamps: List<Long> = emptyList()

    /** Coroutine job for the active playback loop, cancelled on stop/pause. */
    private var playbackJob: Job? = null

    // ---------------------------------------------------------------------
    // Public API
    // ---------------------------------------------------------------------

    /**
     * Load and parse the CSV file into memory.
     *
     * The first line is treated as a header.  The first column must be
     * `timestamp`; all subsequent columns become channel names.
     *
     * @return [Result.success] when loading completes, or [Result.failure]
     *   with a descriptive error.
     */
    fun load(): Result<Unit> {
        if (!file.exists()) {
            return Result.failure(java.io.FileNotFoundException("Datalog file not found: ${file.absolutePath}"))
        }
        if (!file.canRead()) {
            return Result.failure(java.io.IOException("Cannot read datalog file: ${file.absolutePath}"))
        }

        return try {
            val reader = BufferedReader(FileReader(file))
            val parsedSamples = mutableListOf<Map<String, Double>>()
            val parsedTimestamps = mutableListOf<Long>()

            reader.use { br ->
                // --- Header line ---
                val headerLine = br.readLine()
                    ?: return Result.failure(java.io.IOException("CSV file is empty (no header)"))

                val columns = parseCsvLine(headerLine)
                if (columns.isEmpty()) {
                    return Result.failure(java.io.IOException("CSV header is empty"))
                }
                if (columns.first().lowercase() != DataLogSession.COLUMN_TIMESTAMP) {
                    return Result.failure(java.io.IOException(
                        "CSV header must start with '${DataLogSession.COLUMN_TIMESTAMP}', got '${columns.first()}'"
                    ))
                }

                channelNames = columns.drop(1)

                // --- Data lines ---
                var lineNum = 1
                br.forEachLine { line ->
                    lineNum++
                    val trimmed = line.trim()
                    if (trimmed.isEmpty()) return@forEachLine // skip blank lines

                    val fields = parseCsvLine(trimmed)
                    if (fields.isEmpty()) return@forEachLine

                    // First field is the timestamp
                    val ts = fields[0].toLongOrNull()
                    if (ts == null) {
                        // Malformed timestamp row – skip rather than aborting the entire file
                        return@forEachLine
                    }
                    parsedTimestamps.add(ts)

                    // Remaining fields are channel values
                    val row = mutableMapOf<String, Double>()
                    for (i in channelNames.indices) {
                        val field = fields.getOrElse(i + 1) { "" }
                        row[channelNames[i]] = field.toDoubleOrNull() ?: Double.NaN
                    }
                    parsedSamples.add(row)
                }
            }

            samples = parsedSamples
            timestamps = parsedTimestamps
            _position.value = 0

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(java.io.IOException("Failed to parse datalog CSV: ${e.message}", e))
        }
    }

    /** Total number of samples loaded from the file. */
    fun totalSamples(): Int = samples.size

    /** Ordered list of channel names (excluding the timestamp column). */
    fun getChannelNames(): List<String> = channelNames.toList()

    /**
     * Get the sample at [index] as a `channelName → value` map.
     *
     * @return The sample map, or `null` if [index] is out of bounds.
     */
    fun getSample(index: Int): Map<String, Double>? {
        if (index < 0 || index >= samples.size) return null
        return samples[index]
    }

    /**
     * Get the timestamp (epoch-ms) for sample at [index].
     *
     * @return The timestamp, or `null` if [index] is out of bounds.
     */
    fun getTimestamp(index: Int): Long? {
        if (index < 0 || index >= timestamps.size) return null
        return timestamps[index]
    }

    /**
     * Start playback from the current [position].
     *
     * Samples are emitted to [onSample] at intervals derived from the
     * timestamps in the CSV, scaled by the current [speed].  The callback
     * receives the sample's channel-value map.
     *
     * If playback reaches the end of the file it transitions to
     * [PlaybackState.STOPPED] automatically.
     *
     * @param scope Coroutine scope for the playback loop.  Playback is
     *   automatically cancelled when this scope is cancelled.
     * @param onSample Callback invoked on each sample.  Called on the
     *   coroutine's dispatcher (typically `Dispatchers.Default`).
     */
    fun play(scope: CoroutineScope, onSample: (Map<String, Double>) -> Unit) {
        if (samples.isEmpty()) return
        if (_state.value == PlaybackState.PLAYING) return

        _state.value = PlaybackState.PLAYING
        playbackJob = scope.launchPlayback(onSample)
    }

    /** Pause playback (can be resumed with [play] from the current position). */
    fun pause() {
        if (_state.value != PlaybackState.PLAYING) return
        playbackJob?.cancel()
        playbackJob = null
        _state.value = PlaybackState.PAUSED
    }

    /** Stop playback and reset position to 0. */
    fun stop() {
        playbackJob?.cancel()
        playbackJob = null
        _state.value = PlaybackState.STOPPED
        _position.value = 0
    }

    /**
     * Seek to a specific sample index.
     *
     * Clamped to `[0, totalSamples)`.  If currently playing, the next
     * emitted sample will be from the new position.
     *
     * @param position Zero-based sample index.
     */
    fun seek(position: Int) {
        val clamped = position.coerceIn(0, (samples.size - 1).coerceAtLeast(0))
        _position.value = clamped
    }

    /**
     * Set playback speed multiplier.
     *
     * @param speed Speed factor from 0.25× to 4×.  Values outside this
     *   range are clamped.
     */
    fun setSpeed(speed: Float) {
        _speed.value = speed.coerceIn(MIN_SPEED, MAX_SPEED)
    }

    // ---------------------------------------------------------------------
    // Internal playback loop
    // ---------------------------------------------------------------------

    /**
     * Launch the playback coroutine that emits samples at the correct
     * cadence based on inter-sample timestamp deltas.
     */
    private fun CoroutineScope.launchPlayback(
        onSample: (Map<String, Double>) -> Unit
    ): Job = launch {
        var idx = _position.value
        val speed = _speed.value

        while (isActive && idx < samples.size) {
            onSample(samples[idx])
            _position.value = idx

            // Calculate delay to the next sample based on timestamps
            if (idx + 1 < timestamps.size && idx + 1 < samples.size) {
                val deltaMs = timestamps[idx + 1] - timestamps[idx]
                if (deltaMs > 0) {
                    val delayMs = (deltaMs / speed).toLong().coerceAtLeast(1L)
                    delay(delayMs)
                } else {
                    // Zero-delta or negative – use a minimum 10ms tick
                    delay(10L)
                }
            } else if (idx + 1 >= samples.size) {
                // Last sample – hold briefly so the final value is visible
                delay(100L)
            }

            idx++
        }

        // Reached the end
        _state.value = PlaybackState.STOPPED
    }

    // ---------------------------------------------------------------------
    // CSV parsing utilities
    // ---------------------------------------------------------------------

    /**
     * Parse a single CSV line into a list of unescaped field strings.
     *
     * Handles:
     * - Quoted fields containing commas, newlines (for multi-line values),
     *   and escaped double-quotes (`""`).
     * - Trailing comma producing an empty trailing field.
     */
    internal fun parseCsvLine(line: String): List<String> {
        val fields = mutableListOf<String>()
        val current = StringBuilder()
        var inQuotes = false
        var i = 0

        while (i < line.length) {
            val ch = line[i]

            if (inQuotes) {
                if (ch == '"') {
                    // Could be an escaped quote or end of quoted field
                    if (i + 1 < line.length && line[i + 1] == '"') {
                        current.append('"')
                        i += 2
                        continue
                    } else {
                        inQuotes = false
                        i++
                        continue
                    }
                } else {
                    current.append(ch)
                    i++
                    continue
                }
            }

            // Not in quotes
            when (ch) {
                ',' -> {
                    fields.add(current.toString())
                    current.clear()
                    i++
                }
                '"' -> {
                    inQuotes = true
                    i++
                }
                else -> {
                    current.append(ch)
                    i++
                }
            }
        }

        // Add the last field
        fields.add(current.toString())
        return fields
    }

    companion object {
        /** Minimum playback speed multiplier. */
        const val MIN_SPEED = 0.25f

        /** Maximum playback speed multiplier. */
        const val MAX_SPEED = 4.0f

        /**
         * Export a list of samples back to CSV format as a [String].
         *
         * This is the inverse of the file-loading logic and is useful for
         * sharing or re-serialising a session.
         *
         * @param timestamps Sample timestamps (same length as [samplesList]).
         * @param channelNames Column headers.
         * @param samplesList Rows of channel data.
         * @return The complete CSV text including the header line.
         */
        fun toCsv(
            timestamps: List<Long>,
            channelNames: List<String>,
            samplesList: List<Map<String, Double>>
        ): String {
            val sb = StringBuilder()

            // Header
            sb.append(DataLogSession.COLUMN_TIMESTAMP)
            for (ch in channelNames) {
                sb.append(',')
                sb.append(csvEscapeField(ch))
            }
            sb.append('\n')

            // Data rows
            for (i in timestamps.indices) {
                sb.append(timestamps[i])
                for (ch in channelNames) {
                    sb.append(',')
                    val value = samplesList.getOrNull(i)?.get(ch)
                    if (value != null && !value.isNaN()) {
                        sb.append(value)
                    }
                    // else: leave empty between commas
                }
                sb.append('\n')
            }

            return sb.toString()
        }

        /**
         * Escape a single field for CSV output.
         */
        internal fun csvEscapeField(value: String): String {
            val dq = "\""
            if (value.contains(",") || value.contains(dq) ||
                value.contains("\n") || value.contains("\r")) {
                return dq + value.replace(dq, dq + dq) + dq
            }
            return value
        }
    }
}
