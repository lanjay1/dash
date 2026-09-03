package com.ztune.libretune.ui.screens.diagnostics

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ztune.libretune.core.ecu.ToothLoggerTransport

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToothLoggerScreen(
    viewModel: ToothLoggerViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    val captureColor by animateColorAsState(
        targetValue = if (state.isCapturing) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
        label = "captureBtn",
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Tooth Logger") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(16.dp))

            // Capture button
            Button(
                onClick = {
                    if (state.isCapturing) viewModel.stopCapture()
                    else viewModel.startCapture()
                },
                colors = ButtonDefaults.buttonColors(containerColor = captureColor),
                modifier = Modifier.padding(horizontal = 16.dp),
            ) {
                Icon(
                    imageVector = if (state.isCapturing) Icons.Default.Stop else Icons.Default.FiberManualRecord,
                    contentDescription = null,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    if (state.isCapturing) "Stop Capture" else "Start Capture",
                    fontWeight = FontWeight.SemiBold,
                )
            }

            // Error banner
            state.error?.let {
                Spacer(Modifier.height(12.dp))
                Text(
                    text = it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }

            Spacer(Modifier.height(16.dp))

            // Timing diagram
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .height(220.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
            ) {
                ToothTimingDiagram(
                    events = state.events,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(12.dp),
                )
            }

            Spacer(Modifier.height(16.dp))

            // Stats panel
            StatsPanel(stats = state.stats)

            Spacer(Modifier.height(24.dp))
        }
    }
}

// ------------------------------------------------------------------
// Timing diagram canvas
// ------------------------------------------------------------------

@Composable
private fun ToothTimingDiagram(
    events: List<ToothLoggerTransport.ToothEvent>,
    modifier: Modifier = Modifier,
) {
    val textMeasurer = rememberTextMeasurer()
    androidx.compose.foundation.Canvas(modifier = modifier) {
        if (events.isEmpty()) {
            drawCenteredPlaceholder("No data captured", textMeasurer)
            return@Canvas
        }

        val maxTime = events.last().timeUs.toFloat()
        if (maxTime <= 0f) return@Canvas

        val primaryHeight = size.height * 0.72f
        val secondaryHeight = size.height * 0.32f
        val barWidth = 2.dp.toPx()
        val primaryColor = Color(0xFFE53935)
        val secondaryColor = Color(0xFF43A047)

        // Subtle grid lines
        drawGrid(maxTime)

        // Draw each tooth event as a vertical bar
        for (event in events) {
            val x = (event.timeUs.toFloat() / maxTime) * size.width

            if (event.isPrimary) {
                drawRect(
                    color = primaryColor,
                    topLeft = Offset(x - barWidth / 2f, 0f),
                    size = androidx.compose.ui.geometry.Size(barWidth, primaryHeight),
                )
            }
            if (event.isSecondary) {
                val yBase = size.height - secondaryHeight
                drawRect(
                    color = secondaryColor,
                    topLeft = Offset(x - barWidth / 2f, yBase),
                    size = androidx.compose.ui.geometry.Size(barWidth, secondaryHeight),
                )
            }
        }

        // Axis labels
        drawAxisLabels(textMeasurer)
    }
}

private fun DrawScope.drawCenteredPlaceholder(text: String, textMeasurer: TextMeasurer) {
    val measured = textMeasurer.measure(text, TextStyle(fontSize = 10.sp))
    drawText(
        textLayoutResult = measured,
        color = Color.Gray.copy(alpha = 0.6f),
        topLeft = Offset(
            (size.width - measured.size.width) / 2f,
            (size.height - measured.size.height) / 2f,
        ),
    )
}

private fun DrawScope.drawGrid(maxTime: Float) {
    val gridColor = Color.Gray.copy(alpha = 0.15f)
    val steps = 8
    for (i in 0..steps) {
        val x = (i.toFloat() / steps) * size.width
        drawLine(
            color = gridColor,
            start = Offset(x, 0f),
            end = Offset(x, size.height),
            strokeWidth = 1f,
        )
    }
}

private fun DrawScope.drawAxisLabels(textMeasurer: TextMeasurer) {
    val labelColor = Color.Gray.copy(alpha = 0.7f)
    val primaryLabel = textMeasurer.measure("PRI", TextStyle(fontSize = 10.sp))
    val secLabel = textMeasurer.measure("SEC", TextStyle(fontSize = 10.sp))
    drawText(textLayoutResult = primaryLabel, color = labelColor, topLeft = Offset(4f, 4f))
    drawText(
        textLayoutResult = secLabel,
        color = labelColor,
        topLeft = Offset(4f, size.height - secLabel.size.height - 4f),
    )
}

// ------------------------------------------------------------------
// Stats panel
// ------------------------------------------------------------------

@Composable
private fun StatsPanel(stats: ToothPatternStats) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        StatCard(label = "Tooth Count", value = "${stats.toothCount}", Modifier.weight(1f))
        StatCard(label = "RPM", value = "${"%.0f".format(stats.rpm)}", Modifier.weight(1f))
    }
    Spacer(Modifier.height(12.dp))
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        StatCard(label = "Missing Teeth", value = "${stats.missingTeethCount}", Modifier.weight(1f))
        StatCard(label = "Gap Ratio", value = "${"%.2f".format(stats.gapRatio)}", Modifier.weight(1f))
    }
}

@Composable
private fun StatCard(label: String, value: String, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = value,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
