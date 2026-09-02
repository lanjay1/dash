package com.ztune.libretune.ui.screens.diagnostics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ztune.libretune.core.connection.EcuInterface
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

/** A single line displayed in the console output. */
data class ConsoleOutputLine(
    val id: Long,
    val text: String,
)

@HiltViewModel
class EcuConsoleViewModel @Inject constructor(
    private val ecu: EcuInterface,
) : ViewModel() {

    private val _outputLines = MutableStateFlow<List<ConsoleOutputLine>>(emptyList())
    val outputLines: StateFlow<List<ConsoleOutputLine>> = _outputLines.asStateFlow()

    private val commandHistory = ArrayDeque<String>(capacity = 64)
    private var lineIdCounter = 0L

    /**
     * Send a text command to the ECU console.
     *
     * Each line is dispatched via [EcuInterface.sendControllerCommand].
     * The raw response bytes are decoded as a UTF-8 string and appended
     * to the output. The sent command itself is echoed with a ">>" prefix.
     */
    fun sendCommand(cmd: String) {
        viewModelScope.launch {
            echoCommand(cmd)
            commandHistory.addLast(cmd)

            try {
                val response = ecu.sendControllerCommand(
                    code = 0x00,
                    data = cmd.toByteArray(Charsets.UTF_8),
                )
                val text = String(response, Charsets.UTF_8).trim()
                appendOutput(text)
            } catch (e: Exception) {
                appendOutput("Error: ${e.message}")
            }
        }
    }

    fun clearOutput() {
        _outputLines.value = emptyList()
    }

    // ------------------------------------------------------------------
    // Internal helpers
    // ------------------------------------------------------------------

    private fun echoCommand(cmd: String) {
        val ts = timestamp()
        _outputLines.update { it + ConsoleOutputLine(id = nextId(), text = ">> [$ts] $cmd") }
    }

    private fun appendOutput(text: String) {
        for (line in text.lines()) {
            _outputLines.update { it + ConsoleOutputLine(id = nextId(), text = line) }
        }
    }

    private fun nextId(): Long = ++lineIdCounter

    private fun timestamp(): String =
        SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
}
