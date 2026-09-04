package com.ztune.libretune.ui.screens.search

import androidx.compose.foundation.clickable
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

data class SearchResult(val title: String, val subtitle: String, val category: String, val route: String?)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GlobalSearchScreen(
    onNavigateBack: () -> Unit = {},
    onNavigate: (String) -> Unit = {}
) {
    var query by remember { mutableStateOf("") }
    val allResults = remember { listOf(
        SearchResult("VE Table 1", "Fuel/VE table", "Tables", "table_editor/veTable1Tbl"),
        SearchResult("Ignition Table 1", "Spark advance table", "Tables", "table_editor/ignTable1Tbl"),
        SearchResult("AFR Target 1", "Target AFR table", "Tables", "table_editor/afrTable1Tbl"),
        SearchResult("Dashboard", "Real-time gauges", "Navigation", "dashboard"),
        SearchResult("AutoTune", "Automatic VE correction", "Navigation", "autotune"),
        SearchResult("Datalog", "Record ECU data", "Navigation", "datalog"),
        SearchResult("Engine Constants", "Scalar parameters", "Navigation", "engine_constants"),
        SearchResult("Sensor Calibration", "TPS, CLT, IAT, O2", "Navigation", "calibration"),
        SearchResult("ECU Console", "Text command interface", "Navigation", "ecu_console"),
        SearchResult("Performance Calculator", "HP/torque estimation", "Navigation", "performance_calculator"),
        SearchResult("AI Assistant", "Tuning copilot", "Navigation", "ai_assistant"),
        SearchResult("Settings", "App configuration", "Navigation", "settings"),
        SearchResult("RPM", "Engine speed channel", "Channels", null),
        SearchResult("TPS", "Throttle position channel", "Channels", null),
        SearchResult("MAP", "Manifold pressure channel", "Channels", null),
        SearchResult("AFR", "Air-fuel ratio channel", "Channels", null),
        SearchResult("CLT", "Coolant temperature channel", "Channels", null),
    ) }

    val filtered = if (query.isBlank()) allResults
        else allResults.filter { it.title.contains(query, true) || it.subtitle.contains(query, true) || it.category.contains(query, true) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Search") },
                navigationIcon = { IconButton(onClick = onNavigateBack) { Icon(Icons.Default.ArrowBack, "Back") } }
            )
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            OutlinedTextField(
                value = query, onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                placeholder = { Text("Search tables, channels, settings...") },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Default.Clear, "Clear") } },
                singleLine = true
            )
            LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(filtered) { result ->
                    ListItem(
                        headlineContent = { Text(result.title) },
                        supportingContent = { Text(result.subtitle) },
                        leadingContent = { Icon(Icons.Default.Search, null) },
                        trailingContent = { Text(result.category, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant) },
                        modifier = Modifier.clickable {
                            result.route?.let { onNavigate(it) }
                        }
                    )
                }
            }
        }
    }
}
