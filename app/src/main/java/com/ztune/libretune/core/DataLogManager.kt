package com.ztune.libretune.core

import android.content.Context
import android.net.Uri
import com.ztune.libretune.core.datalog.DataLogRecorder
import com.ztune.libretune.core.datalog.DataLogSession
import com.ztune.libretune.core.ecu.RealtimeUpdate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream

enum class DataLogRecordingState { IDLE, RECORDING, PAUSED }

class DataLogManager(private val context: Context? = null) {

    private val _recordingState = MutableStateFlow(DataLogRecordingState.IDLE)
    val recordingState: StateFlow<DataLogRecordingState> = _recordingState.asStateFlow()

    private val _sampleCount = MutableStateFlow(0)
    val sampleCount: StateFlow<Int> = _sampleCount.asStateFlow()

    private val _sessions = MutableStateFlow<List<DataLogSession>>(emptyList())
    val sessions: StateFlow<List<DataLogSession>> = _sessions.asStateFlow()

    val selectedChannels = mutableSetOf<String>()

    private var recorder: DataLogRecorder? = null
    private val logDir: File? get() = context?.getExternalFilesDir("datalog")

    init { refreshSessions() }

    private fun refreshSessions() {
        val dir = logDir ?: return
        if (!dir.exists()) dir.mkdirs()
        val list = dir.listFiles()
            ?.filter { it.extension == "csv" }
            ?.mapNotNull { f ->
                DataLogSession(
                    id = f.lastModified(), name = f.nameWithoutExtension,
                    startedAt = f.lastModified(), channelNames = emptyList(),
                    sampleCount = 0, filePath = f.absolutePath
                )
            }
            ?.sortedByDescending { it.startedAt }
            ?: emptyList()
        _sessions.value = list
    }

    fun startRecording(channelNames: List<String> = emptyList()) {
        val ctx = context ?: return
        val rec = DataLogRecorder(ctx)
        val channels = channelNames.ifEmpty { selectedChannels.toList() }
        if (channels.isEmpty()) return
        rec.startRecording(channels)
        recorder = rec
        _recordingState.update { DataLogRecordingState.RECORDING }
        _sampleCount.update { 0 }
    }

    fun stopRecording(): Result<Long> {
        recorder?.stopRecording()
        recorder = null
        _recordingState.update { DataLogRecordingState.IDLE }
        refreshSessions()
        return Result.success(System.currentTimeMillis())
    }

    fun pauseRecording() { _recordingState.update { DataLogRecordingState.PAUSED } }
    fun resumeRecording() { _recordingState.update { DataLogRecordingState.RECORDING } }

    fun onRealtimeUpdate(channelValues: Map<String, Double>) {
        if (_recordingState.value == DataLogRecordingState.RECORDING) {
            recorder?.recordSample(System.currentTimeMillis(), channelValues)
            _sampleCount.update { it + 1 }
        }
    }

    @Deprecated("Use channel-based overload", ReplaceWith("onRealtimeUpdate(channelValues)"))
    fun onRealtimeUpdate(update: RealtimeUpdate) {
        if (_recordingState.value == DataLogRecordingState.RECORDING && update.channelValues.isNotEmpty()) {
            recorder?.recordSample(update.timestamp, update.channelValues)
            _sampleCount.update { it + 1 }
        }
    }

    fun deleteSession(sessionId: Long) {
        val dir = logDir ?: return
        dir.listFiles()?.firstOrNull { it.lastModified() == sessionId }?.delete()
        _sessions.update { list -> list.filter { it.id != sessionId } }
    }

    suspend fun exportSession(sessionId: Long, uri: Uri): Result<Unit> = withContext(Dispatchers.IO) {
        val ctx = context ?: return@withContext Result.failure(IllegalStateException("No context"))
        val dir = logDir ?: return@withContext Result.failure(IllegalStateException("No log dir"))
        val src = dir.listFiles()?.firstOrNull { it.lastModified() == sessionId }
            ?: return@withContext Result.failure(IllegalArgumentException("Session not found"))
        try {
            ctx.contentResolver.openOutputStream(uri)?.use { out ->
                FileInputStream(src).use { inp -> inp.copyTo(out) }
            }
            Result.success(Unit)
        } catch (e: Exception) { Result.failure(e) }
    }

    fun getSessionFilePath(sessionId: Long): String? {
        val dir = logDir ?: return null
        return dir.listFiles()?.firstOrNull { it.lastModified() == sessionId }?.absolutePath
    }

    fun getLastSessionPath(): String? = _sessions.value.firstOrNull()?.id?.let { getSessionFilePath(it) }
}
