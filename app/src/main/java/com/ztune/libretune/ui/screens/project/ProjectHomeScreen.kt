package com.ztune.libretune.ui.screens.project

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

data class ProjectInfo(val name: String, val ecuType: String, val signature: String,
    val tuneName: String, val lastModified: String, val isModified: Boolean)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectHomeScreen(onNavigateBack: () -> Unit = {}) {
    val recentProjects = remember { listOf(
        ProjectInfo("My Speeduino", "SPEEDUINO", "Speeduino 202401", "Daily Driver Tune", "2024-09-01 14:30", false),
        ProjectInfo("Race Car MS3", "MEGASQUIRT", "Megasquirt-Extra 3.1.x", "Track Day V2", "2024-08-28 09:15", true),
        ProjectInfo("rusEFI Test", "RUSEFI", "rusEFI 2024", "Baseline", "2024-08-15 16:00", false),
    ) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Projects") },
                navigationIcon = { IconButton(onClick = onNavigateBack) { Icon(Icons.Default.ArrowBack, "Back") } },
                actions = { IconButton(onClick = {}) { Icon(Icons.Default.Add, "New Project") } }
            )
        }
    ) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(recentProjects) { project ->
                Card(modifier = Modifier.fillMaxWidth(), onClick = {}) {
                    Column(Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(project.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                                modifier = Modifier.weight(1f))
                            if (project.isModified) {
                                Text("Modified", color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        Text("ECU: ${project.ecuType}", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("Tune: ${project.tuneName}", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("Last: ${project.lastModified}", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(Modifier.padding(top = 8.dp)) {
                            TextButton(onClick = {}) { Text("Open") }
                            TextButton(onClick = {}) { Text("Duplicate") }
                            TextButton(onClick = {}) { Text("Delete") }
                        }
                    }
                }
            }
        }
    }
}
