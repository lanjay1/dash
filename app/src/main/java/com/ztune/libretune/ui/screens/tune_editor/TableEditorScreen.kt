package com.ztune.libretune.ui.screens.tune_editor

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.ViewInAr
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel

/**
 * The main table editor screen — displays a 2D or 3D ECU table as a
 * colour-coded, scrollable grid with tap-to-select and inline editing.
 *
 * @param tableName     Identifier of the table to open (e.g. "ve", "ignition").
 * @param onNavigateBack Called when the user presses the back arrow.
 * @param viewModel     Hilt-injected view-model.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TableEditorScreen(
    tableName: String,
    onNavigateBack: () -> Unit = {},
    viewModel: TableEditorViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    var showCellEditor by remember { mutableStateOf(false) }
    var showBurnDialog by remember { mutableStateOf(false) }
    var is3DView by remember { mutableStateOf(false) }
    var showRebinDialog by remember { mutableStateOf(false) }

    // Load table data once when the table name changes.
    LaunchedEffect(tableName) {
        viewModel.loadTable(tableName)
    }

    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    val is3D = state.rows > 1

    // State for the cell-edit dialog.
    var showEditDialog by remember { mutableStateOf(false) }
    var editingRow by remember { mutableStateOf(0) }
    var editingCol by remember { mutableStateOf(0) }
    var editValueText by remember { mutableStateOf("") }

    // Shared horizontal LazyListState so the header row and every data row
    // scroll in lock-step.
    val sharedHorizontalState = rememberLazyListState()

    // ======================================================================
    //  Scaffold: TopAppBar + body
    // ======================================================================
    Column(
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection)
    ) {
        // ------------------------------------------------------------------
        //  Top app bar
        // ------------------------------------------------------------------
        TopAppBar(
            title = {
                Column {
                    Text(
                        text = state.title.ifEmpty { "Table Editor" },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = buildString {
                            append("${state.rows}×${state.cols}")
                            if (state.units.isNotEmpty()) append(" · ${state.units}")
                            if (state.isModified) append(" · Modified")
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (state.isModified) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
            },
            navigationIcon = {
                IconButton(onClick = onNavigateBack) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back"
                    )
                }
            },
            actions = {
                IconButton(
                    onClick = { viewModel.undo() },
                    enabled = state.canUndo
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.Undo,
                        contentDescription = "Undo"
                    )
                }
                IconButton(
                    onClick = { viewModel.redo() },
                    enabled = state.canRedo
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.Redo,
                        contentDescription = "Redo"
                    )
                }
                // Burn button — visible when table is modified
                if (state.isModified || state.isBurning) {
                    IconButton(
                        onClick = { showBurnDialog = true },
                        enabled = !state.isBurning
                    ) {
                        Icon(
                            Icons.Default.LocalFireDepartment,
                            contentDescription = "Burn to ECU",
                            tint = if (state.isBurning) MaterialTheme.colorScheme.onSurfaceVariant
                                   else MaterialTheme.colorScheme.error
                        )
                    }
                }
                // Burn error indicator
                state.burnError?.let { error ->
                    Text(
                        text = "!",
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(end = 8.dp)
                    )
                }
                // 2D/3D toggle
                IconButton(onClick = { is3DView = !is3DView }) {
                    Icon(
                        if (is3DView) Icons.Default.GridOn else Icons.Default.ViewInAr,
                        contentDescription = if (is3DView) "2D View" else "3D View"
                    )
                }
            },
            scrollBehavior = scrollBehavior
        )

        // ------------------------------------------------------------------
        //  Empty state
        // ------------------------------------------------------------------
        if (state.values.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "No table data loaded.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            return@Column
        }

        // ------------------------------------------------------------------
        //  Color legend
        // ------------------------------------------------------------------
        ColorLegend(
            min = state.min,
            max = state.max,
            units = state.units,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp)
        )

        // ------------------------------------------------------------------
        //  3D Surface View (when toggled)
        // ------------------------------------------------------------------
        if (is3DView && state.values.isNotEmpty()) {
            com.ztune.libretune.ui.screens.tune_editor.view3d.Table3DView(
                values = state.values,
                min = state.min,
                max = state.max,
                modifier = Modifier.fillMaxSize().padding(8.dp)
            )
            return@Column
        }

        // ------------------------------------------------------------------
        //  Toolbar operations bar
        // ------------------------------------------------------------------
        TableToolbar(
            state = state,
            viewModel = viewModel,
            onRebin = { showRebinDialog = true }
        )

        // ------------------------------------------------------------------
        //  Canvas-based table grid (replaces LazyColumn + LazyRow)
        // ------------------------------------------------------------------
        CanvasTableEditor(
            values = state.values,
            xBins = state.xBins,
            yBins = state.yBins,
            min = state.min,
            max = state.max,
            format = state.format,
            units = state.units,
            selectedCell = state.selectedCell,
            selectedCells = state.selectedCells,
            liveCell = state.liveCell,
            onCellTap = { row, col ->
                val isSelected = state.selectedCell == Pair(row, col)
                if (isSelected && state.selectedCells.size <= 1) {
                    editingRow = row
                    editingCol = col
                    editValueText = formatCellValue(state.values[row][col], state.format)
                    showEditDialog = true
                } else {
                    viewModel.selectCell(row, col)
                }
            },
            onCellLongPress = { row, col ->
                viewModel.showContextMenu(row, col)
            },
            onCellDragStart = { row, col ->
                viewModel.selectCell(row, col)
            },
            onCellDrag = { row, col ->
                val start = state.selectedCell
                if (start != null) {
                    viewModel.selectCellRange(start.first, start.second, row, col)
                }
            },
            onCellDragEnd = { },
            modifier = Modifier.fillMaxSize()
        )
    }

    // ======================================================================
    //  Cell edit dialog
    // ======================================================================
    if (showEditDialog) {
        CellEditDialog(
            currentValue = editValueText,
            units = state.units,
            onDismiss = { showEditDialog = false },
            onConfirm = { newValue ->
                viewModel.setCellValue(editingRow, editingCol, newValue)
                showEditDialog = false
            }
        )
    }

    // ======================================================================
    //  Context menu (long-press on cell)
    // ======================================================================
    TableContextMenu(state = state, viewModel = viewModel)

    // ======================================================================
    //  Rebin dialog
    // ======================================================================
    if (showRebinDialog) {
        var newRows by remember { mutableStateOf(state.rows.toString()) }
        var newCols by remember { mutableStateOf(state.cols.toString()) }
        AlertDialog(
            onDismissRequest = { showRebinDialog = false },
            title = { Text("Rebin Table") },
            text = {
                Column {
                    Text("Current: ${state.rows} rows × ${state.cols} cols")
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(value = newRows, onValueChange = { newRows = it },
                        label = { Text("New Rows") }, singleLine = true,
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            keyboardType = androidx.compose.ui.text.input.KeyboardType.Number))
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(value = newCols, onValueChange = { newCols = it },
                        label = { Text("New Columns") }, singleLine = true,
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            keyboardType = androidx.compose.ui.text.input.KeyboardType.Number))
                    Spacer(Modifier.height(8.dp))
                    Text("Values will be bilinearly interpolated to the new dimensions.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val r = newRows.toIntOrNull(); val c = newCols.toIntOrNull()
                    if (r != null && c != null && r >= 2 && c >= 2) {
                        viewModel.rebin(r, c)
                    }
                    showRebinDialog = false
                }) { Text("Rebin") }
            },
            dismissButton = { TextButton(onClick = { showRebinDialog = false }) { Text("Cancel") } }
        )
    }

    // ======================================================================
    //  Burn confirmation dialog
    // ======================================================================
    if (showBurnDialog) {
        AlertDialog(
            onDismissRequest = { showBurnDialog = false },
            title = { Text("Burn to ECU") },
            text = {
                Column {
                    Text("This will write the modified table to ECU flash memory.")
                    Spacer(Modifier.height(8.dp))
                    Text("The operation will:")
                    Text("• Back up current data", style = MaterialTheme.typography.bodySmall)
                    Text("• Write new values to ECU RAM", style = MaterialTheme.typography.bodySmall)
                    Text("• Verify the write (byte-for-byte)", style = MaterialTheme.typography.bodySmall)
                    Text("• Burn to flash if verification passes", style = MaterialTheme.typography.bodySmall)
                    Text("• Restore backup if verification fails", style = MaterialTheme.typography.bodySmall)
                    if (state.isBurning) {
                        Spacer(Modifier.height(12.dp))
                        Text("Burning... please wait", color = MaterialTheme.colorScheme.primary)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.burnTable()
                        showBurnDialog = false
                    },
                    enabled = !state.isBurning
                ) { Text("Burn", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showBurnDialog = false }) { Text("Cancel") }
            }
        )
    }

    // Burn error display
    state.burnError?.let { error ->
        AlertDialog(
            onDismissRequest = { /* dismissed by VM state change */ },
            title = { Text("Burn Failed") },
            text = { Text(error) },
            confirmButton = { TextButton(onClick = { showBurnDialog = false }) { Text("OK") } }
        )
    }
}

// =============================================================================
//  Color legend bar (blue → green → red gradient with min/max labels)
// =============================================================================

@Composable
private fun ColorLegend(
    min: Double,
    max: Double,
    units: String,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "${formatCellValue(min, "0.0")}${units}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        // Gradient strip rendered as 50 side-by-side boxes.
        Row(
            modifier = Modifier
                .weight(1f)
                .height(8.dp)
                .padding(horizontal = 8.dp)
                .border(
                    width = 0.5.dp,
                    color = MaterialTheme.colorScheme.outlineVariant
                )
        ) {
            val steps = 50
            repeat(steps) { i ->
                val fraction = i / (steps - 1.0)
                val stepValue = min + fraction * (max - min)
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxSize()
                        .background(TableColorUtils.valueToColor(stepValue, min, max))
                )
            }
        }
        Text(
            text = "${formatCellValue(max, "0.0")}${units}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// =============================================================================
//  Cell edit dialog
// =============================================================================

@Composable
private fun CellEditDialog(
    currentValue: String,
    units: String,
    onDismiss: () -> Unit,
    onConfirm: (Double) -> Unit
) {
    var text by remember { mutableStateOf(currentValue) }
    var errorText by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit Cell Value") },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { input ->
                        text = input
                        errorText = if (input.isBlank()) {
                            null
                        } else {
                            input.toDoubleOrNull()?.let { null }
                                ?: "Enter a valid number"
                        }
                    },
                    label = { Text("Value ($units)") },
                    isError = errorText != null,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                if (errorText != null) {
                    Text(
                        text = errorText!!,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val parsed = text.toDoubleOrNull()
                    if (parsed != null) onConfirm(parsed)
                },
                enabled = errorText == null && text.isNotBlank()
            ) {
                Text("Apply")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

// =============================================================================
//  Table Toolbar — operations bar (Set Equal, Scale, Smooth, Interpolate, etc.)
// =============================================================================

@Composable
private fun TableToolbar(
    state: TableEditorViewModel.TableEditorUiState,
    viewModel: TableEditorViewModel,
    onRebin: () -> Unit
) {
    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        item {
            ToolbarButton("Equal") { viewModel.setCellsEqual() }
        }
        item {
            ToolbarButton("Scale 5%") { viewModel.scaleSelected(1.05) }
        }
        item {
            ToolbarButton("Scale -5%") { viewModel.scaleSelected(0.95) }
        }
        item {
            ToolbarButton("+1") { viewModel.addOffsetToSelection(1.0) }
        }
        item {
            ToolbarButton("-1") { viewModel.addOffsetToSelection(-1.0) }
        }
        item {
            ToolbarButton("Smooth") { viewModel.smoothSelected() }
        }
        item {
            ToolbarButton("Interp") { viewModel.interpolateSelected() }
        }
        item {
            ToolbarButton("Copy") { viewModel.copySelection() }
        }
        item {
            ToolbarButton("Paste") { viewModel.pasteToSelection() }
        }
        item {
            ToolbarButton("Select All") { viewModel.selectAll() }
        }
        item {
            ToolbarButton("Rebin") { onRebin() }
        }
    }
}

@Composable
private fun ToolbarButton(label: String, onClick: () -> Unit) {
    androidx.compose.material3.OutlinedButton(
        onClick = onClick,
        modifier = Modifier.height(36.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp)
    ) {
        Text(label, fontSize = 11.sp)
    }
}

// =============================================================================
//  Context Menu — long-press popup for cell operations
// =============================================================================

@Composable
private fun TableContextMenu(
    state: TableEditorViewModel.TableEditorUiState,
    viewModel: TableEditorViewModel
) {
    if (state.showContextMenu) {
        val cell = state.contextMenuCell ?: return
        androidx.compose.material3.DropdownMenu(
            expanded = true,
            onDismissRequest = { viewModel.hideContextMenu() }
        ) {
            androidx.compose.material3.DropdownMenuItem(
                text = { Text("Edit Cell") },
                onClick = {
                    viewModel.selectCell(cell.first, cell.second)
                    viewModel.hideContextMenu()
                }
            )
            androidx.compose.material3.DropdownMenuItem(
                text = { Text("Copy") },
                onClick = { viewModel.copySelection(); viewModel.hideContextMenu() }
            )
            androidx.compose.material3.DropdownMenuItem(
                text = { Text("Paste") },
                onClick = { viewModel.pasteToSelection(); viewModel.hideContextMenu() }
            )
            androidx.compose.material3.HorizontalDivider()
            androidx.compose.material3.DropdownMenuItem(
                text = { Text("Smooth") },
                onClick = { viewModel.smoothSelected(); viewModel.hideContextMenu() }
            )
            androidx.compose.material3.DropdownMenuItem(
                text = { Text("Interpolate") },
                onClick = { viewModel.interpolateSelected(); viewModel.hideContextMenu() }
            )
            androidx.compose.material3.DropdownMenuItem(
                text = { Text("Set Equal") },
                onClick = { viewModel.setCellsEqual(); viewModel.hideContextMenu() }
            )
            androidx.compose.material3.HorizontalDivider()
            androidx.compose.material3.DropdownMenuItem(
                text = { Text("Select All") },
                onClick = { viewModel.selectAll(); viewModel.hideContextMenu() }
            )
        }
    }
}

/**
 * Format a cell value using a String.format pattern.
 * Used by TableEditorScreen, CanvasTableEditor, and TableCell.
 */
internal fun formatCellValue(value: Double, format: String): String {
    return try {
        String.format("%.${
            when {
                format.contains(".") -> format.substringAfter(".").length
                else -> 1
            }
        }f", value)
    } catch (_: Exception) {
        value.toString()
    }
}