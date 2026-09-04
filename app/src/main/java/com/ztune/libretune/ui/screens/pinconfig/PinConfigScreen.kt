package com.ztune.libretune.ui.screens.pinconfig

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ztune.libretune.core.safety.PinConflict
import com.ztune.libretune.core.safety.PinConflictReport

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PinConfigScreen(onNavigateBack: () -> Unit = {}) {
    // Mock pin assignments
    val pinAssignments = remember { listOf(
        PinAssignment("PA0", "Fuel Pump", "Output"),
        PinAssignment("PA1", "Fan 1", "Output"),
        PinAssignment("PA2", "Tachometer", "Output"),
        PinAssignment("PB0", "CLT Sensor", "Analog Input"),
        PinAssignment("PB1", "IAT Sensor", "Analog Input"),
        PinAssignment("PB2", "TPS Sensor", "Analog Input"),
        PinAssignment("PB3", "MAP Sensor", "Analog Input"),
        PinAssignment("PB4", "O2 Sensor", "Analog Input"),
        PinAssignment("PC0", "Injector 1", "Output"),
        PinAssignment("PC1", "Injector 2", "Output"),
        PinAssignment("PC2", "Injector 3", "Output"),
        PinAssignment("PC3", "Injector 4", "Output"),
        PinAssignment("PD0", "Coil 1", "Output"),
        PinAssignment("PD1", "Coil 2", "Output"),
        PinAssignment("PD2", "Coil 3", "Output"),
        PinAssignment("PD3", "Coil 4", "Output"),
    ) }

    val mockConflicts = remember { listOf(
        PinConflict("PA0", listOf("fuelPumpPin", "gppwm1_pin"))
    ) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Pin Configuration") },
                navigationIcon = { IconButton(onClick = onNavigateBack) { Icon(Icons.Default.ArrowBack, "Back") } }
            )
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().padding(16.dp)) {
            // Conflict warnings
            if (mockConflicts.isNotEmpty()) {
                mockConflicts.forEach { conflict ->
                    Card(colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer
                    ), modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Warning, null)
                            Spacer(Modifier.width(8.dp))
                            Text(conflict.description, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }

            Text("Pin Assignments", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 8.dp))

            LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(pinAssignments) { pin ->
                    Card(Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(pin.pin, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold,
                                modifier = Modifier.width(60.dp), color = MaterialTheme.colorScheme.primary)
                            Column(Modifier.weight(1f)) {
                                Text(pin.function, style = MaterialTheme.typography.bodyMedium)
                                Text(pin.type, style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    }
}

private data class PinAssignment(val pin: String, val function: String, val type: String)
