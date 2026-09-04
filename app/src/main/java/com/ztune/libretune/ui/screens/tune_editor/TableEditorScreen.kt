package com.ztune.libretune.ui.screens.tune_editor

import androidx.compose.foundation.background
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
        //  Table grid  (LazyColumn of rows, each containing a LazyRow of cells)
        // ------------------------------------------------------------------
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = if (is3D) 4.dp else 0.dp, end = 4.dp)
        ) {
            // ---- Item 0: X-axis header row ----
            item(key = "x_header") {
                Row {
                    // Corner cell (shows "kPa" or similar Y-axis label for 3D)
                    if (is3D) {
                        Box(
                            modifier = Modifier
                                .width(56.dp)
                                .height(32.dp)
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .border(
                                    width = 0.5.dp,
                                    color = MaterialTheme.colorScheme.outlineVariant
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "kPa",
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    // X-axis bins in a LazyRow
                    LazyRow(state = sharedHorizontalState) {
                        itemsIndexed(state.xBins) { _, bin ->
                            TableBinCell(
                                label = formatCellValue(bin, state.format),
                                isXHeader = true
                            )
                        }
                    }
                }
            }

            // ---- Data rows ----
            itemsIndexed(state.values, key = { idx, _ -> "row_$idx" }) { rowIdx, rowValues ->
                Row {
                    // Y-axis bin header (3D tables only)
                    if (is3D) {
                        val yBin = if (rowIdx < state.yBins.size) state.yBins[rowIdx] else 0.0
                        TableBinCell(
                            label = formatCellValue(yBin, state.format),
                            isXHeader = false
                        )
                    }
                    // Value cells in a LazyRow sharing the horizontal state
                    LazyRow(state = sharedHorizontalState) {
                        itemsIndexed(rowValues) { colIdx, cellValue ->
                            val isSelected = state.selectedCell == Pair(rowIdx, colIdx)
                            TableCell(
                                value = cellValue,
                                format = state.format,
                                min = state.min,
                                max = state.max,
                                isSelected = isSelected,
                                onClick = {
                                    if (isSelected) {
                                        // Second tap on the already-selected cell → open edit dialog
                                        editingRow = rowIdx
                                        editingCol = colIdx
                                        editValueText = formatCellValue(cellValue, state.format)
                                        showEditDialog = true
                                    } else {
                                        viewModel.selectCell(rowIdx, colIdx)
                                    }
                                }
                            )
                        }
                    }
                }
            }

            // Bottom padding so the last row is not clipped by the edge.
            item {
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
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
