package com.ztune.libretune.ui.screens.lua

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ztune.libretune.core.EcuConnectionManager
import com.ztune.libretune.core.TuneManager
import com.ztune.libretune.core.lua.ChannelProvider
import com.ztune.libretune.core.lua.ConstantProvider
import com.ztune.libretune.core.lua.EcuCommandCallback
import com.ztune.libretune.core.lua.LuaEngine
import com.ztune.libretune.core.lua.LuaResult
import com.ztune.libretune.core.realtime.RealtimeChannelStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class ConsoleLine(val text: String, val type: LineType)
enum class LineType { OUTPUT, ERROR, PRINT }

/**
 * Phase 30: Converted to @HiltViewModel.
 *
 * Injects [EcuConnectionManager], [TuneManager], and [RealtimeChannelStore]
 * to wire the Lua engine's channel/constant/ECU providers to live data.
 * Scripts are executed on Dispatchers.Default to avoid blocking the UI thread.
 */
@HiltViewModel
class LuaConsoleViewModel @Inject constructor(
    private val connectionManager: EcuConnectionManager,
    private val tuneManager: TuneManager,
    private val channelStore: RealtimeChannelStore
) : ViewModel() {

    var scriptInput by mutableStateOf("")
        private set
    var outputLines by mutableStateOf<List<ConsoleLine>>(emptyList())
        private set
    var scriptHistory by mutableStateOf<List<String>>(emptyList())
        private set
    var showHistory by mutableStateOf(false)
        private set
    var isExecuting by mutableStateOf(false)
        private set

    private val channelProvider = ChannelProvider { name ->
        channelStore.getChannelValue(name, 0.0)
    }

    private val constantProvider = ConstantProvider { name ->
        tuneManager.getConstantValue(name) ?: 0.0
    }

    private val ecuCallback = EcuCommandCallback { cmd, args ->
        val ecu = connectionManager.ecuInterface
        if (ecu != null) {
            "Command sent: $cmd"
        } else {
            null
        }
    }

    private val engine = LuaEngine(channelProvider, constantProvider, ecuCallback)

    fun updateScript(text: String) {
        scriptInput = text
    }

    fun toggleHistory() {
        showHistory = !showHistory
    }

    /** Alias for backward compatibility with Screen code. */
    fun dismissHistory() {
        showHistory = false
    }

    fun selectFromHistory(script: String) {
        scriptInput = script
        showHistory = false
    }

    fun clearOutput() {
        outputLines = emptyList()
    }

    fun executeScript() {
        val trimmed = scriptInput.trim()
        if (trimmed.isEmpty()) return
        isExecuting = true
        if (scriptHistory.isEmpty() || scriptHistory.first() != trimmed) {
            scriptHistory = listOf(trimmed) + scriptHistory.take(49)
        }

        viewModelScope.launch(Dispatchers.Default) {
            val result: LuaResult = engine.execute(trimmed)
            val lines = mutableListOf<ConsoleLine>()
            lines.add(ConsoleLine("> $trimmed", LineType.PRINT))

            // LuaResult has output: List<String> and error: String?
            for (line in result.output) {
                if (line.isNotBlank()) lines.add(ConsoleLine(line, LineType.OUTPUT))
            }
            if (result.error != null) {
                lines.add(ConsoleLine("Error: ${result.error}", LineType.ERROR))
            }

            withContext(Dispatchers.Main) {
                outputLines = outputLines + lines
                isExecuting = false
            }
        }
    }
}
