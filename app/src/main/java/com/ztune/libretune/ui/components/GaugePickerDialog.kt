package com.ztune.libretune.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.*
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ztune.libretune.core.dash.GaugeWidgetConfig
import com.ztune.libretune.core.dash.GaugeWidgetType

/**
 * Dialog for adding a new gauge to the dashboard.
 * Shows available channel names and gauge types.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GaugePickerDialog(
    availableChannels: List<String>,
    onAdd: (GaugeWidgetConfig) -> Unit,
    onDismiss: () -> Unit
) {
    var selectedChannel by remember { mutableStateOf(availableChannels.firstOrNull() ?: "") }
    var selectedType by remember { mutableStateOf(GaugeWidgetType.ANALOG_SWEEP) }
    var label by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Gauge") },
        text = {
            Column(Modifier.fillMaxWidth()) {
                Text("Channel", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                DropdownMenuBox(
                    options = availableChannels,
                    selected = selectedChannel,
                    onSelected = { selectedChannel = it },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(12.dp))
                Text("Gauge Type", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                GaugeTypeSelector(selected = selectedType, onSelect = { selectedType = it })
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = label, onValueChange = { label = it },
                    label = { Text("Label (optional)") },
                    modifier = Modifier.fillMaxWidth(), singleLine = true
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onAdd(GaugeWidgetConfig(
                        id = "gauge_${System.currentTimeMillis()}",
                        channelName = selectedChannel,
                        label = label.ifEmpty { selectedChannel },
                        type = selectedType
                    ))
                    onDismiss()
                },
                enabled = selectedChannel.isNotEmpty()
            ) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GaugeTypeSelector(
    selected: GaugeWidgetType,
    onSelect: (GaugeWidgetType) -> Unit
) {
    val types = listOf(
        GaugeWidgetType.ANALOG_SWEEP to "Analog Sweep",
        GaugeWidgetType.DIGITAL_LARGE to "Digital Large",
        GaugeWidgetType.DIGITAL_COMPACT to "Digital Compact",
        GaugeWidgetType.BAR_HORIZONTAL to "Bar Horizontal",
        GaugeWidgetType.BAR_VERTICAL to "Bar Vertical",
        GaugeWidgetType.INDICATOR to "LED Indicator",
        GaugeWidgetType.TEXT to "Text"
    )
    LazyColumn(modifier = Modifier.height(200.dp)) {
        items(types) { (type, label) ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(selected = selected == type, onClick = { onSelect(type) })
                Spacer(Modifier.width(8.dp))
                Text(label, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DropdownMenuBox(
    options: List<String>,
    selected: String,
    onSelected: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }, modifier = modifier) {
        OutlinedTextField(
            value = selected, onValueChange = {},
            readOnly = true, modifier = Modifier.menuAnchor().fillMaxWidth(),
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) }
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(text = { Text(option) }, onClick = { onSelected(option); expanded = false })
            }
        }
    }
}
