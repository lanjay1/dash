package com.ztune.libretune.core.datalog

import android.content.Context
import com.ztune.libretune.core.realtime.RealtimeData
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Records realtime ECU data to a CSV file on device storage.
 *
 * The CSV format is:
 * ```
 * timestamp,channel1,channel2,...
 * 1234567890,12.5,85.0,...
 * ```
 *
 * Thread safety: all public methods are safe to call from any thread.
 * The internal [BufferedWriter] is synchronised on [writerLock].
 *
 * Converted from LibreTune's Rust `datalog::recorder` module.
 */
class DataLogRecorder(private val context: Context) {

    /** Recording lifecycle states. */
    enum class State { IDLE, RECORDING, PAUSED }

    // ---------------------------------------------------------------------
    // Public flows
    // ---------------------------------------------------------------------

    private val _state = MutableStateFlow(State.IDLE)
    val state: StateFlow<State> = _state

    private val _sampleCount = MutableStateFlow(0)
    /** Observable count of samples written so far in the current session. */
    val sampleCount: StateFlow<Int> = _sampleCount

    // ---------------------------------------------------------------------
    // Internal state
    // ---------------------------------------------------------------------

    private var writer: BufferedWriter? = null
    private var file: File? = null
    private var session: DataLogSession? = null
    private var channelNames: List<String> = emptyList()
    private var recordingJob: Job? = null

    /** Lock guarding writes to [writer] so [recordSample] is thread-safe. */
    private val writerLock = Any()

    /** Date format used for auto-generated session names. */
    private val sessionNameFormatter = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US)

    // ---------------------------------------------------------------------
    // Public API
    // ---------------------------------------------------------------------

    /**
     * Start recording to a new CSV file.
     *
     * Creates the CSV file in the app's external-files directory under
     * `datalog/`, writes the header row (`timestamp,channel1,channel2,...`),
     * and transitions to [State.RECORDING].
     *
     * @param channelNames Ordered list of channel names to log. The order is
     *   preserved in the CSV columns.
     * @param sessionName Optional human-readable name; defaults to an
     *   auto-generated timestamp-based name.
     * @return [Result.success] with the new [DataLogSession] metadata, or
     *   [Result.failure] if recording is already active or file I/O fails.
     */
    fun startRecording(channelNames: List<String>, sessionName: String = ""): Result<DataLogSession> {
        synchronized(writerLock) {
            if (_state.value != State.IDLE) {
                return Result.failure(IllegalStateException(
                    "Cannot start recording: current state is ${_state.value}"
                ))
            }

            if (channelNames.isEmpty()) {
                return Result.failure(IllegalArgumentException(
                    "channelNames must not be empty"
                ))
            }

            this.channelNames = channelNames

            // Build output directory
            val logDir = File(context.getExternalFilesDir(null), "datalog")
            if (!logDir.exists() && !logDir.mkdirs()) {
                return Result.failure(IOException("Failed to create datalog directory: $logDir"))
            }

            // Generate file name and path
            val safeName = (sessionName.ifBlank { sessionNameFormatter.format(Date()) })
                .replace(Regex("[^a-zA-Z0-9_-]"), "_")
            val csvFile = File(logDir, "${safeName}.csv")

            // Write CSV header
            try {
                val bw = BufferedWriter(FileWriter(csvFile, false))
                val header = buildString {
                    append(DataLogSession.COLUMN_TIMESTAMP)
                    for (ch in channelNames) {
                        append(',')
                        append(csvEscape(ch))
                    }
                    append('\n')
                }
                bw.write(header)
                bw.flush()
                writer = bw
                file = csvFile
            } catch (e: Exception) {
                return Result.failure(IOException("Failed to create CSV file: ${e.message}", e))
            }

            val now = System.currentTimeMillis()
            val newSession = DataLogSession(
                id = now, // use timestamp as provisional ID
                name = safeName,
                startedAt = now,
                channelNames = channelNames,
                sampleCount = 0,
                filePath = csvFile.absolutePath
            )
            session = newSession
            _sampleCount.value = 0
            _state.value = State.RECORDING

            return Result.success(newSession)
        }
    }

    /**
     * Append a realtime data sample to the CSV file.
     *
     * Only writes when the state is [State.RECORDING].  Channels that are
     * not present in [data.channels] are written as empty strings so that
     * the column alignment is preserved.
     *
     * @param data The decoded realtime snapshot to log.
     */
    fun recordSample(data: RealtimeData) {
        if (_state.value != State.RECORDING) return

        synchronized(writerLock) {
            val w = writer ?: return
            try {
                val row = buildString {
                    append(data.timestamp)
                    for (ch in channelNames) {
                        append(',')
                        val value = data.channels[ch]
                        if (value != null && !value.isNaN()) {
                            append(value)
                        }
                        // else: leave empty between commas
                    }
                    append('\n')
                }
                w.write(row)
                // Flush every 64 samples to balance latency vs I/O
                val count = _sampleCount.value + 1
                _sampleCount.value = count
                if (count % 64 == 0) {
                    w.flush()
                }
            } catch (_: IOException) {
                // I/O error during write – stop recording to avoid further issues
                _state.value = State.IDLE
            }
        }
    }

    /**
     * Convenience: record a raw map of channel values (not from [RealtimeData]).
     *
     * @param timestamp Epoch-millis timestamp for this sample.
     * @param values Channel name → value map.
     */
    fun recordSample(timestamp: Long, values: Map<String, Double>) {
        if (_state.value != State.RECORDING) return

        synchronized(writerLock) {
            val w = writer ?: return
            try {
                val row = buildString {
                    append(timestamp)
                    for (ch in channelNames) {
                        append(',')
                        val value = values[ch]
                        if (value != null && !value.isNaN()) {
                            append(value)
                        }
                    }
                    append('\n')
                }
                w.write(row)
                val count = _sampleCount.value + 1
                _sampleCount.value = count
                if (count % 64 == 0) {
                    w.flush()
                }
            } catch (_: IOException) {
                _state.value = State.IDLE
            }
        }
    }

    /**
     * Pause the current recording.
     *
     * Samples received while paused are silently dropped.  The file remains
     * open and the session can be resumed with [resume].
     */
    fun pause() {
        if (_state.value == State.RECORDING) {
            synchronized(writerLock) {
                try {
                    writer?.flush()
                } catch (_: IOException) { /* best-effort */ }
            }
            _state.value = State.PAUSED
        }
    }

    /**
     * Resume a paused recording.
     */
    fun resume() {
        if (_state.value == State.PAUSED) {
            _state.value = State.RECORDING
        }
    }

    /**
     * Stop recording and finalise the session.
     *
     * Flushes any buffered data, closes the file, and returns the completed
     * [DataLogSession] with the final sample count and end time.
     *
     * @return The completed session, or `null` if no session was active.
     */
    fun stopRecording(): DataLogSession? {
        synchronized(writerLock) {
            if (_state.value == State.IDLE) return null

            try {
                writer?.flush()
                writer?.close()
            } catch (_: IOException) { /* best-effort close */ }

            writer = null
            _state.value = State.IDLE

            val s = session ?: return null
            val completed = s.copy(
                endedAt = System.currentTimeMillis(),
                sampleCount = _sampleCount.value
            )
            session = completed
            return completed
        }
    }

    /**
     * Get the current session metadata, or `null` if not recording.
     */
    fun getCurrentSession(): DataLogSession? {
        synchronized(writerLock) {
            return session
        }
    }

    // ---------------------------------------------------------------------
    // CSV helpers
    // ---------------------------------------------------------------------

    /**
     * Escape a field for CSV output.
     *
     * If the value contains a comma, double-quote, or newline, it is wrapped
     * in double-quotes and interior double-quotes are escaped by doubling them.
     */
    internal fun csvEscape(value: String): String {
        if (value.contains(',') || value.contains('"') || value.contains('\n') || value.contains('\r')) {
            return "\"${value.replace("\"", "\"\""}\""
        }
        return value
    }

    companion object {
        /** Default flush interval (samples). */
        const val FLUSH_INTERVAL = 64
    }
}
