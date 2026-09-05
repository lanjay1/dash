@file:Suppress("unused")

package com.ztune.libretune.ui.screens.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.ztune.libretune.R
import com.ztune.libretune.core.dash.GaugeWidgetConfig
import com.ztune.libretune.core.dash.GaugeWidgetType
import com.ztune.libretune.ui.screens.dashboard.gauges.GaugeHost

/**
 * Real-time ECU dashboard screen.
 *
 * Displays a grid of gauge widgets driven by live channel data.
 * Features:
 * - TopAppBar with dashboard name, connection status, and column count selector.
 * - LazyVerticalGrid of gauge widgets, each dispatching to the correct composable.
 * - FAB for adding new gauges or toggling edit mode.
 * - Empty slot placeholders that open the add-gauge dialog on tap.
 * - Widget configuration on tap (shows a dialog).
 *
 * @param onNavigateToConnection Callback to navigate to the ECU connection screen.
 * @param viewModel              Dashboard view model, injected via Hilt.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun DashboardScreen(
    onNavigateToConnection: () -> Unit = {},
    viewModel: DashboardViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    // Dialog state
    var showMenu by remember { mutableStateOf(false) }
    var showWidgetConfigDialog by remember { mutableStateOf<GaugeWidgetConfig?>(null) }
    var showAddDialog by remember { mutableStateOf(false) }
    var showColumnDialog by remember { mutableStateOf(false) }

    // Observe the selected widget from the ViewModel and show the dialog
    val selectedWidgetId = uiState.selectedWidgetId
    if (selectedWidgetId != null) {
        val widget = uiState.widgets.firstOrNull { it.id == selectedWidgetId }
        if (widget != null && !widget.isEmpty) {
            showWidgetConfigDialog = widget
        }
        viewModel.dismissWidgetConfig()
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            DashboardTopBar(
                dashboardName = uiState.dashboardName,
                isConnected = uiState.isConnected,
                connectionStatus = uiState.connectionStatus,
                columns = uiState.columns,
                isEditing = uiState.isEditing,
                onMenuClick = { showMenu = true },
                onToggleEdit = { viewModel.toggleEditMode() },
                onColumnsChange = { showColumnDialog = true },
                scrollBehavior = scrollBehavior
            )
        },
        floatingActionButton = {
            if (uiState.isEditing) {
                FloatingActionButton(
                    onClick = { showAddDialog = true },
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                    contentColor = MaterialTheme.colorScheme.onTertiaryContainer
                ) {
                    Icon(Icons.Default.Add, contentDescription = "Add gauge")
                }
            } else {
                FloatingActionButton(
                    onClick = { viewModel.toggleEditMode() },
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                ) {
                    Icon(Icons.Default.Edit, contentDescription = "Edit layout")
                }
            }
        }
    ) { innerPadding ->
        if (uiState.widgets.isEmpty()) {
            EmptyDashboard(
                isConnected = uiState.isConnected,
                onNavigateToConnection = onNavigateToConnection,
                modifier = Modifier.padding(innerPadding)
            )
        } else {
            GaugeGrid(
                widgets = uiState.widgets,
                columns = uiState.columns,
                channelValues = uiState.channelValues,
                isEditing = uiState.isEditing,
                onWidgetTap = { widget ->
                    if (widget.isEmpty) {
                        showAddDialog = true
                    } else {
                        showWidgetConfigDialog = widget
                    }
                },
                modifier = Modifier.padding(innerPadding)
            )
        }
    }

    // Dropdown menu
    DashboardDropdownMenu(
        expanded = showMenu,
        onDismiss = { showMenu = false },
        isConnected = uiState.isConnected,
        isEditing = uiState.isEditing,
        onToggleEdit = { viewModel.toggleEditMode(); showMenu = false },
        onNavigateToConnection = { onNavigateToConnection(); showMenu = false },
        onResetLayout = { viewModel.loadDashboard(); showMenu = false }
    )

    // Widget configuration dialog
    showWidgetConfigDialog?.let { widget ->
        WidgetConfigDialog(
            widget = widget,
            onDismiss = { showWidgetConfigDialog = null },
            onUpdate = { updated ->
                viewModel.updateWidget(updated)
                showWidgetConfigDialog = null
            },
            onRemove = {
                viewModel.removeWidget(widget.id)
                showWidgetConfigDialog = null
            }
        )
    }

    // Add gauge dialog
    if (showAddDialog) {
        AddGaugeDialog(
            onDismiss = { showAddDialog = false },
            onAdd = { widget ->
                viewModel.addWidget(widget)
                showAddDialog = false
            }
        )
    }

    // Column count dialog
    if (showColumnDialog) {
        ColumnCountDialog(
            current = uiState.columns,
            onDismiss = { showColumnDialog = false },
            onSelect = { cols ->
                viewModel.setColumns(cols)
                showColumnDialog = false
            }
        )
    }
}

// ========================================================================
//  TopAppBar
// ========================================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DashboardTopBar(
    dashboardName: String,
    isConnected: Boolean,
    connectionStatus: String,
    columns: Int,
    isEditing: Boolean,
    onMenuClick: () -> Unit,
    onToggleEdit: () -> Unit,
    onColumnsChange: () -> Unit,
    scrollBehavior: TopAppBarScrollBehavior
) {
    LargeTopAppBar(
        title = {
            Column {
                Text(
                    text = dashboardName,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Connection status indicator
                    BadgedBox(
                        badge = {
                            Badge(
                                containerColor = if (isConnected)
                                    Color(0xFF4CAF50)
                                else
                                    MaterialTheme.colorScheme.error
                            ) {
                                Box(Modifier.size(8.dp))
                            }
                        }
                    ) {
                        Text(
                            text = connectionStatus,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    // Column count indicator
                    if (isEditing) {
                        Text(
                            text = "${columns} cols",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = 4.dp)
                        )
                    }
                }
            }
        },
        navigationIcon = {
            if (isEditing) {
                IconButton(onClick = onToggleEdit) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Done editing"
                    )
                }
            }
        },
        actions = {
            if (isEditing) {
                // Column selector in top bar when editing
                SingleChoiceSegmentedButtonRow(
                    modifier = Modifier.padding(end = 4.dp)
                ) {
                    listOf(2, 3, 4, 5, 6).forEach { col ->
                        SegmentedButton(
                            selected = columns == col,
                            onClick = {
                                if (columns != col) onColumnsChange()
                            },
                            shape = SegmentedButtonDefaults.itemShape(index = col - 2, count = 5)
                        ) {
                            Text("$col", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
            IconButton(onClick = onMenuClick) {
                Icon(Icons.Default.MoreVert, contentDescription = "Menu")
            }
        },
        scrollBehavior = scrollBehavior,
        colors = TopAppBarDefaults.largeTopAppBarColors(
            containerColor = MaterialTheme.colorScheme.surface,
            scrolledContainerColor = MaterialTheme.colorScheme.surface
        )
    )
}

// ========================================================================
//  Gauge Grid
// ========================================================================

@Composable
private fun GaugeGrid(
    widgets: List<GaugeWidgetConfig>,
    columns: Int,
    channelValues: Map<String, Double>,
    isEditing: Boolean,
    onWidgetTap: (GaugeWidgetConfig) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(columns),
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 8.dp,
            end = 8.dp,
            top = 4.dp,
            bottom = 88.dp // Space for FAB
        ),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        items(
            items = widgets,
            key = { it.id },
            span = { widget ->
                if (widget.width > 1 && widget.width <= columns) {
                    GridItemSpan(widget.width)
                } else {
                    GridItemSpan(1)
                }
            }
        ) { widget ->
            val value = if (widget.channelName.isNotBlank()) {
                channelValues[widget.channelName] ?: 0.0
            } else 0.0

            GaugeHost(
                config = widget,
                value = value,
                modifier = Modifier.fillMaxSize(),
                onTap = { onWidgetTap(widget) },
                isEditing = isEditing
            )
        }
    }
}

// ========================================================================
//  Empty Dashboard
// ========================================================================

@Composable
private fun EmptyDashboard(
    isConnected: Boolean,
    onNavigateToConnection: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.Warning,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
        )
        Text(
            text = if (isConnected) "No gauges configured" else stringResource(R.string.dash_not_connected),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = if (isConnected) {
                "Tap the edit button to add gauges to your dashboard."
            } else {
                stringResource(R.string.dash_connect_hint)
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
        if (!isConnected) {
            androidx.compose.material3.Button(
                onClick = onNavigateToConnection,
                modifier = Modifier.padding(top = 16.dp)
            ) {
                Text(stringResource(R.string.dash_connect_hint))
            }
        }
    }
}

// ========================================================================
//  Dropdown Menu
// ========================================================================

@Composable
private fun DashboardDropdownMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    isConnected: Boolean,
    isEditing: Boolean,
    onToggleEdit: () -> Unit,
    onNavigateToConnection: () -> Unit,
    onResetLayout: () -> Unit
) {
    androidx.compose.material3.DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss
    ) {
        DropdownMenuItem(
            text = { Text(if (isEditing) "Done Editing" else "Edit Layout") },
            onClick = onToggleEdit,
            leadingIcon = {
                Icon(
                    if (isEditing) Icons.Default.CheckCircle else Icons.Default.Edit,
                    contentDescription = null
                )
            }
        )
        HorizontalDivider()
        DropdownMenuItem(
            text = { Text("Reset Layout") },
            onClick = onResetLayout,
            leadingIcon = {
                Icon(Icons.Default.Settings, contentDescription = null)
            }
        )
        if (!isConnected) {
            DropdownMenuItem(
                text = { Text("Connect ECU") },
                onClick = onNavigateToConnection,
                leadingIcon = {
                    Icon(Icons.Default.Settings, contentDescription = null)
                }
            )
        }
    }
}

// ========================================================================
//  Widget Configuration Dialog
// ========================================================================

@Composable
private fun WidgetConfigDialog(
    widget: GaugeWidgetConfig,
    onDismiss: () -> Unit,
    onUpdate: (GaugeWidgetConfig) -> Unit,
    onRemove: () -> Unit
) {
    var label by remember(widget) { mutableStateOf(widget.label) }
    var units by remember(widget) { mutableStateOf(widget.units) }
    var minVal by remember(widget) { mutableStateOf(widget.min.toString()) }
    var maxVal by remember(widget) { mutableStateOf(widget.max.toString()) }
    var highWarn by remember(widget) { mutableStateOf(if (widget.highWarning.isNaN()) "" else widget.highWarning.toString()) }
    var lowWarn by remember(widget) { mutableStateOf(if (widget.lowWarning.isNaN()) "" else widget.lowWarning.toString()) }
    var highDang by remember(widget) { mutableStateOf(if (widget.highDanger.isNaN()) "" else widget.highDanger.toString()) }
    var lowDang by remember(widget) { mutableStateOf(if (widget.lowDanger.isNaN()) "" else widget.lowDanger.toString()) }
    var decimals by remember(widget) { mutableStateOf(widget.decimals.toString()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Configure: ${widget.label.ifBlank { widget.channelName }}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text("Label") }
                )
                OutlinedTextField(
                    value = units,
                    onValueChange = { units = it },
                    label = { Text("Units") }
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = minVal,
                        onValueChange = { minVal = it },
                        label = { Text("Min") },
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = maxVal,
                        onValueChange = { maxVal = it },
                        label = { Text("Max") },
                        modifier = Modifier.weight(1f)
                    )
                }
                Text(
                    "Warning Zones",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = lowWarn,
                        onValueChange = { lowWarn = it },
                        label = { Text("Low Warn") },
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = highWarn,
                        onValueChange = { highWarn = it },
                        label = { Text("High Warn") },
                        modifier = Modifier.weight(1f)
                    )
                }
                Text(
                    "Danger Zones",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = lowDang,
                        onValueChange = { lowDang = it },
                        label = { Text("Low Danger") },
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = highDang,
                        onValueChange = { highDang = it },
                        label = { Text("High Danger") },
                        modifier = Modifier.weight(1f)
                    )
                }
                OutlinedTextField(
                    value = decimals,
                    onValueChange = { decimals = it.filter { c -> c.isDigit() }.take(1) },
                    label = { Text("Decimals") },
                    singleLine = true
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val updated = widget.copy(
                    label = label,
                    units = units,
                    min = minVal.toDoubleOrNull() ?: widget.min,
                    max = maxVal.toDoubleOrNull() ?: widget.max,
                    lowWarning = lowWarn.toDoubleOrNull() ?: Double.NaN,
                    highWarning = highWarn.toDoubleOrNull() ?: Double.NaN,
                    lowDanger = lowDang.toDoubleOrNull() ?: Double.NaN,
                    highDanger = highDang.toDoubleOrNull() ?: Double.NaN,
                    decimals = decimals.toIntOrNull() ?: widget.decimals
                )
                onUpdate(updated)
            }) {
                Text("Apply")
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onRemove) {
                    Text("Remove", color = MaterialTheme.colorScheme.error)
                }
                TextButton(onClick = onDismiss) {
                    Text("Cancel")
                }
            }
        }
    )
}

// ========================================================================
//  Add Gauge Dialog
// ========================================================================

private val quickAddPresets = listOf(
    Triple("RPM", "rpm", "RPM"),
    Triple("Coolant Temp", "clt", "°C"),
    Triple("Intake Air Temp", "iat", "°C"),
    Triple("MAP", "map", "kPa"),
    Triple("TPS", "tps", "%"),
    Triple("AFR", "afr", "λ"),
    Triple("Ignition Adv", "ignitionAdv", "°"),
    Triple("Battery", "batteryVoltage", "V"),
    Triple("Fuel PW", "pw", "ms"),
    Triple("VE", "ve", "%")
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AddGaugeDialog(
    onDismiss: () -> Unit,
    onAdd: (GaugeWidgetConfig) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Gauge") },
        text = {
            Column {
                Text(
                    "Quick Add",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(top = 8.dp)
                ) {
                    quickAddPresets.forEach { (label, channel, units) ->
                        androidx.compose.material3.FilterChip(
                            selected = false,
                            onClick = {
                                val widget = GaugeWidgetConfig.create(
                                    id = "g_${System.nanoTime()}",
                                    channelName = channel,
                                    label = label,
                                    units = units,
                                    type = when {
                                        channel == "rpm" -> GaugeWidgetType.ANALOG_SWEEP
                                        channel == "clt" || channel == "iat" -> GaugeWidgetType.ANALOG_SWEEP
                                        channel == "tps" -> GaugeWidgetType.BAR_VERTICAL
                                        channel == "afr" -> GaugeWidgetType.BAR_HORIZONTAL
                                        else -> GaugeWidgetType.DIGITAL_LARGE
                                    },
                                    min = when (channel) {
                                        "rpm" -> 0.0
                                        "clt", "iat" -> -40.0
                                        "tps", "ve" -> 0.0
                                        "afr" -> 7.0
                                        "batteryVoltage" -> 8.0
                                        "pw" -> 0.0
                                        "ignitionAdv" -> -10.0
                                        else -> 0.0
                                    },
                                    max = when (channel) {
                                        "rpm" -> 8000.0
                                        "clt" -> 130.0
                                        "iat" -> 80.0
                                        "tps", "ve" -> 100.0
                                        "afr" -> 22.0
                                        "map" -> 300.0
                                        "batteryVoltage" -> 16.0
                                        "pw" -> 25.0
                                        "ignitionAdv" -> 50.0
                                        else -> 100.0
                                    },
                                    decimals = when (channel) {
                                        "rpm" -> 0
                                        "batteryVoltage" -> 2
                                        "afr" -> 2
                                        else -> 1
                                    }
                                )
                                onAdd(widget)
                            },
                            label = { Text(label, style = MaterialTheme.typography.labelSmall) }
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Done")
            }
        }
    )
}

// ========================================================================
//  Column Count Dialog
// ========================================================================

@Composable
private fun ColumnCountDialog(
    current: Int,
    onDismiss: () -> Unit,
    onSelect: (Int) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Grid Columns") },
        text = {
            SingleChoiceSegmentedButtonRow {
                listOf(2, 3, 4, 5, 6).forEachIndexed { index, col ->
                    SegmentedButton(
                        selected = current == col,
                        onClick = { onSelect(col) },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = 5)
                    ) {
                        Text("$col")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

// ========================================================================
//  Material 3 OutlinedTextField (inline to avoid extra import complexity)
// ========================================================================

@Composable
private fun OutlinedTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: @Composable (() -> Unit)?,
    modifier: Modifier = Modifier,
    singleLine: Boolean = false
) {
    androidx.compose.material3.OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = label,
        modifier = modifier,
        singleLine = singleLine,
        textStyle = MaterialTheme.typography.bodyMedium
    )
}