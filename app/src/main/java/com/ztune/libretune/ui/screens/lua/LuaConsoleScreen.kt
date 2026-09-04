package com.ztune.libretune.ui.screens.lua

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LuaConsoleScreen(
    onBack: () -> Unit,
    vm: LuaConsoleViewModel = hiltViewModel()
) {
    val isDark = isSystemInDarkTheme()
    val outputScrollState = rememberScrollState()

    // Auto-scroll output when new lines arrive
    LaunchedEffect(vm.outputLines.size) {
        outputScrollState.animateScrollTo(outputScrollState.maxValue)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Lua Console") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    // History dropdown
                    Box {
                        IconButton(onClick = vm::toggleHistory) {
                            Icon(Icons.Default.History, contentDescription = "Script history")
                        }
                        DropdownMenu(
                            expanded = vm.showHistory,
                            onDismissRequest = vm::dismissHistory
                        ) {
                            if (vm.scriptHistory.isEmpty()) {
                                DropdownMenuItem(
                                    text = { Text("No history") },
                                    onClick = vm::dismissHistory,
                                    enabled = false
                                )
                            } else {
                                vm.scriptHistory.forEach { script ->
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                text = script.take(60) + if (script.length > 60) "..." else "",
                                                fontFamily = FontFamily.Monospace,
                                                fontSize = 12.sp
                                            )
                                        },
                                        onClick = { vm.selectFromHistory(script) }
                                    )
                                }
                            }
                        }
                    }
                    // Clear output
                    IconButton(onClick = vm::clearOutput) {
                        Icon(Icons.Default.DeleteSweep, contentDescription = "Clear output")
                    }
                }
            )
        },
        bottomBar = {
            BottomBar(vm)
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Output area
            OutputArea(
                lines = vm.outputLines,
                isDark = isDark,
                scrollState = outputScrollState,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun OutputArea(
    lines: List<ConsoleLine>,
    isDark: Boolean,
    scrollState: androidx.compose.foundation.ScrollState,
    modifier: Modifier = Modifier
) {
    val bgColor = if (isDark) Color(0xFF1E1E1E) else Color(0xFFF5F5F5)
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = bgColor,
        tonalElevation = 1.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(12.dp)
        ) {
            if (lines.isEmpty()) {
                Text(
                    text = "-- Lua Console ready.\n-- Type a script below and tap Execute.",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                    color = if (isDark) Color(0xFF666666) else Color(0xFF999999)
                )
            }
            lines.forEach { line ->
                val color = when (line.type) {
                    LineType.OUTPUT -> if (isDark) Color(0xFFCCCCCC) else Color(0xFF333333)
                    LineType.ERROR -> Color(0xFFFF5555)
                    LineType.PRINT -> if (isDark) Color(0xFFFFDD57) else Color(0xFFBF8F00)
                }
                Text(
                    text = line.text,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                    color = color,
                    lineHeight = 18.sp,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@Composable
private fun BottomBar(vm: LuaConsoleViewModel) {
    val bgColor = MaterialTheme.colorScheme.surface
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = bgColor,
        shadowElevation = 8.dp
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // Script input
            OutlinedTextField(
                value = vm.scriptInput,
                onValueChange = vm::updateScript,
                label = { Text("Script") },
                placeholder = {
                    Text(
                        "print(\"Hello from ZTUNE!\")",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp
                    )
                },
                textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 14.sp),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 80.dp, max = 200.dp),
                maxLines = 8
            )
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (vm.isExecuting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "Executing...",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { vm.updateScript("") }) {
                    Text("Clear")
                }
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = vm::executeScript,
                    enabled = vm.scriptInput.isNotBlank() && !vm.isExecuting
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text("Execute")
                }
            }
        }
    }
}