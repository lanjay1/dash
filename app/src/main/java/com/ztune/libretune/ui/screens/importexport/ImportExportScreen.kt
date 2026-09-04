package com.ztune.libretune.ui.screens.importexport

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportExportScreen(onNavigateBack: () -> Unit = {}) {
    var showImportDialog by remember { mutableStateOf(false) }
    var showExportDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Import / Export") },
                navigationIcon = { IconButton(onClick = onNavigateBack) { Icon(Icons.Default.ArrowBack, "Back") } }
            )
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Tune Import", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text("Import tune files from external sources.", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onClick = { showImportDialog = true }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Upload, null); Spacer(Modifier.width(8.dp)); Text("Import Tune File")
            }

            HorizontalDivider()
            Text("Tune Export", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text("Export current tune to file.", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onClick = { showExportDialog = true }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Download, null); Spacer(Modifier.width(8.dp)); Text("Export Current Tune")
            }

            HorizontalDivider()
            Text("Supported Formats", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            FormatItem(".msq", "TunerStudio MSQ project file (XML)")
            FormatItem(".table", "TunerStudio per-table export (XML)")
            FormatItem(".csv", "CSV table data")
            FormatItem(".ztune", "ZTune native tune format (JSON)")
        }
    }

    if (showImportDialog) {
        AlertDialog(
            onDismissRequest = { showImportDialog = false },
            title = { Text("Import Tune") },
            text = { Text("File picker integration will be available when backend is connected. Select a format:\n\n.msq (TunerStudio)\n.table (Per-table)\n.csv (CSV data)") },
            confirmButton = { TextButton(onClick = { showImportDialog = false }) { Text("Browse...") } },
            dismissButton = { TextButton(onClick = { showImportDialog = false }) { Text("Cancel") } }
        )
    }
    if (showExportDialog) {
        AlertDialog(
            onDismissRequest = { showExportDialog = false },
            title = { Text("Export Tune") },
            text = { Text("Choose export format:\n\n.ztune (Native)\n.msq (TunerStudio)\n.csv (Table data)") },
            confirmButton = { TextButton(onClick = { showExportDialog = false }) { Text("Export") } },
            dismissButton = { TextButton(onClick = { showExportDialog = false }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun FormatItem(ext: String, desc: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(ext, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold,
            modifier = Modifier.width(80.dp), color = MaterialTheme.colorScheme.primary)
        Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
