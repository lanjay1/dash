package com.ztune.libretune.ui.screens.performance

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ztune.libretune.core.performance.PerformanceCalculator
import com.ztune.libretune.core.performance.VehicleSpec

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PerformanceCalculatorScreen(onNavigateBack: () -> Unit = {}) {
    var mass by remember { mutableStateOf("1200") }
    var tireDiameter by remember { mutableStateOf("650") }
    var finalDrive by remember { mutableStateOf("3.9") }
    var rpm by remember { mutableStateOf("3000") }
    var speed by remember { mutableStateOf("80") }

    val spec = VehicleSpec(
        massKg = mass.toDoubleOrNull() ?: 1200.0,
        tireDiameterMm = tireDiameter.toDoubleOrNull() ?: 650.0,
        finalDriveRatio = finalDrive.toDoubleOrNull() ?: 3.9
    )
    val result = PerformanceCalculator.calculate(
        rpm.toDoubleOrNull() ?: 3000.0, speed.toDoubleOrNull() ?: 80.0, spec
    )
    val zeroToHundred = PerformanceCalculator.estimateZeroToHundred(spec)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Performance Calculator") },
                navigationIcon = { IconButton(onClick = onNavigateBack) { Icon(Icons.Default.ArrowBack, "Back") } }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Vehicle Parameters", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            InputRow("Vehicle Mass (kg)", mass) { mass = it }
            InputRow("Tire Diameter (mm)", tireDiameter) { tireDiameter = it }
            InputRow("Final Drive Ratio", finalDrive) { finalDrive = it }
            InputRow("Engine RPM", rpm) { rpm = it }
            InputRow("Vehicle Speed (km/h)", speed) { speed = it }

            HorizontalDivider()
            Text("Results", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)

            ResultCard("Estimated Power", "${"%.0f".format(result.estimatedHp)} HP")
            ResultCard("Estimated Torque", "${"%.0f".format(result.estimatedTorqueNm)} Nm")
            ResultCard("Current Gear", if (result.gear >= 0) "${result.gear + 1}" else "N/A")
            ResultCard("Acceleration", "${"%.2f".format(result.accelerationMs2)} m/s²")
            ResultCard("Drag Force", "${"%.0f".format(result.dragForceN)} N")
            ResultCard("Rolling Resistance", "${"%.0f".format(result.rollingForceN)} N")
            ResultCard("Net Force", "${"%.0f".format(result.netForceN)} N")
            if (!zeroToHundred.isNaN()) {
                ResultCard("Est. 0-100 km/h", "${"%.1f".format(zeroToHundred)} s")
            }
            Spacer(Modifier.height(8.dp))
            Text("Note: Power estimation uses a simplified model. Real values depend on engine torque curve, gear changes, and traction.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun InputRow(label: String, value: String, onChange: (String) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        OutlinedTextField(value = value, onValueChange = onChange, modifier = Modifier.width(120.dp),
            singleLine = true, textStyle = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun ResultCard(label: String, value: String) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }
    }
}
