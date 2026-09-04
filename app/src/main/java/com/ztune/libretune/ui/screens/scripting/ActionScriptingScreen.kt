package com.ztune.libretune.ui.screens.scripting

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

data class ScriptAction(val name: String, val description: String, val enabled: Boolean = true)
data class ScriptItem(val name: String, val description: String, val actions: List<ScriptAction>)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActionScriptingScreen(onNavigateBack: () -> Unit = {}) {
    var scripts by remember { mutableStateOf(listOf(
        ScriptItem("Smooth VE Table", "Smooth VE table with 3x3 Gaussian kernel", listOf(
            ScriptAction("Open VE Table 1", "Navigate to VE Table 1"),
            ScriptAction("Select All Cells", "Select entire table"),
            ScriptAction("Smooth", "Apply 3x3 Gaussian smoothing")
        )),
        ScriptItem("Scale Ignition +2°", "Add 2 degrees to all ignition cells", listOf(
            ScriptAction("Open Ignition Table 1", "Navigate to ignition table"),
            ScriptAction("Select All", "Select all cells"),
            ScriptAction("Add Offset", "Add +2.0 to selection")
        ))
    )) }
    var selectedScript by remember { mutableStateOf<ScriptItem?>(null) }
    var showNewDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Action Scripting") },
                navigationIcon = { IconButton(onClick = onNavigateBack) { Icon(Icons.Default.ArrowBack, "Back") } },
                actions = { IconButton(onClick = { showNewDialog = true }) { Icon(Icons.Default.Add, "New Script") } }
            )
        }
    ) { padding ->
        if (selectedScript == null) {
            LazyColumn(Modifier.padding(padding).fillMaxSize().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(scripts) { script ->
                    Card(modifier = Modifier.fillMaxWidth(), onClick = { selectedScript = script }) {
                        Column(Modifier.padding(16.dp)) {
                            Text(script.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Text(script.description, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("${script.actions.size} actions", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        } else {
            val script = selectedScript!!
            Column(Modifier.padding(padding).fillMaxSize().padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { selectedScript = null }) { Icon(Icons.Default.ArrowBack, "Back") }
                    Text(script.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.weight(1f))
                    Button(onClick = { /* Mock run */ }) { Text("Run") }
                }
                Text(script.description, style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 8.dp))
                Text("Actions", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(script.actions.withIndex().toList()) { (i, action) ->
                        Card(Modifier.fillMaxWidth()) {
                            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text("${i+1}.", modifier = Modifier.padding(end = 8.dp),
                                    style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                                Column(Modifier.weight(1f)) {
                                    Text(action.name, style = MaterialTheme.typography.bodyMedium)
                                    Text(action.description, style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Switch(checked = action.enabled, onCheckedChange = {})
                            }
                        }
                    }
                }
            }
        }
    }

    if (showNewDialog) {
        AlertDialog(
            onDismissRequest = { showNewDialog = false },
            title = { Text("New Script") },
            text = { OutlinedTextField(value = "", onValueChange = {}, label = { Text("Script Name") }) },
            confirmButton = { TextButton(onClick = { showNewDialog = false }) { Text("Create") } },
            dismissButton = { TextButton(onClick = { showNewDialog = false }) { Text("Cancel") } }
        )
    }
}
