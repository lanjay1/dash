package com.ztune.libretune.ui.screens.calibration

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SensorCalibrationScreen(
    onNavigateBack: () -> Unit,
    viewModel: SensorCalibrationViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Sensor Calibration") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Calibration type chips
            CalibTypeChips(
                selected = state.type,
                onSelect = viewModel::setCalibrationType
            )

            // Live ADC readout card
            LiveAdcCard(
                type = state.type,
                adcValue = state.liveAdcValue,
                step = state.step
            )

            // Step content
            when (state.step) {
                CalibrationStep.IDLE -> IdleStep(
                    adcValue = state.liveAdcValue,
                    onCaptureLow = viewModel::captureLow
                )
                CalibrationStep.CAPTURE_LOW -> CaptureLowStep(
                    adcLow = state.adcLow,
                    onCaptureHigh = viewModel::captureHigh,
                    onReset = viewModel::reset
                )
                CalibrationStep.CAPTURE_HIGH -> CaptureHighStep(
                    adcLow = state.adcLow,
                    adcHigh = state.adcHigh,
                    onGenerate = viewModel::generateCalibration,
                    onReset = viewModel::reset
                )
                CalibrationStep.PREVIEW -> PreviewStep(
                    values = state.newValues,
                    onWrite = viewModel::writeCalibration,
                    onReset = viewModel::reset
                )
                CalibrationStep.WRITING -> WritingStep(progress = state.progress)
                CalibrationStep.DONE -> DoneStep(isVerified = state.isVerified, onReset = viewModel::reset)
                CalibrationStep.ERROR -> ErrorStep(
                    message = state.errorMessage,
                    onRetry = viewModel::reset
                )
                CalibrationStep.VERIFYING -> WritingStep(progress = state.progress)
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun CalibTypeChips(
    selected: CalibrationType,
    onSelect: (CalibrationType) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        CalibrationType.entries.forEach { type ->
            FilterChip(
                selected = type == selected,
                onClick = { onSelect(type) },
                label = { Text(type.name) },
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun LiveAdcCard(
    type: CalibrationType,
    adcValue: Double,
    step: CalibrationStep
) {
    val isActive = step in listOf(
        CalibrationStep.IDLE, CalibrationStep.CAPTURE_LOW, CalibrationStep.CAPTURE_HIGH
    )
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "${type.name} ADC (Live)",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = String.format("%.1f", adcValue),
                style = MaterialTheme.typography.headlineMedium,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                color = if (isActive) MaterialTheme.colorScheme.primary
                       else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun IdleStep(adcValue: Double, onCaptureLow: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Step 1: Capture Low Reference", style = MaterialTheme.typography.titleMedium)
            Text(
                "Set the sensor to its minimum known position " +
                    "(e.g. throttle closed, ambient temperature). " +
                    "Current ADC: ${String.format("%.1f", adcValue)}",
                style = MaterialTheme.typography.bodyMedium
            )
            Button(
                onClick = onCaptureLow,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Capture Low Value")
            }
        }
    }
}

@Composable
private fun CaptureLowStep(
    adcLow: Double,
    onCaptureHigh: () -> Unit,
    onReset: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Step 2: Capture High Reference", style = MaterialTheme.typography.titleMedium)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Low ADC captured:", style = MaterialTheme.typography.bodyMedium)
                Text(
                    String.format("%.1f", adcLow),
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
            }
            Text(
                "Now set the sensor to its maximum known position " +
                    "(e.g. throttle fully open, apply heat).",
                style = MaterialTheme.typography.bodyMedium
            )
            Button(
                onClick = onCaptureHigh,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Capture High Value")
            }
            TextButton(onClick = onReset, modifier = Modifier.fillMaxWidth()) {
                Text("Reset")
            }
        }
    }
}

@Composable
private fun CaptureHighStep(
    adcLow: Double,
    adcHigh: Double,
    onGenerate: () -> Unit,
    onReset: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Step 3: Confirm & Generate", style = MaterialTheme.typography.titleMedium)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Low ADC:", style = MaterialTheme.typography.bodyMedium)
                Text(
                    String.format("%.1f", adcLow),
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("High ADC:", style = MaterialTheme.typography.bodyMedium)
                Text(
                    String.format("%.1f", adcHigh),
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
            }
            Button(
                onClick = onGenerate,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Generate Calibration Table")
            }
            TextButton(onClick = onReset, modifier = Modifier.fillMaxWidth()) {
                Text("Reset")
            }
        }
    }
}

@Composable
private fun PreviewStep(
    values: List<Double>,
    onWrite: () -> Unit,
    onReset: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Step 4: Preview & Write", style = MaterialTheme.typography.titleMedium)
            Text(
                "Review the generated calibration table before writing to ECU.",
                style = MaterialTheme.typography.bodyMedium
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 200.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                values.forEachIndexed { index, value ->
                    Column(
                        modifier = Modifier.padding(vertical = 2.dp),
                        horizontalAlignment = Alignment.Start
                    ) {
                        Text(
                            "[$index] ${String.format("%.2f", value)}",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp
                        )
                    }
                }
            }
            Button(
                onClick = onWrite,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Write to ECU")
            }
            TextButton(onClick = onReset, modifier = Modifier.fillMaxWidth()) {
                Text("Reset")
            }
        }
    }
}

@Composable
private fun WritingStep(progress: Float) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("Writing Calibration...", style = MaterialTheme.typography.titleMedium)
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                "${(progress * 100).toInt()}%",
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun DoneStep(isVerified: Boolean, onReset: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                Icons.Default.CheckCircle,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Text(
                if (isVerified) "Calibration Written & Verified"
                else "Calibration Written",
                style = MaterialTheme.typography.titleMedium
            )
            Button(
                onClick = onReset,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Calibrate Another Sensor")
            }
        }
    }
}

@Composable
private fun ErrorStep(message: String?, onRetry: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                Icons.Default.Error,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.error
            )
            Text(
                message ?: "An unknown error occurred",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error
            )
            Button(
                onClick = onRetry,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Retry")
            }
        }
    }
}
