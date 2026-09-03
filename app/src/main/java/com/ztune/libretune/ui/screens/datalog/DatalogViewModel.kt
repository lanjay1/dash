@file:Suppress("unused")

package com.ztune.libretune.ui.screens.datalog

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ztune.libretune.core.DataLogManager
import com.ztune.libretune.core.DataLogRecordingState
import com.ztune.libretune.core.datalog.DataLogSession
import com.ztune.libretune.core.EcuConnectionManager
import com.ztune.libretune.core.EcuConnectionStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * UI state for the Datalog screen.
 *
 * @property isConnected       Whether the ECU is connected.
 * @property recordingState    Current recording state (idle / recording / paused).
 * @property sampleCount       Number of samples recorded in the current session.
 * @property sessions          List of previously recorded sessions.
 * @property availableChannels Channel names the user can select for logging.
 * @property selectedChannels  Currently selected channel names for logging.
 * @property showChannelPicker Whether the channel picker dialog is visible.
 * @property deleteConfirmId   Session ID awaiting delete confirmation, or null.
 * @property toastMessage      Transient message shown in a snackbar.
 */
data class DatalogUiState(
    val isConnected: Boolean = false,
    val recordingState: DataLogRecordingState = DataLogRecordingState.IDLE,
    val sampleCount: Int = 0,
    val sessions: List<DataLogSession> = emptyList(),
    val availableChannels: List<String> = emptyList(),
    val selectedChannels: Set<String> = emptySet(),
    val showChannelPicker: Boolean = false,
    val deleteConfirmId: Long? = null,
    val toastMessage: String? = null
)

/**
 * Well-known output channels used when no ECU definition is loaded
 * (demo / default channel list).
 */
private val DEFAULT_CHANNELS = listOf(
    "rpm", "clt", "iat", "map", "tps", "afr", "batteryVoltage", "ignitionAdv",
    "pulseWidth", "dutyCycle", "egoCorrection", "accEnrich", "ve",
    "boostTarget", "boostDuty", "fuelLoad", "afrTarget"
)

@HiltViewModel
class DatalogViewModel @Inject constructor(
    private val connectionManager: EcuConnectionManager,
    private val dataLogManager: DataLogManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(DatalogUiState())
    val uiState: StateFlow<DatalogUiState> = _uiState.asStateFlow()

    init {
        // Observe connection state.
        viewModelScope.launch {
            connectionManager.state.collect { connState ->
                _uiState.update { prev ->
                    prev.copy(
                        isConnected = connState.status == EcuConnectionStatus.CONNECTED,
                        // Populate channel names from definition when connected.
                        availableChannels = if (connState.status == EcuConnectionStatus.CONNECTED) {
                            connectionManager.activeDefinition?.outputChannels?.keys?.toList()
                                ?: DEFAULT_CHANNELS
                        } else {
                            prev.availableChannels.ifEmpty { DEFAULT_CHANNELS }
                        }
                    )
                }
            }
        }

        // Observe recording state.
        viewModelScope.launch {
            dataLogManager.recordingState.collect { state ->
                _uiState.update { it.copy(recordingState = state) }
            }
        }

        // Observe sample count.
        viewModelScope.launch {
            dataLogManager.sampleCount.collect { count ->
                _uiState.update { it.copy(sampleCount = count) }
            }
        }

        // Observe saved sessions.
        viewModelScope.launch {
            dataLogManager.sessions.collect { sessions ->
                _uiState.update { it.copy(sessions = sessions) }
            }
        }

        // Initialize available channels with defaults.
        _uiState.update { it.copy(availableChannels = DEFAULT_CHANNELS) }
    }

    // ========================================================================
    //  Recording controls
    // ========================================================================

    /** Start a new recording session. */
    fun startRecording() {
        if (!_uiState.value.isConnected) {
            _uiState.update { it.copy(toastMessage = "ECU connection required for recording") }
            return
        }
        dataLogManager.startRecording()
        _uiState.update { it.copy(toastMessage = "Recording started") }
    }

    /** Pause the current recording. */
    fun pauseRecording() {
        dataLogManager.pauseRecording()
        _uiState.update { it.copy(toastMessage = "Recording paused") }
    }

    /** Resume a paused recording. */
    fun resumeRecording() {
        dataLogManager.resumeRecording()
        _uiState.update { it.copy(toastMessage = "Recording resumed") }
    }

    /** Stop the current recording and save the session. */
    fun stopRecording() {
        val result = dataLogManager.stopRecording()
        result.onSuccess {
            _uiState.update { it.copy(toastMessage = "Session saved") }
        }.onFailure { e ->
            _uiState.update { it.copy(toastMessage = "Save failed: ${e.message}") }
        }
    }

    // ========================================================================
    //  Session management
    // ========================================================================

    /** Request delete confirmation for a session. */
    fun requestDeleteSession(sessionId: Long) {
        _uiState.update { it.copy(deleteConfirmId = sessionId) }
    }

    /** Dismiss the delete confirmation dialog. */
    fun dismissDeleteConfirm() {
        _uiState.update { it.copy(deleteConfirmId = null) }
    }

    /** Delete a saved session. */
    fun deleteSession(sessionId: Long) {
        dataLogManager.deleteSession(sessionId)
        _uiState.update { it.copy(deleteConfirmId = null, toastMessage = "Session deleted") }
    }

    // ========================================================================
    //  Channel picker
    // ========================================================================

    /** Show or hide the channel picker dialog. */
    fun toggleChannelPicker() {
        _uiState.update { it.copy(showChannelPicker = !it.showChannelPicker) }
    }

    /** Toggle a channel in/out of the selected set. */
    fun toggleChannel(channelName: String) {
        val current = dataLogManager.selectedChannels.toMutableSet()
        if (channelName in current) {
            current.remove(channelName)
        } else {
            current.add(channelName)
        }
        dataLogManager.selectedChannels.clear()
        dataLogManager.selectedChannels.addAll(current)
        _uiState.update { it.copy(selectedChannels = current.toSet()) }
    }

    /** Select all available channels. */
    fun selectAllChannels() {
        val all = _uiState.value.availableChannels.toSet()
        dataLogManager.selectedChannels.clear()
        dataLogManager.selectedChannels.addAll(all)
        _uiState.update { it.copy(selectedChannels = all) }
    }

    /** Deselect all channels. */
    fun deselectAllChannels() {
        dataLogManager.selectedChannels.clear()
        _uiState.update { it.copy(selectedChannels = emptySet()) }
    }

    // ========================================================================
    //  Misc
    // ========================================================================

    /** Dismiss the toast message. */
    fun dismissToast() {
        _uiState.update { it.copy(toastMessage = null) }
    }
}
