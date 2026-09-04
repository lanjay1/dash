package com.ztune.libretune.ui.screens.ai_assistant

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

data class ChatMessage(val role: String, val content: String, val isUser: Boolean)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiAssistantScreen(onNavigateBack: () -> Unit = {}) {
    var messages by remember { mutableStateOf(listOf(
        ChatMessage("assistant", "Hello! I'm your ZTune AI Assistant. I can analyze your tune, suggest improvements, and help diagnose issues. What would you like to know?", false)
    )) }
    var input by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("AI Assistant") },
                navigationIcon = { IconButton(onClick = onNavigateBack) { Icon(Icons.Default.ArrowBack, "Back") } }
            )
        },
        bottomBar = {
            Surface(tonalElevation = 2.dp) {
                Row(Modifier.padding(8.dp).fillMaxWidth().imePadding(), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = input, onValueChange = { input = it },
                        modifier = Modifier.weight(1f), placeholder = { Text("Ask about your tune...") },
                        maxLines = 3, enabled = !isLoading
                    )
                    Spacer(Modifier.width(8.dp))
                    IconButton(onClick = {
                        if (input.isNotBlank()) {
                            messages = messages + ChatMessage("user", input, true)
                            val q = input; input = ""; isLoading = true
                            // Mock AI response
                            messages = messages + ChatMessage("assistant",
                                "I'm analyzing your question about '$q'. In a full implementation, I would read your tables, realtime data, and datalogs to provide specific recommendations. Currently running in stub mode — configure an API key in Settings to enable AI responses.", false)
                            isLoading = false
                        }
                    }, enabled = !isLoading && input.isNotBlank()) {
                        if (isLoading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        else Icon(Icons.Default.Send, "Send")
                    }
                }
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxSize().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(messages) { msg ->
                MessageBubble(msg)
            }
        }
    }
}

@Composable
private fun MessageBubble(msg: ChatMessage) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (msg.isUser) Arrangement.End else Arrangement.Start
    ) {
        if (!msg.isUser) {
            Icon(Icons.Default.SmartToy, null, modifier = Modifier.size(32.dp).padding(end = 8.dp),
                tint = MaterialTheme.colorScheme.primary)
        }
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = if (msg.isUser) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.weight(0.85f, fill = false)
        ) {
            Text(msg.content, modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium)
        }
    }
}
