package com.ztune.libretune.ui.screens.plugin

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

data class PluginInfo(val name: String, val version: String, val description: String,
    val enabled: Boolean, val permissions: List<String>)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PluginManagerScreen(onNavigateBack: () -> Unit = {}) {
    val plugins = remember { listOf(
        PluginInfo("VE Analyzer Pro", "1.2.0", "Advanced VE table analysis with statistical models", true, listOf("ReadTables", "ReadRealtime")),
        PluginInfo("Datalog Export", "0.9.1", "Export datalogs to multiple formats", true, listOf("ReadDatalogs")),
        PluginInfo("Custom Gauge Pack", "2.0.0", "Additional gauge types and styles", false, listOf("SubscribeChannels")),
        PluginInfo("Tune Comparator", "1.0.0", "Compare tunes side by side with diff view", false, listOf("ReadTables", "ReadConstants"))
    ) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Plugin Manager") },
                navigationIcon = { IconButton(onClick = onNavigateBack) { Icon(Icons.Default.ArrowBack, "Back") } }
            )
        }
    ) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(plugins) { plugin ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Extension, null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(plugin.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                Text("v${plugin.version}", style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Switch(checked = plugin.enabled, onCheckedChange = {})
                        }
                        Text(plugin.description, style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(top = 8.dp))
                        Text("Permissions: ${plugin.permissions.joinToString(", ")}",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp))
                    }
                }
            }
        }
    }
}
