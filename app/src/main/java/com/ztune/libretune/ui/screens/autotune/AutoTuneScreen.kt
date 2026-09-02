package com.ztune.libretune.ui.screens.autotune

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ztune.libretune.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AutoTuneScreen(
    viewModel: AutoTuneViewModel = hiltViewModel(),
    onNavigateBack: () -> Unit = {},
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val heatmapData by viewModel.heatmapData.collectAsStateWithLifecycle()
    var settingsExpanded by remember { mutableStateOf(false) }
    var showCellPopup by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("AutoTune") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { settingsExpanded = !settingsExpanded }) {
                        Icon(
                            if (settingsExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = "Toggle settings",
                        )
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { if (uiState.isRunning) viewModel.stopAutoTune() else viewModel.startAutoTune() },
                containerColor = if (uiState.isRunning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            ) {
                Icon(
                    if (uiState.isRunning) Icons.Default.Stop else Icons.Default.PlayArrow,
                    contentDescription = if (uiState.isRunning) "Stop" else "Start",
                )
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding),
        ) {
            AnimatedVisibility(visible = settingsExpanded) {
                SettingsPanel(settings = settings, onUpdate = viewModel::updateSettings)
            }

            HeatmapModeSelector(
                current = uiState.heatmapMode,
                onSelect = viewModel::setHeatmapMode,
            )

            HeatmapLegendBar(mode = uiState.heatmapMode)

            VeTableGrid(
                heatmapData = heatmapData,
                cellStats = uiState.cellStats,
                lockedCells = uiState.lockedCells,
                selectedCell = uiState.selectedCell,
                minCellSamples = settings.minCellSamples,
                onCellTap = { r, c ->
                    viewModel.selectCell(r, c)
                    showCellPopup = true
                },
                onCellLongPress = viewModel::toggleCellLock,
            )

            Spacer(modifier = Modifier.height(8.dp))

            Button(
                onClick = { viewModel.sendRecommendations { success ->
                    if (!success) { /* handled via snackbar if needed */ }
                }},
                enabled = uiState.recommendationsReady,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
            ) {
                Icon(Icons.Default.Send, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Send Recommendations")
            }

            StatsBar(
                totalSamples = uiState.totalSamples,
                activeCells = uiState.activeCells,
                avgCorrection = uiState.avgCorrection,
            )
        }

        if (showCellPopup && uiState.selectedCell != null) {
            val cell = uiState.selectedCell!!
            val stats = uiState.cellStats[cell]
            if (stats != null) {
                CellInfoPopup(
                    stats = stats,
                    isLocked = uiState.lockedCells.contains(cell),
                    onDismiss = {
                        showCellPopup = false
                        viewModel.clearSelection()
                    },
                    onToggleLock = { viewModel.toggleCellLock(cell.first, cell.second) },
                )
            }
        }
    }
}

@Composable
private fun SettingsPanel(
    settings: AutoTuneSettings,
    onUpdate: (AutoTuneSettings) -> AutoTuneSettings,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Settings", style = MaterialTheme.typography.titleSmall)
            LabeledSlider("Target AFR", settings.targetAfr, 9f, 22f) { onUpdate(it.copy(targetAfr = it.targetAfr + 0.1f * it.targetAfr)) }
            LabeledSlider("Min RPM", settings.minRpm.toFloat(), 500f, 8000f) { onUpdate(it.copy(minRpm = it.toInt())) }
            LabeledSlider("Max RPM", settings.maxRpm.toFloat(), 1000f, 10000f) { onUpdate(it.copy(maxRpm = it.toInt())) }
            LabeledSlider("Min CLT °C", settings.minClt.toFloat(), 20f, 100f) { onUpdate(it.copy(minClt = it.toInt())) }
            LabeledSlider("Max TPS Rate %/s", settings.maxTpsRate, 10f, 200f) { onUpdate(it.copy(maxTpsRate = it)) }
            LabeledSlider("Authority %", settings.maxChangePct, 1f, 50f) { onUpdate(it.copy(maxChangePct = it)) }
            LabeledSlider("Authority Abs", settings.maxChangeAbs, 1f, 20f) { onUpdate(it.copy(maxChangeAbs = it)) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Algorithm", modifier = Modifier.weight(1f))
                Algorithm.entries.forEach { algo ->
                    FilterChip(
                        selected = settings.algorithm == algo,
                        onClick = { onUpdate(settings.copy(algorithm = algo)) },
                        label = { Text(algo.label) },
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                }
            }
            LabeledSlider("Delay ms", settings.lambdaDelayMs.toFloat(), 0f, 500f) { onUpdate(it.copy(lambdaDelayMs = it.toLong())) }
            LabeledSlider("Min Samples", settings.minCellSamples.toFloat(), 1f, 50f) { onUpdate(it.copy(minCellSamples = it.toInt())) }
            LabeledSlider("Smoothing", settings.smoothingPasses.toFloat(), 0f, 10f) { onUpdate(it.copy(smoothingPasses = it.toInt())) }
            OutlinedTextField(
                value = settings.customExpression,
                onValueChange = { onUpdate(settings.copy(customExpression = it)) },
                label = { Text("Custom Filter Expr") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
        }
    }
}

@Composable
private fun LabeledSlider(
    label: String,
    value: Float,
    min: Float,
    max: Float,
    onValueChange: (Float) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f), fontSize = 12.sp)
        Text("${"%.1f".format(value)}", fontSize = 12.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.width(56.dp), textAlign = TextAlign.End)
        Slider(
            value = value.coerceIn(min, max),
            onValueChange = onValueChange,
            valueRange = min..max,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun HeatmapModeSelector(current: HeatmapMode, onSelect: (HeatmapMode) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        HeatmapMode.entries.forEach { mode ->
            FilterChip(
                selected = current == mode,
                onClick = { onSelect(mode) },
                label = { Text(mode.name, fontSize = 11.sp) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun HeatmapLegendBar(mode: HeatmapMode) {
    val lowLabel = when (mode) {
        HeatmapMode.WEIGHTING -> "Low weight"
        HeatmapMode.CHANGE -> "Small Δ"
        HeatmapMode.COVERAGE -> "Few hits"
    }
    val highLabel = when (mode) {
        HeatmapMode.WEIGHTING -> "High weight"
        HeatmapMode.CHANGE -> "Large Δ"
        HeatmapMode.COVERAGE -> "Many hits"
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .height(24.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(lowLabel, fontSize = 10.sp, modifier = Modifier.width(72.dp))
        Box(
            modifier = Modifier
                .weight(1f)
                .height(12.dp)
                .clip(MaterialTheme.shapes.small)
                .background(
                    Brush.horizontalGradient(
                        listOf(
                            Color(0xFFE8F5E9),
                            Color(0xFFFFF9C4),
                            Color(0xFFFFCDD2),
                            Color(0xFFB71C1C),
                        )
                    )
                ),
        )
        Text(highLabel, fontSize = 10.sp, modifier = Modifier.width(72.dp), textAlign = TextAlign.End)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun VeTableGrid(
    heatmapData: Map<Pair<Int, Int>, Float>,
    cellStats: Map<Pair<Int, Int>, CellStats>,
    lockedCells: Set<Pair<Int, Int>>,
    selectedCell: Pair<Int, Int>?,
    minCellSamples: Int,
    onCellTap: (Int, Int) -> Unit,
    onCellLongPress: (Int, Int) -> Unit,
) {
    val rows = 16
    val cols = 16
    LazyVerticalGrid(
        columns = GridCells.Fixed(cols + 1),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
    ) {
        item { Box(Modifier.size(32.dp)) }
        items(cols) { col ->
            Box(
                modifier = Modifier.size(32.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text("${col}", fontSize = 8.sp, fontFamily = FontFamily.Monospace)
            }
        }
        items(rows) { row ->
            item {
                Box(
                    modifier = Modifier.size(32.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("${row}", fontSize = 8.sp, fontFamily = FontFamily.Monospace)
                }
            }
            items(cols) { col ->
                val key = row to col
                val value = heatmapData[key]
                val stat = cellStats[key]
                val locked = lockedCells.contains(key)
                val selected = selectedCell == key
                val bgColor = when {
                    locked -> Color(0xFF424242)
                    value == null || stat == null || stat.hits < minCellSamples -> Color(0xFFECEFF1)
                    else -> heatmapColor(value)
                }
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .combinedClickable(
                            onClick = { onCellTap(row, col) },
                            onLongClick = { onCellLongPress(row, col) },
                        )
                        .background(bgColor)
                        .then(
                            if (selected) Modifier.border(2.dp, MaterialTheme.colorScheme.primary)
                            else Modifier
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stat?.currentVe?.let { "%.0f".format(it) } ?: "-",
                        fontSize = 7.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = if (locked) Color.White else Color.Black,
                        maxLines = 1,
                        overflow = TextOverflow.Clip,
                    )
                    if (locked) {
                        Icon(
                            Icons.Default.Lock,
                            contentDescription = "Locked",
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .size(8.dp),
                            tint = Color.White,
                        )
                    }
                }
            }
        }
    }
}

private fun heatmapColor(value: Float): Color {
    val t = value.coerceIn(0f, 1f)
    return when {
        t < 0.25f -> Color(0xFFE8F5E9)
        t < 0.5f -> Color(0xFFFFF9C4)
        t < 0.75f -> Color(0xFFFFCDD2)
        else -> Color(0xFFB71C1C)
    }
}

@Composable
private fun CellInfoPopup(
    stats: CellStats,
    isLocked: Boolean,
    onDismiss: () -> Unit,
    onToggleLock: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Cell Details") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                InfoRow("Hits", "${stats.hits}")
                InfoRow("Current VE", "%.2f".format(stats.currentVe))
                InfoRow("Proposed VE", "%.2f".format(stats.proposedVe))
                InfoRow("Delta", "%+.2f".format(stats.proposedChange))
                InfoRow("Weight", "%.3f".format(stats.weight))
                InfoRow("Confidence", "%.1f%%".format(stats.confidence * 100f))
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(onClick = onToggleLock, modifier = Modifier.weight(1f)) {
                        Icon(
                            if (isLocked) Icons.Default.LockOpen else Icons.Default.Lock,
                            contentDescription = null,
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(if (isLocked) "Unlock" else "Lock")
                    }
                    OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) {
                        Text("Close")
                    }
                }
            }
        },
        confirmButton = {},
    )
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun StatsBar(
    totalSamples: Long,
    activeCells: Int,
    avgCorrection: Float,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        tonalElevation = 2.dp,
        shape = MaterialTheme.shapes.medium,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceAround,
        ) {
            StatItem("Samples", "%,d".format(totalSamples))
            StatItem("Active Cells", "$activeCells")
            StatItem("Avg Correction", "%+.2f%%".format(avgCorrection))
        }
    }
}

@Composable
private fun StatItem(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
        Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
