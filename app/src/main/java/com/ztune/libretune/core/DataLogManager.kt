package com.ztune.libretune.core

import com.ztune.libretune.core.ecu.RealtimeUpdate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Recording state of the datalog session.
 */
enum class DataLogRecordingState {
    IDLE,
    RECORDING,
    PAUSED
}

/**
 * A single recorded datalog session.
 */
data class DatalogSession(
    val id: Long,
    val startTimeMs: Long,
    val endTimeMs: Long,
    val sampleCount: Int,
    val channelNames: List<String>
)

/**
 * Manages datalog recording, playback, storage, and CSV export.
 *
 * Stub — will be expanded with:
 * - Real-time sample collection from [EcuConnectionManager]
 * - Room DAO persistence
 * - CSV export via SAF
 * - Session management (list / open / delete)
 */
class DataLogManager {

    private val _recordingState = MutableStateFlow(DataLogRecordingState.IDLE)
    val recordingState: StateFlow<DataLogRecordingState> = _recordingState.asStateFlow()

    private val _sampleCount = MutableStateFlow(0)
    val sampleCount: StateFlow<Int> = _sampleCount.asStateFlow()

    private val _sessions = MutableStateFlow<List<DatalogSession>>(emptyList())
    val sessions: StateFlow<List<DatalogSession>> = _sessions.asStateFlow()

    /** Selected channel names for the current recording. */
    val selectedChannels = mutableSetOf<String>()

    /** Start recording real-time data. */
    fun startRecording() {
        _recordingState.update { DataLogRecordingState.RECORDING }
        _sampleCount.update { 0 }
    }

    /** Stop the current recording and persist the session. */
    fun stopRecording(): Result<Long> {
        _recordingState.update { DataLogRecordingState.IDLE }
        return Result.success(System.currentTimeMillis())
    }

    /** Pause the current recording (keep in memory, stop appending). */
    fun pauseRecording() {
        _recordingState.update { DataLogRecordingState.PAUSED }
    }

    /** Resume a paused recording. */
    fun resumeRecording() {
        _recordingState.update { DataLogRecordingState.RECORDING }
    }

    /**
     * Ingest a real-time update from the ECU.
     *
     * Stub — real implementation will decode channels and append to buffer.
     */
    fun onRealtimeUpdate(update: RealtimeUpdate) {
        if (_recordingState.value == DataLogRecordingState.RECORDING) {
            _sampleCount.update { it + 1 }
        }
    }

    /** Delete a saved session by ID. */
    fun deleteSession(sessionId: Long) {
        _sessions.update { list -> list.filter { it.id != sessionId } }
    }

    /** Export a session to CSV. Stub. */
    fun exportToCsv(sessionId: Long): Result<String> {
        TODO("DataLogManager.exportToCsv() — not yet implemented")
    }
}