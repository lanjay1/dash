package com.ztune.libretune.ui.screens.analysis

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ztune.libretune.core.tune.Tune
import com.ztune.libretune.core.ini.EcuDefinition

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VeAnalysisScreen(
    viewModel: VeAnalysisViewModel = hiltViewModel(),
    tune: Tune? = null,
    definition: EcuDefinition? = null,
    onNavigateBack: () -> Unit = {},
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var showMappings by remember { mutableStateOf(true) }
    var showParams by remember { mutableStateOf(false) }
    var showCoverage by remember { mutableStateOf(false) }
    var showCellPopup by remember { mutableStateOf(false) }
    var showRejections by remember { mutableStateOf(false) }

    val csvPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
    ) { uri: Uri? ->
        uri?.let { viewModel.loadCsv(it) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("VE Analysis")
                        if (state.csvLoaded) {
                            Text(
                                state.csvFileName,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    AnalysisMode.entries.forEach { mode ->
                        FilterChip(
                            selected = state.mode == mode,
                            onClick = { viewModel.setMode(mode) },
                            label = { Text(mode.label, fontSize = 11.sp) },
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            // File picker
            OutlinedButton(
                onClick = { csvPicker.launch("text/csv") },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
            ) {
                Icon(Icons.Default.UploadFile, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(if (state.csvLoaded) "Change Log File" else "Load Datalog CSV")
            }

            if (!state.csvLoaded) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "Load a datalog CSV to begin analysis",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                return@Scaffold
            }

            // Channel mapping section
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showMappings = !showMappings }
                        .padding(12.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Channel Mapping", style = MaterialTheme.typography.titleSmall)
                        Icon(
                            if (showMappings) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = null,
                        )
                    }
                    AnimatedVisibility(visible = showMappings) {
                        Column(
                            modifier = Modifier.padding(top = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            ChannelDropdown(
                                label = "RPM",
                                selected = state.channelMapping.rpm,
                                channels = state.availableChannels,
                                onSelect = { viewModel.updateChannelMapping(state.channelMapping.copy(rpm = it)) },
                            )
                            ChannelDropdown(
                                label = "MAP / TPS",
                                selected = state.channelMapping.mapOrTps,
                                channels = state.availableChannels,
                                onSelect = { viewModel.updateChannelMapping(state.channelMapping.copy(mapOrTps = it)) },
                            )
                            ChannelDropdown(
                                label = "AFR / Lambda",
                                selected = state.channelMapping.afr,
                                channels = state.availableChannels,
                                onSelect = { viewModel.updateChannelMapping(state.channelMapping.copy(afr = it)) },
                            )
                            ChannelDropdown(
                                label = "CLT",
                                selected = state.channelMapping.clt,
                                channels = state.availableChannels,
                                onSelect = { viewModel.updateChannelMapping(state.channelMapping.copy(clt = it)) },
                            )
                            ChannelDropdown(
                                label = "EGO Correction",
                                selected = state.channelMapping.egoCorrection,
                                channels = state.availableChannels,
                                onSelect = { viewModel.updateChannelMapping(state.channelMapping.copy(egoCorrection = it)) },
                            )
                            ChannelDropdown(
                                label = "TPS Rate (%/s)",
                                selected = state.channelMapping.tpsRate,
                                channels = state.availableChannels,
                                onSelect = { viewModel.updateChannelMapping(state.channelMapping.copy(tpsRate = it)) },
                            )
                        }
                    }
                }
            }

            // Analysis parameters
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showParams = !showParams }
                        .padding(12.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Parameters", style = MaterialTheme.typography.titleSmall)
                        Icon(
                            if (showParams) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = null,
                        )
                    }
                    AnimatedVisibility(visible = showParams) {
                        Column(
                            modifier = Modifier.padding(top = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            ParamRow("Weighting", state.params.weightingMode.label) {
                                WeightingMode.entries.forEach { mode ->
                                    FilterChip(
                                        selected = state.params.weightingMode == mode,
                                        onClick = { viewModel.updateParams { it.copy(weightingMode = mode) } },
                                        label = { Text(mode.label, fontSize = 10.sp) },
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                }
                            }
                            ParamSlider(
                                "Target AFR",
                                state.params.targetAfr,
                                9f, 22f,
                            ) { viewModel.updateParams { it.copy(targetAfr = it) } }
                            ParamSlider(
                                "Min Change %",
                                state.params.minChangePct,
                                0f, 5f,
                            ) { viewModel.updateParams { it.copy(minChangePct = it) } }
                            ParamSlider(
                                "Min Steady ms",
                                state.params.minSteadyMs.toFloat(),
                                0f, 2000f,
                            ) { viewModel.updateParams { it.copy(minSteadyMs = it.toLong()) } }
                            ParamSlider(
                                "CLT Threshold °C",
                                state.params.cltThreshold.toFloat(),
                                20f, 100f,
                            ) { viewModel.updateParams { it.copy(cltThreshold = it.toInt()) } }
                            ParamSlider(
                                "TPS Rate Thresh",
                                state.params.tpsRateThreshold,
                                10f, 300f,
                            ) { viewModel.updateParams { it.copy(tpsRateThreshold = it) } }
                            ParamSlider(
                                "Max Change %",
                                state.params.maxChangePct,
                                1f, 30f,
                            ) { viewModel.updateParams { it.copy(maxChangePct = it) } }
                            ParamSlider(
                                "Smoothing Passes",
                                state.params.smoothingPasses.toFloat(),
                                0f, 8f,
                            ) { viewModel.updateParams { it.copy(smoothingPasses = it.toInt()) } }
                            ParamSlider(
                                "AFR Tolerance",
                                state.params.afrTolerance,
                                0.5f, 5f,
                            ) { viewModel.updateParams { it.copy(afrTolerance = it) } }
                        }
                    }
                }
            }

            // Run analysis button
            Button(
                onClick = { viewModel.runAnalysis(tune, definition) },
                enabled = !state.isAnalyzing && state.csvLoaded,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                if (state.isAnalyzing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Analyzing...")
                } else {
                    Icon(Icons.Default.PlayArrow, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Run Analysis")
                }
            }

            // Error display
            state.error?.let { error ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                ) {
                    Text(error, modifier = Modifier.padding(12.dp), color = MaterialTheme.colorScheme.error)
                }
            }

            // Results
            state.results?.let { results ->
                // Stats bar
                StatsBar(results)

                // Rejection breakdown
                if (results.rejectionBreakdown.isNotEmpty()) {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { showRejections = !showRejections }
                                .padding(12.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text("Rejection Breakdown", style = MaterialTheme.typography.titleSmall)
                                Text(
                                    "${results.rejectedSamples} rejected",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            AnimatedVisibility(visible = showRejections) {
                                Column(
                                    modifier = Modifier.padding(top = 6.dp),
                                    verticalArrangement = Arrangement.spacedBy(2.dp),
                                ) {
                                    results.rejectionBreakdown.entries
                                        .sortedByDescending { it.value }
                                        .forEach { (reason, count) ->
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                            ) {
                                                Text(
                                                    reason.label,
                                                    fontSize = 11.sp,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                )
                                                Text(
                                                    "$count",
                                                    fontSize = 11.sp,
                                                    fontFamily = FontFamily.Monospace,
                                                )
                                            }
                                        }
                                }
                            }
                        }
                    }
                }

                // Heatmap toggle
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(
                        selected = !showCoverage,
                        onClick = { showCoverage = false },
                        label = { Text("Proposed Changes", fontSize = 11.sp) },
                    )
                    FilterChip(
                        selected = showCoverage,
                        onClick = { showCoverage = true },
                        label = { Text("Coverage Heatmap", fontSize = 11.sp) },
                    )
                }

                // VE table grid
                if (showCoverage) {
                    CoverageGridView(
                        coverageMap = results.coverageMap,
                        rowCount = results.rowCount,
                        colCount = results.colCount,
                        onCellTap = { x, y ->
                            viewModel.selectCell(x, y)
                            showCellPopup = true
                        },
                    )
                } else {
                    VeProposedGridView(
                        cellMap = results.cellMap,
                        rowCount = results.rowCount,
                        colCount = results.colCount,
                        onCellTap = { x, y ->
                            viewModel.selectCell(x, y)
                            showCellPopup = true
                        },
                    )
                }

                // Cross-validation score
                if (results.crossValidationScore > 0f) {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text("Cross-Validation Score", style = MaterialTheme.typography.bodySmall)
                            Text(
                                "%.1f%%".format(results.crossValidationScore * 100f),
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                }

                // Apply button
                Button(
                    onClick = {
                        viewModel.applyRecommendations(tune) { success ->
                            if (success) viewModel.clearResults()
                        }
                    },
                    enabled = tune != null && results.cells.isNotEmpty(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    ) {
                        Icon(Icons.Default.Check, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Apply to Table")
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
            }

            // Cell detail popup
            if (showCellPopup && state.selectedCell != null && state.results != null) {
                val cell = state.results.cellMap[state.selectedCell]
                if (cell != null) {
                    CellDetailDialog(
                        cell = cell,
                        coverageHits = state.results.coverageMap[state.selectedCell] ?: 0,
                        onDismiss = {
                            showCellPopup = false
                            viewModel.clearSelection()
                        },
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Channel dropdown
// ---------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChannelDropdown(
    label: String,
    selected: String,
    channels: List<String>,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, modifier = Modifier.width(100.dp), fontSize = 12.sp)
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = it },
            modifier = Modifier.weight(1f),
        ) {
            OutlinedTextField(
                value = if (selected.isBlank()) "-- select --" else selected,
                onValueChange = {},
                readOnly = true,
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                modifier = Modifier
                    .menuAnchor()
                    .fillMaxWidth(),
                textStyle = MaterialTheme.typography.bodySmall,
            )
            ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                DropdownMenuItem(
                    text = { Text("-- none --", fontSize = 12.sp) },
                    onClick = { onSelect(""); expanded = false },
                )
                channels.forEach { ch ->
                    DropdownMenuItem(
                        text = { Text(ch, fontSize = 12.sp) },
                        onClick = { onSelect(ch); expanded = false },
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Parameter row with slider
// ---------------------------------------------------------------------------

@Composable
private fun ParamSlider(
    label: String,
    value: Float,
    min: Float,
    max: Float,
    onValueChange: (Float) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.width(120.dp), fontSize = 11.sp)
        Text(
            "%.1f".format(value),
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.width(48.dp),
            textAlign = TextAlign.End,
        )
        Slider(
            value = value.coerceIn(min, max),
            onValueChange = onValueChange,
            valueRange = min..max,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun ParamRow(
    label: String,
    value: String,
    content: @Composable () -> Unit,
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, fontSize = 11.sp)
            Text(value, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
        }
        Spacer(modifier = Modifier.height(4.dp))
        content()
    }
}

// ---------------------------------------------------------------------------
// Stats bar
// ---------------------------------------------------------------------------

@Composable
private fun StatsBar(results: AnalysisResults) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        tonalElevation = 2.dp,
        shape = MaterialTheme.shapes.medium,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceAround,
        ) {
            StatItem("Total Samples", "%,d".format(results.totalSamples))
            StatItem("Accepted", "%,d".format(results.acceptedSamples))
            StatItem("Rejected", "%,d".format(results.rejectedSamples))
            StatItem("Coverage", "%.0f%%".format(results.coveragePct))
        }
    }
}

@Composable
private fun StatItem(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            value,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.titleSmall,
        )
        Text(label, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ---------------------------------------------------------------------------
// VE proposed changes grid
// ---------------------------------------------------------------------------

@Composable
private fun VeProposedGridView(
    cellMap: Map<Pair<Int, Int>, CellResult>,
    rowCount: Int,
    colCount: Int,
    onCellTap: (Int, Int) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
    ) {
        // Column headers
        Row {
            Box(modifier = Modifier.size(40.dp, 20.dp))
            repeat(colCount) { c ->
                Box(
                    modifier = Modifier.width(40.dp).height(20.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("$c", fontSize = 7.sp, fontFamily = FontFamily.Monospace)
                }
            }
        }
        repeat(rowCount) { r ->
            Row {
                Box(
                    modifier = Modifier.size(40.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("$r", fontSize = 7.sp, fontFamily = FontFamily.Monospace)
                }
                repeat(colCount) { c ->
                    val cell = cellMap[c to r]
                    val bgColor = when {
                        cell == null -> Color(0xFFECEFF1)
                        cell.delta > 0 -> Color(
                            0xFF_E0_F2_F1.toInt() and 0xFF_FFFFFF.toInt() or
                                (cell.delta.coerceIn(0f, 5f) / 5f * 0x4CAF50.toInt()).toInt()
                        ).let {
                            val alpha = (cell.delta.coerceIn(0f, 5f) / 5f)
                            Color.lerp(Color(0xFFE8F5E9), Color(0xFF2E7D32), alpha)
                        }
                        cell.delta < 0 -> {
                            val alpha = (cell.delta.coerceIn(-5f, 0f) / -5f)
                            Color.lerp(Color(0xFFFFEBEE), Color(0xFFC62828), alpha)
                        }
                        else -> Color(0xFFF5F5F5)
                    }
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .background(bgColor)
                            .clickable { onCellTap(c, r) }
                            .border(0.5.dp, Color(0xFFBDBDBD)),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (cell != null) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Text(
                                    "%.0f".format(cell.currentVe),
                                    fontSize = 6.sp,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                )
                                Text(
                                    "%+.1f".format(cell.delta),
                                    fontSize = 6.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = if (cell.delta > 0) Color(0xFF1B5E20) else Color(0xFFB71C1C),
                                    maxLines = 1,
                                )
                            }
                        } else {
                            Text("-", fontSize = 7.sp, color = Color(0xFFBDBDBD))
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Coverage heatmap grid
// ---------------------------------------------------------------------------

@Composable
private fun CoverageGridView(
    coverageMap: Map<Pair<Int, Int>, Int>,
    rowCount: Int,
    colCount: Int,
    onCellTap: (Int, Int) -> Unit,
) {
    val maxHits = coverageMap.values.maxOrNull() ?: 1
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
    ) {
        Row {
            Box(modifier = Modifier.size(40.dp, 20.dp))
            repeat(colCount) { c ->
                Box(
                    modifier = Modifier.width(40.dp).height(20.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("$c", fontSize = 7.sp, fontFamily = FontFamily.Monospace)
                }
            }
        }
        repeat(rowCount) { r ->
            Row {
                Box(
                    modifier = Modifier.size(40.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("$r", fontSize = 7.sp, fontFamily = FontFamily.Monospace)
                }
                repeat(colCount) { c ->
                    val hits = coverageMap[c to r] ?: 0
                    val intensity = if (maxHits > 0) hits.toFloat() / maxHits else 0f
                    val bgColor = if (hits == 0) {
                        Color(0xFFECEFF1)
                    } else {
                        Color.lerp(Color(0xFFFFF9C4), Color(0xFFE65100), intensity)
                    }
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .background(bgColor)
                            .clickable { onCellTap(c, r) }
                            .border(0.5.dp, Color(0xFFBDBDBD)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "$hits",
                            fontSize = 7.sp,
                            fontFamily = FontFamily.Monospace,
                            color = if (intensity > 0.6f) Color.White else Color.Black,
                        )
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Cell detail dialog
// ---------------------------------------------------------------------------

@Composable
private fun CellDetailDialog(
    cell: CellResult,
    coverageHits: Int,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Cell (${cell.x}, ${cell.y})") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                DetailRow("Current VE", "%.2f".format(cell.currentVe))
                DetailRow("Proposed VE", "%.2f".format(cell.proposedVe))
                DetailRow("Delta", "%+.2f".format(cell.delta))
                HorizontalDivider()
                DetailRow("Target AFR", "%.1f".format(cell.targetAfr))
                DetailRow("Mean AFR", "%.2f".format(cell.meanAfr))
                HorizontalDivider()
                DetailRow("Hits", "${cell.hits}")
                DetailRow("Coverage Hits", "$coverageHits")
                DetailRow("Weight", "%.1f".format(cell.weight))
                DetailRow("Confidence", "%.1f%%".format(cell.confidence * 100f))
                // Confidence bar
                LinearProgressIndicator(
                    progress = { cell.confidence },
                    modifier = Modifier.fillMaxWidth().height(8.dp),
                    color = when {
                        cell.confidence >= 0.7f -> Color(0xFF4CAF50)
                        cell.confidence >= 0.4f -> Color(0xFFFFC107)
                        else -> Color(0xFFF44336)
                    },
                )
            }
        },
        confirmButton = {
            OutlinedButton(onClick = onDismiss) { Text("Close") }
        },
    )
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            value,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Medium,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
