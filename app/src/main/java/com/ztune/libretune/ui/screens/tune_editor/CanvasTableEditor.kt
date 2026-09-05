package com.ztune.libretune.ui.screens.tune_editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.MaterialTheme
import kotlin.math.roundToInt

/**
 * Canvas-based 2D table editor.
 *
 * Replaces LazyColumn+LazyRow with a single Canvas that draws all cells,
 * axis labels, selection borders, and live cursor manually.
 *
 * Advantages:
 * - Better performance (no composable overhead per cell)
 * - Smooth pan via drag
 * - Drag-select multi-cell range
 * - Full control over rendering
 *
 * Cell logic:
 * - cellWidth/cellHeight calculated from canvas size and table dimensions
 * - Touch → offsetToCell() converts pixel coordinates to (row, col)
 * - Tap → select single cell, double-tap → edit dialog
 * - Drag → select range from start to current position
 * - Long-press → context menu
 *
 * Layout:
 * ┌─────────┬──────────────────────────┐
 * │ corner  │    X-axis bins (RPM)     │
 * ├─────────┼──────────────────────────┤
 * │  Y-axis │                          │
 * │  bins   │    Value cells grid      │
 * │ (MAP)   │                          │
 * └─────────┴──────────────────────────┘
 */
@Composable
fun CanvasTableEditor(
    values: List<List<Double>>,
    xBins: List<Double>,
    yBins: List<Double>,
    min: Double,
    max: Double,
    format: String,
    units: String,
    selectedCell: Pair<Int, Int>?,
    selectedCells: Set<Pair<Int, Int>>,
    liveCell: Pair<Int, Int>?,
    onCellTap: (Int, Int) -> Unit,
    onCellLongPress: (Int, Int) -> Unit,
    onCellDragStart: (Int, Int) -> Unit,
    onCellDrag: (Int, Int) -> Unit,
    onCellDragEnd: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (values.isEmpty() || values[0].isEmpty()) return

    val rows = values.size
    val cols = values[0].size
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current

    // Colors
    val gridLineColor = MaterialTheme.colorScheme.outlineVariant
    val selectionColor = MaterialTheme.colorScheme.primary
    val liveCursorColor = MaterialTheme.colorScheme.tertiary
    val axisBgColor = MaterialTheme.colorScheme.surfaceVariant
    val axisTextColor = MaterialTheme.colorScheme.onSurfaceVariant
    val cellTextColor = MaterialTheme.colorScheme.onSurface

    // Layout constants
    val cornerSize = with(density) { 56.dp.toPx() } // Y-axis label width
    val headerHeight = with(density) { 28.dp.toPx() } // X-axis header height
    val minCellWidth = with(density) { 72.dp.toPx() }
    val minCellHeight = with(density) { 44.dp.toPx() }

    // Scroll state
    var scrollX by remember { mutableStateOf(0f) }
    var scrollY by remember { mutableStateOf(0f) }

    // Drag state
    var dragStart by remember { mutableStateOf<Pair<Int, Int>?>(null) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(rows, cols) {
                    detectTapGestures(
                        onTap = { offset ->
                            val cell = offsetToCell(offset, cornerSize, headerHeight,
                                minCellWidth, minCellHeight, rows, cols, scrollX, scrollY)
                            if (cell != null) onCellTap(cell.first, cell.second)
                        },
                        onLongPress = { offset ->
                            val cell = offsetToCell(offset, cornerSize, headerHeight,
                                minCellWidth, minCellHeight, rows, cols, scrollX, scrollY)
                            if (cell != null) onCellLongPress(cell.first, cell.second)
                        }
                    )
                }
                .pointerInput(rows, cols) {
                    detectDragGestures(
                        onDragStart = { offset ->
                            val cell = offsetToCell(offset, cornerSize, headerHeight,
                                minCellWidth, minCellHeight, rows, cols, scrollX, scrollY)
                            if (cell != null) {
                                dragStart = cell
                                onCellDragStart(cell.first, cell.second)
                            }
                        },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            scrollX = (scrollX - dragAmount.x).coerceAtMost(0f)
                                .coerceAtLeast(-((cols * minCellWidth) - size.width + cornerSize))
                            scrollY = (scrollY - dragAmount.y).coerceAtMost(0f)
                                .coerceAtLeast(-((rows * minCellHeight) - size.height + headerHeight))

                            // Update drag selection
                            val pos = change.position
                            val cell = offsetToCell(pos, cornerSize, headerHeight,
                                minCellWidth, minCellHeight, rows, cols, scrollX, scrollY)
                            if (cell != null && cell != dragStart) {
                                onCellDrag(cell.first, cell.second)
                            }
                        },
                        onDragEnd = {
                            dragStart = null
                            onCellDragEnd()
                        },
                        onDragCancel = {
                            dragStart = null
                            onCellDragEnd()
                        }
                    )
                }
        ) {
            drawTableGrid(
                textMeasurer = textMeasurer,
                values = values,
                xBins = xBins,
                yBins = yBins,
                min = min,
                max = max,
                format = format,
                units = units,
                selectedCell = selectedCell,
                selectedCells = selectedCells,
                liveCell = liveCell,
                cornerSize = cornerSize,
                headerHeight = headerHeight,
                cellWidth = minCellWidth,
                cellHeight = minCellHeight,
                scrollX = scrollX,
                scrollY = scrollY,
                gridLineColor = gridLineColor,
                selectionColor = selectionColor,
                liveCursorColor = liveCursorColor,
                axisBgColor = axisBgColor,
                axisTextColor = axisTextColor
            )
        }
    }
}

/**
 * Convert a touch offset to (row, col) cell index.
 *
 * Accounts for:
 * - Corner offset (Y-axis label area)
 * - Header height (X-axis label area)
 * - Scroll offset
 */
private fun offsetToCell(
    offset: Offset,
    cornerSize: Float,
    headerHeight: Float,
    cellWidth: Float,
    cellHeight: Float,
    rows: Int,
    cols: Int,
    scrollX: Float,
    scrollY: Float
): Pair<Int, Int>? {
    val x = offset.x - cornerSize - scrollX
    val y = offset.y - headerHeight - scrollY
    if (x < 0 || y < 0) return null
    val col = (x / cellWidth).toInt()
    val row = (y / cellHeight).toInt()
    if (row !in 0 until rows || col !in 0 until cols) return null
    return row to col
}

/**
 * Draw the full table grid on canvas.
 */
private fun DrawScope.drawTableGrid(
    textMeasurer: TextMeasurer,
    values: List<List<Double>>,
    xBins: List<Double>,
    yBins: List<Double>,
    min: Double,
    max: Double,
    format: String,
    units: String,
    selectedCell: Pair<Int, Int>?,
    selectedCells: Set<Pair<Int, Int>>,
    liveCell: Pair<Int, Int>?,
    cornerSize: Float,
    headerHeight: Float,
    cellWidth: Float,
    cellHeight: Float,
    scrollX: Float,
    scrollY: Float,
    gridLineColor: Color,
    selectionColor: Color,
    liveCursorColor: Color,
    axisBgColor: Color,
    axisTextColor: Color
) {
    val rows = values.size
    val cols = values[0].size

    // ---- Clip drawing area to canvas bounds ----
    val clipLeft = cornerSize
    val clipTop = headerHeight
    val clipRight = size.width
    val clipBottom = size.height

    // ---- Draw Y-axis label area background ----
    drawRect(
        color = axisBgColor,
        topLeft = Offset(0f, headerHeight),
        size = Size(cornerSize, size.height - headerHeight)
    )

    // ---- Draw X-axis label area background ----
    drawRect(
        color = axisBgColor,
        topLeft = Offset(cornerSize, 0f),
        size = Size(size.width - cornerSize, headerHeight)
    )

    // ---- Draw corner ----
    drawRect(
        color = axisBgColor,
        topLeft = Offset(0f, 0f),
        size = Size(cornerSize, headerHeight)
    )
    // Corner text (units)
    val cornerText = textMeasurer.measure(
        text = units.ifEmpty { "" },
        style = TextStyle(fontSize = 9.sp, color = axisTextColor)
    )
    drawText(cornerText, topLeft = Offset(4f, headerHeight / 2 - cornerText.size.height / 2))

    // ---- Draw cells ----
    val range = (max - min).takeIf { it > 0 } ?: 1.0
    val cellTextStyle = TextStyle(fontSize = 10.sp)
    val axisStyle = TextStyle(fontSize = 9.sp, color = axisTextColor)

    for (r in 0 until rows) {
        for (c in 0 until cols) {
            val x = cornerSize + c * cellWidth + scrollX
            val y = headerHeight + r * cellHeight + scrollY

            // Skip if completely outside visible area
            if (x + cellWidth < clipLeft || x > this.size.width) continue
            if (y + cellHeight < clipTop || y > this.size.height) continue

            val value = values[r][c]
            val cellColor = TableColorUtils.valueToColor(value, min, max)
            val textColor = TableColorUtils.contrastTextColor(cellColor)

            // Cell background
            drawRect(
                color = cellColor,
                topLeft = Offset(x, y),
                size = Size(cellWidth, cellHeight)
            )

            // Cell border
            drawRect(
                color = gridLineColor,
                topLeft = Offset(x, y),
                size = Size(cellWidth, cellHeight),
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 0.5f)
            )

            // Cell value text
            val displayText = formatCellValue(value, format)
            val textLayout = textMeasurer.measure(
                text = displayText,
                style = cellTextStyle.copy(color = textColor)
            )
            drawText(
                textLayout,
                topLeft = Offset(
                    x + cellWidth / 2 - textLayout.size.width / 2,
                    y + cellHeight / 2 - textLayout.size.height / 2
                )
            )

            // Selection border
            val isSel = selectedCell == (r to c) || (r to c) in selectedCells
            if (isSel) {
                drawRect(
                    color = selectionColor,
                    topLeft = Offset(x, y),
                    size = Size(cellWidth, cellHeight),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3f)
                )
            }

            // Live cursor border
            if (liveCell == (r to c)) {
                drawRect(
                    color = liveCursorColor,
                    topLeft = Offset(x - 1f, y - 1f),
                    size = Size(cellWidth + 2f, cellHeight + 2f),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = 4f)
                )
            }
        }
    }

    // ---- Draw X-axis bin labels ----
    for (c in 0 until cols) {
        val x = cornerSize + c * cellWidth + scrollX
        if (x + cellWidth < cornerSize || x > this.size.width) continue

        val binValue = xBins.getOrElse(c) { (c * 500).toDouble() }
        val label = formatCellValue(binValue, format)
        val textLayout = textMeasurer.measure(text = label, style = axisStyle)
        drawText(
            textLayout,
            topLeft = Offset(
                x + cellWidth / 2 - textLayout.size.width / 2,
                headerHeight / 2 - textLayout.size.height / 2
            )
        )
    }

    // ---- Draw Y-axis bin labels ----
    for (r in 0 until rows) {
        val y = headerHeight + r * cellHeight + scrollY
        if (y + cellHeight < headerHeight || y > this.size.height) continue

        val binValue = yBins.getOrElse(r) { (20 + r * 10).toDouble() }
        val label = formatCellValue(binValue, format)
        val textLayout = textMeasurer.measure(text = label, style = axisStyle)
        drawText(
            textLayout,
            topLeft = Offset(
                cornerSize / 2 - textLayout.size.width / 2,
                y + cellHeight / 2 - textLayout.size.height / 2
            )
        )
    }

    // ---- Draw separator lines ----
    // Vertical line between Y-axis and cells
    drawLine(
        color = gridLineColor,
        start = Offset(cornerSize, 0f),
        end = Offset(cornerSize, size.height),
        strokeWidth = 1f
    )
    // Horizontal line between X-axis and cells
    drawLine(
        color = gridLineColor,
        start = Offset(0f, headerHeight),
        end = Offset(size.width, headerHeight),
        strokeWidth = 1f
    )
}
