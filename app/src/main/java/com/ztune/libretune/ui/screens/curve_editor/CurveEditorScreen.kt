@file:OptIn(ExperimentalMaterial3Api::class)

package com.ztune.libretune.ui.screens.curve_editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Redo
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlin.math.abs

private val AccentColor = Color(0xFF6DD400)
private val GridColor = Color(0xFF3A3A3A)
private val PointSelectedColor = Color(0xFFFF9800)
private val LiveIndicatorColor = Color(0xFF42A5F5)

@Composable
fun CurveEditorScreen(
    onBack: () -> Unit,
    onSave: () -> Unit,
    viewModel: CurveEditorViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val textMeasurer = rememberTextMeasurer()
    var chartBounds by remember { mutableStateOf(Rect.Zero) }
    val scrollState = rememberScrollState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = state.title,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.undo() }, enabled = viewModel.canUndo()) {
                        Icon(Icons.Filled.Undo, contentDescription = "Undo")
                    }
                    IconButton(onClick = { viewModel.redo() }, enabled = viewModel.canRedo()) {
                        Icon(Icons.Filled.Redo, contentDescription = "Redo")
                    }
                }
            )
        },
        bottomBar = {
            CurveEditorBottomBar(
                onSmooth = { viewModel.smoothCurve() },
                onInterpolate = { viewModel.interpolateSelected() },
                onSave = onSave,
                isModified = state.isModified
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(scrollState)
        ) {
            ChartCanvas(
                state = state,
                textMeasurer = textMeasurer,
                onChartBoundsMeasured = { chartBounds = it },
                onPointTapped = { viewModel.selectPoint(it) },
                onPointDragged = { offset ->
                    if (chartBounds != Rect.Zero) {
                        viewModel.setPointByDrag(offset, chartBounds)
                    }
                }
            )
            BinTable(
                xBins = state.xBins,
                yBins = state.yBins,
                yLabel = state.yLabel,
                selectedIndex = state.selectedPoint,
                onValueChange = { idx, newVal -> viewModel.setPointValue(idx, newVal) },
                onRowSelected = { viewModel.selectPoint(it) }
            )
        }
    }
}

@Composable
private fun ChartCanvas(
    state: CurveEditorState,
    textMeasurer: androidx.compose.ui.text.TextMeasurer,
    onChartBoundsMeasured: (Rect) -> Unit,
    onPointTapped: (Int) -> Unit,
    onPointDragged: (Offset) -> Unit
) {
    val xBins = state.xBins
    val yBins = state.yBins
    if (xBins.isEmpty() || yBins.isEmpty()) return

    val yMin = (yBins.minOrNull() ?: 0.0)
    val yMax = (yBins.maxOrNull() ?: 100.0)
    val yRange = yMax - yMin
    val xMin = xBins.first()
    val xMax = xBins.last()
    val xRange = xMax - xMin

    val chartPadding = 56f

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(280.dp)
            .padding(horizontal = 8.dp)
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { offset -> onPointDragged(offset) },
                    onDrag = { change, _ -> onPointDragged(change.position) }
                )
            }
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val left = chartPadding
            val top = 16f
            val right = size.width - 16f
            val bottom = size.height - chartPadding
            val width = right - left
            val height = bottom - top
            val bounds = Rect(left, top, right, bottom)
            onChartBoundsMeasured(bounds)

            val gridLines = 5
            for (i in 0..gridLines) {
                val y = top + (height * i / gridLines)
                drawLine(GridColor, Offset(left, y), Offset(right, y), strokeWidth = 1f)
                val labelVal = yMax - (yRange * i / gridLines)
                val text = "%.1f".format(labelVal)
                val measured = textMeasurer.measure(text, fontSize = 10.sp)
                drawText(measured, color = Color.Gray, topLeft = Offset(2f, y - measured.size.height / 2))
            }

            val xTickStep = maxOf(1, xBins.size / 6)
            for (i in xBins.indices step xTickStep) {
                val xFrac = if (xRange > 0) (xBins[i] - xMin) / xRange else 0.0
                val x = left + (xFrac * width).toFloat()
                drawLine(GridColor, Offset(x, top), Offset(x, bottom), strokeWidth = 1f)
                val text = "%.0f".format(xBins[i])
                val measured = textMeasurer.measure(text, fontSize = 10.sp)
                drawText(
                    measured, color = Color.Gray,
                    topLeft = Offset(x - measured.size.width / 2f, bottom + 6f)
                )
            }

            if (xBins.size >= 2 && yRange > 0) {
                val points = xBins.mapIndexed { i, xVal ->
                    val xFrac = if (xRange > 0) (xVal - xMin) / xRange else 0.0
                    val yFrac = (yBins[i] - yMin) / yRange
                    Offset(
                        left + (xFrac * width).toFloat(),
                        bottom - (yFrac * height).toFloat()
                    )
                }

                val fillPath = Path().apply {
                    moveTo(points.first().x, bottom)
                    points.forEach { lineTo(it.x, it.y) }
                    lineTo(points.last().x, bottom)
                    close()
                }
                drawPath(fillPath, AccentColor.copy(alpha = 0.15f))

                val linePath = Path().apply {
                    points.forEachIndexed { i, p ->
                        if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y)
                    }
                }
                drawPath(linePath, AccentColor, style = Stroke(width = 2.5f))

                points.forEachIndexed { i, p ->
                    val isSelected = i == state.selectedPoint
                    val radius = if (isSelected) 8f else 5f
                    val color = if (isSelected) PointSelectedColor else AccentColor
                    drawCircle(color, radius, p)
                    drawCircle(Color.White, 2f, p)
                }

                state.liveXValue?.let { liveX ->
                    if (liveX in xMin..xMax) {
                        val liveFrac = (liveX - xMin) / xRange
                        val liveScreenX = left + (liveFrac * width).toFloat()
                        drawLine(
                            LiveIndicatorColor.copy(alpha = 0.6f),
                            Offset(liveScreenX, top),
                            Offset(liveScreenX, bottom),
                            strokeWidth = 1.5f
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BinTable(
    xBins: List<Double>,
    yBins: List<Double>,
    yLabel: String,
    selectedIndex: Int,
    onValueChange: (Int, Double) -> Unit,
    onRowSelected: (Int) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.small)
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Text("X", modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelSmall)
            Text(yLabel, modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.End)
        }
        val rows = xBins.indices.toList()
        rows.forEach { i ->
            val isSelected = i == selectedIndex
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        if (isSelected) Modifier.background(AccentColor.copy(alpha = 0.12f))
                        else Modifier
                    )
                    .padding(horizontal = 12.dp, vertical = 6.dp)
                    .then(
                        if (isSelected) Modifier else Modifier
                    ),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "%.1f".format(xBins[i]),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                var editValue by remember(yBins[i]) { mutableStateOf("%.2f".format(yBins[i])) }
                OutlinedTextField(
                    value = editValue,
                    onValueChange = { newText ->
                        editValue = newText
                        newText.toDoubleOrNull()?.let { onValueChange(i, it) }
                    },
                    modifier = Modifier.width(120.dp).height(36.dp),
                    textStyle = MaterialTheme.typography.bodySmall.copy(textAlign = TextAlign.End),
                    singleLine = true
                )
            }
            HorizontalDivider(color = GridColor.copy(alpha = 0.3f))
        }
    }
}

@Composable
private fun CurveEditorBottomBar(
    onSmooth: () -> Unit,
    onInterpolate: () -> Unit,
    onSave: () -> Unit,
    isModified: Boolean
) {
    Surface(
        tonalElevation = 3.dp,
        shadowElevation = 4.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedButton(onClick = onSmooth) {
                Text("Smooth")
            }
            OutlinedButton(onClick = onInterpolate) {
                Text("Interpolate")
            }
            Button(
                onClick = onSave,
                enabled = isModified,
                colors = ButtonDefaults.buttonColors(containerColor = AccentColor)
            ) {
                Text("Save")
            }
        }
    }
}
