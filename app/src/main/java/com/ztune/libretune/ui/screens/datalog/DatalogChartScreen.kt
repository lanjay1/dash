@file:OptIn(ExperimentalMaterial3Api::class)

package com.ztune.libretune.ui.screens.datalog

import android.content.ContentValues
import android.graphics.Bitmap
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.input.pointer.awaitEachGesture
import androidx.compose.ui.input.pointer.awaitFirstDown
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.*

// ---------------------------------------------------------------------------
// Constants
// ---------------------------------------------------------------------------

private val CHANNEL_PALETTE = listOf(
    Color(0xFFE53935), Color(0xFF43A047), Color(0xFFFB8C00),
    Color(0xFF1E88E5), Color(0xFF8E24AA), Color(0xFF00ACC1),
    Color(0xFFD81B60), Color(0xFF3949AB), Color(0xFF00897B),
    Color(0xFFC0CA33), Color(0xFFFF6D00), Color(0xFF5E35B1),
)

private val CHART_LEFT = 52.dp
private val CHART_BOTTOM = 24.dp
private val CHART_TOP = 8.dp
private val CHART_RIGHT = 8.dp

private val PLAYBACK_SPEEDS = listOf(0.25f, 0.5f, 1f, 2f, 4f)

// ---------------------------------------------------------------------------
// Screen
// ---------------------------------------------------------------------------

@Composable
fun DatalogChartScreen(
    viewModel: DatalogChartViewModel = hiltViewModel(),
    onBack: () -> Unit = {},
) {
    val state by viewModel.uiState.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val graphicsLayer = rememberGraphicsLayer()

    // Pre-compute per-channel Y ranges for the current view window.
    val channelRanges = remember(
        state.selectedChannels, state.viewStart, state.viewEnd,
    ) {
        state.selectedChannels.associateWith { ch ->
            viewModel.getChannelVisibleRange(ch, state.viewStart, state.viewEnd)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Datalog Chart") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        scope.launch {
                            try {
                                val img = graphicsLayer.toImageBitmap()
                                saveBitmapToGallery(
                                    context, img.asAndroidBitmap(),
                                    "ztune_chart_${System.currentTimeMillis()}.png",
                                )
                                snackbar.showSnackbar("Chart exported to Pictures/ZTune")
                            } catch (_: Exception) {
                                snackbar.showSnackbar("Export failed")
                            }
                        }
                    }) {
                        Icon(Icons.Default.FileDownload, contentDescription = "Export PNG")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        when {
            state.isLoading -> {
                Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            state.loadError != null -> {
                Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                    Text(state.loadError!!, color = MaterialTheme.colorScheme.error)
                }
            }
            else -> {
                Column(
                    modifier = Modifier.padding(padding).fillMaxSize(),
                ) {
                    ChannelSelector(
                        channels = state.channelNames,
                        selected = state.selectedChannels,
                        onToggle = viewModel::toggleChannel,
                        onSelectAll = viewModel::selectAllChannels,
                        onDeselectAll = viewModel::deselectAllChannels,
                    )
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .drawWithContent {
                                graphicsLayer.record {
                                    this@drawWithContent.drawContent()
                                }
                                drawLayer(graphicsLayer)
                            },
                    ) {
                        DatalogChart(state, channelRanges, viewModel)
                    }
                    if (state.crosshairTime != null) {
                        CrosshairInfo(
                            time = state.crosshairTime,
                            values = state.crosshairValues,
                            selectedChannels = state.selectedChannels,
                            channelNames = state.channelNames,
                        )
                    }
                    ChannelStatsPanel(state.channelStats, state.selectedChannels, state.channelNames)
                    PlaybackControls(
                        isPlaying = state.isPlaying,
                        speed = state.playbackSpeed,
                        position = if (state.duration > 0f) state.playbackTime / state.duration else 0f,
                        duration = state.duration,
                        onPlayPause = viewModel::togglePlayback,
                        onSpeedChange = viewModel::setSpeed,
                        onSeek = viewModel::seekTo,
                        onResetView = viewModel::resetView,
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Channel selector
// ---------------------------------------------------------------------------

@Composable
private fun ChannelSelector(
    channels: List<String>,
    selected: Set<String>,
    onToggle: (String) -> Unit,
    onSelectAll: () -> Unit,
    onDeselectAll: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = onSelectAll, modifier = Modifier.height(32.dp)) {
            Text("All", fontSize = 12.sp)
        }
        TextButton(onClick = onDeselectAll, modifier = Modifier.height(32.dp)) {
            Text("None", fontSize = 12.sp)
        }
        channels.forEachIndexed { i, ch ->
            val color = CHANNEL_PALETTE[i % CHANNEL_PALETTE.size]
            FilterChip(
                selected = ch in selected,
                onClick = { onToggle(ch) },
                label = { Text(ch, fontSize = 12.sp) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = color.copy(alpha = 0.2f),
                    selectedLabelColor = color,
                ),
                modifier = Modifier.height(32.dp),
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Canvas chart
// ---------------------------------------------------------------------------

@Composable
private fun DatalogChart(
    state: DatalogChartUiState,
    channelRanges: Map<String, ClosedFloatingPointRange<Float>>,
    viewModel: DatalogChartViewModel,
) {
    val textMeasurer = rememberTextMeasurer()
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val chartBg = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.12f)
    val crosshairClr = Color.White.copy(alpha = 0.7f)
    val playbackClr = Color(0xFFFF4444).copy(alpha = 0.55f)

    Canvas(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var prevX = down.position.x
                    var prevSpan = 0f
                    var wasMultiTouch = false
                    var moved = false
                    do {
                        val event = awaitPointerEvent()
                        val changes = event.changes
                        if (changes.size >= 2) {
                            wasMultiTouch = true
                            moved = true
                            val c0 = changes[0].position
                            val c1 = changes[1].position
                            val span = (c0 - c1).getDistance()
                            if (prevSpan > 0f && span > 0f) {
                                val cx = (c0.x + c1.x) / 2f
                                viewModel.handleZoom(cx / size.width, span / prevSpan)
                            }
                            prevSpan = span
                            prevX = (c0.x + c1.x) / 2f
                        } else if (!wasMultiTouch) {
                            val cur = changes[0].position
                            val dx = cur.x - prevX
                            if (abs(dx) > 2f) {
                                viewModel.handlePan(dx, size.width)
                                moved = true
                            }
                            prevX = cur.x
                        }
                        changes.forEach { it.consume() }
                    } while (changes.any { it.pressed })
                    if (!moved) {
                        viewModel.updateCrosshair(down.position.x / size.width)
                    }
                }
            },
    ) {
        val chartLeft = CHART_LEFT.toPx()
        val chartBottom = size.height - CHART_BOTTOM.toPx()
        val chartTop = CHART_TOP.toPx()
        val chartRight = size.width - CHART_RIGHT.toPx()
        val chartW = chartRight - chartLeft
        val chartH = chartBottom - chartTop
        if (chartW <= 0f || chartH <= 0f || state.duration <= 0f) return@Canvas
        val viewRange = state.viewEnd - state.viewStart
        if (viewRange <= 0f) return@Canvas

        // Background
        drawRect(chartBg, Offset(chartLeft, chartTop), Size(chartW, chartH))

        // Grid lines
        val gridInterval = computeGridInterval(viewRange, chartW)
        var t = ((state.viewStart / gridInterval).toInt() * gridInterval).toFloat()
            .coerceAtLeast(state.viewStart)
        while (t <= state.viewEnd) {
            val x = chartLeft + ((t - state.viewStart) / viewRange) * chartW
            drawLine(gridColor, Offset(x, chartTop), Offset(x, chartBottom), strokeWidth = 1f)
            drawText(
                textMeasurer, formatTime(t),
                topLeft = Offset(x - 20f, chartBottom + 4f),
                style = TextStyle(color = labelColor, fontSize = 10.sp),
            )
            t += gridInterval
        }
        for (i in 0..4) {
            val y = chartTop + (i / 4f) * chartH
            drawLine(gridColor, Offset(chartLeft, y), Offset(chartRight, y), strokeWidth = 1f)
        }

        // Clipped area for data, playback cursor, and crosshair
        clipRect(chartLeft, chartTop, chartRight, chartBottom) {
            // Channel data lines
            state.selectedChannels.forEach { ch ->
                val data = state.channelSeries[ch] ?: return@forEach
                val ci = state.channelNames.indexOf(ch)
                val color = CHANNEL_PALETTE[ci % CHANNEL_PALETTE.size]
                val yr = channelRanges[ch] ?: (0f..1f)
                val ySize = (yr.endInclusive - yr.start).coerceAtLeast(0.001f)

                // Binary-search for visible slice
                val lo = data.binarySearchBy(state.viewStart) { it.first }
                    .let { if (it < 0) -(it + 1) else it }.coerceIn(0, data.size)
                val hi = data.binarySearchBy(state.viewEnd) { it.first }
                    .let { if (it < 0) -(it + 1) else it + 1 }.coerceIn(lo, data.size)
                val slice = data.subList(lo, hi)
                if (slice.isEmpty()) return@forEach

                val maxPts = (chartW * 2).toInt().coerceAtLeast(2)
                val step = (slice.size / maxPts).coerceAtLeast(1)
                val path = Path()
                var started = false
                slice.forEachIndexed { idx, (pt, v) ->
                    if (idx % step != 0 && idx != slice.lastIndex) return@forEachIndexed
                    val px = chartLeft + ((pt - state.viewStart) / viewRange) * chartW
                    val py = chartBottom - ((v - yr.start) / ySize) * chartH
                    if (!started) { path.moveTo(px, py); started = true }
                    else path.lineTo(px, py)
                }
                drawPath(
                    path, color,
                    style = Stroke(
                        width = 2.dp.toPx(),
                        cap = StrokeCap.Round,
                        join = StrokeJoin.Round,
                    ),
                )
            }

            // Playback cursor
            val pt = state.playbackTime
            if (pt in state.viewStart..state.viewEnd) {
                val px = chartLeft + ((pt - state.viewStart) / viewRange) * chartW
                drawLine(playbackClr, Offset(px, chartTop), Offset(px, chartBottom), strokeWidth = 2f)
            }

            // Crosshair
            state.crosshairTime?.let { ct ->
                if (ct in state.viewStart..state.viewEnd) {
                    val cx = chartLeft + ((ct - state.viewStart) / viewRange) * chartW
                    drawLine(crosshairClr, Offset(cx, chartTop), Offset(cx, chartBottom), strokeWidth = 1.5f)
                    state.crosshairValues.forEach { (ch, v) ->
                        val ci = state.channelNames.indexOf(ch)
                        val color = CHANNEL_PALETTE[ci % CHANNEL_PALETTE.size]
                        val yr = channelRanges[ch] ?: (0f..1f)
                        val ys = (yr.endInclusive - yr.start).coerceAtLeast(0.001f)
                        val cy = chartBottom - ((v - yr.start) / ys) * chartH
                        drawCircle(color, radius = 5.dp.toPx(), center = Offset(cx, cy))
                        drawCircle(Color.White, radius = 2.5.dp.toPx(), center = Offset(cx, cy))
                    }
                }
            }
        }

        // Chart border
        drawRect(
            gridColor, Offset(chartLeft, chartTop), Size(chartW, chartH),
            style = Stroke(width = 1f),
        )
    }
}

// ---------------------------------------------------------------------------
// Crosshair info bar
// ---------------------------------------------------------------------------

@Composable
private fun CrosshairInfo(
    time: Float,
    values: Map<String, Float>,
    selectedChannels: Set<String>,
    channelNames: List<String>,
) {
    Surface(tonalElevation = 2.dp, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                "t=${formatTime(time)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            selectedChannels.forEach { ch ->
                val ci = channelNames.indexOf(ch)
                val color = CHANNEL_PALETTE[ci % CHANNEL_PALETTE.size]
                val v = values[ch]
                Text(
                    "$ch: ${v?.let { String.format("%.2f", it.toDouble()) } ?: "—"}",
                    style = MaterialTheme.typography.labelSmall,
                    color = color,
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Stats panel
// ---------------------------------------------------------------------------

@Composable
private fun ChannelStatsPanel(
    stats: Map<String, ChannelStats>,
    selectedChannels: Set<String>,
    channelNames: List<String>,
) {
    if (selectedChannels.isEmpty()) return
    Surface(tonalElevation = 1.dp, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            selectedChannels.forEach { ch ->
                val ci = channelNames.indexOf(ch)
                val color = CHANNEL_PALETTE[ci % CHANNEL_PALETTE.size]
                val s = stats[ch] ?: return@forEach
                Text(
                    buildString {
                        append(ch)
                        append("  min:")
                        append(String.format("%.1f", s.min.toDouble()))
                        append("  avg:")
                        append(String.format("%.1f", s.avg.toDouble()))
                        append("  max:")
                        append(String.format("%.1f", s.max.toDouble()))
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = color,
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Playback controls
// ---------------------------------------------------------------------------

@Composable
private fun PlaybackControls(
    isPlaying: Boolean,
    speed: Float,
    position: Float,
    duration: Float,
    onPlayPause: () -> Unit,
    onSpeedChange: (Float) -> Unit,
    onSeek: (Float) -> Unit,
    onResetView: () -> Unit,
) {
    Surface(tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
            Slider(
                value = position.coerceIn(0f, 1f),
                onValueChange = onSeek,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                IconButton(onClick = onPlayPause, modifier = Modifier.size(36.dp)) {
                    Icon(
                        if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (isPlaying) "Pause" else "Play",
                    )
                }
                PLAYBACK_SPEEDS.forEach { s ->
                    FilterChip(
                        selected = abs(speed - s) < 0.01f,
                        onClick = { onSpeedChange(s) },
                        label = { Text("${s}x", fontSize = 11.sp) },
                        modifier = Modifier.height(28.dp),
                    )
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onResetView) { Text("Fit", fontSize = 11.sp) }
                Text(
                    "${formatTime(position * duration)} / ${formatTime(duration)}",
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Utilities
// ---------------------------------------------------------------------------

private fun formatTime(seconds: Float): String {
    if (seconds.isNaN() || seconds < 0f) return "0.000s"
    val m = (seconds / 60).toInt()
    val s = (seconds % 60).toInt()
    val ms = ((seconds % 1) * 1000).toInt()
    return if (m > 0) "%d:%02d.%03d".format(m, s, ms) else "%d.%03ds".format(s, ms)
}

/** Compute a "nice" grid interval so labels don't overlap. */
private fun computeGridInterval(range: Float, pixels: Float): Float {
    val raw = range / (pixels / 80f)
    val mag = 10f.pow(floor(log10(raw.toDouble())).toFloat())
    val norm = raw / mag
    val step = when {
        norm <= 1f -> 1f
        norm <= 2f -> 2f
        norm <= 5f -> 5f
        else -> 10f
    }
    return step * mag
}

/** Save a [Bitmap] to the shared Pictures/ZTune gallery via MediaStore. */
private fun saveBitmapToGallery(context: android.content.Context, bitmap: Bitmap, name: String) {
    val values = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, name)
        put(MediaStore.Images.Media.MIME_TYPE, "image/png")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/ZTune")
        }
    }
    val uri = context.contentResolver
        .insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return
    context.contentResolver.openOutputStream(uri)?.use { out ->
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
    }
}
