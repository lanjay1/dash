package com.ztune.libretune.ui.screens.lua

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import com.ztune.libretune.core.lua.ChannelProvider
import com.ztune.libretune.core.lua.ConstantProvider
import com.ztune.libretune.core.lua.EcuCommandCallback
import com.ztune.libretune.core.lua.LuaEngine
import com.ztune.libretune.core.lua.LuaResult

data class ConsoleLine(val text: String, val type: LineType)

enum class LineType { OUTPUT, ERROR, PRINT }

class LuaConsoleViewModel(
    private val channelProvider: ChannelProvider? = null,
    private val constantProvider: ConstantProvider? = null,
    private val ecuCallback: EcuCommandCallback? = null
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

    private val engine = LuaEngine(channelProvider, constantProvider, ecuCallback)

    fun updateScript(text: String) {
        scriptInput = text
    }

    fun executeScript() {
        val trimmed = scriptInput.trim()
        if (trimmed.isEmpty()) return
        isExecuting = true
        val result: LuaResult = engine.execute(trimmed)
        val newLines = mutableListOf<ConsoleLine>()
        // Print output lines from LuaEngine — they come from print() calls
        for (line in result.output) {
            val type = when {
                line.startsWith("Error:") -> LineType.ERROR
                else -> LineType.PRINT
            }
            newLines.add(ConsoleLine(line, type))
        }
        // If no print output and no error, show the result
        if (newLines.isEmpty() && result.error == null) {
            newLines.add(ConsoleLine("(executed successfully, no output)", LineType.OUTPUT))
        }
        // If there was an error not captured in output
        if (result.error != null && newLines.none { it.type == LineType.ERROR }) {
            newLines.add(ConsoleLine("Error: ${result.error}", LineType.OUTPUT))
        }
        outputLines = outputLines + newLines
        // Add to script history
        val updated = (listOf(trimmed) + scriptHistory.filter { it != trimmed }).take(50)
        scriptHistory = updated
        isExecuting = false
    }

    fun clearOutput() {
        outputLines = emptyList()
    }

    fun selectFromHistory(script: String) {
        scriptInput = script
        showHistory = false
    }

    fun toggleHistory() {
        showHistory = !showHistory
    }

    fun dismissHistory() {
        showHistory = false
    }
}