@file:Suppress("unused")

package com.ztune.libretune.ui.screens.dashboard

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ztune.libretune.core.EcuConnectionManager
import com.ztune.libretune.core.dash.DashboardConfig
import com.ztune.libretune.core.dash.DashboardSerializer
import com.ztune.libretune.core.dash.GaugeWidgetConfig
import com.ztune.libretune.core.dash.GaugeWidgetType
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

/**
 * UI state for the Dashboard screen.
 *
 * @property isConnected       Whether the ECU transport is currently active.
 * @property connectionStatus Human-readable connection status label.
 * @property channelValues    Latest decoded channel values from the ECU.
 * @property widgets          Ordered list of gauge widget configurations.
 * @property columns          Number of grid columns for the gauge layout.
 * @property dashboardName    Display name of the current dashboard.
 * @property dashboardId      Persistent id of the current dashboard.
 * @property isEditing        Whether the grid is in edit mode (long-press to rearrange, etc.).
 * @property selectedWidgetId Id of the widget the user tapped for configuration, or null.
 */
data class DashboardUiState(
    val isConnected: Boolean = false,
    val connectionStatus: String = "Disconnected",
    val channelValues: Map<String, Double> = emptyMap(),
    val widgets: List<GaugeWidgetConfig> = emptyList(),
    val columns: Int = 3,
    val dashboardName: String = "Default",
    val dashboardId: String = "default",
    val isEditing: Boolean = false,
    val selectedWidgetId: String? = null
)

@HiltViewModel
class DashboardViewModel @Inject constructor(
    private val connectionManager: EcuConnectionManager,
    @ApplicationContext private val appContext: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(DashboardUiState())
    val uiState: StateFlow<DashboardUiState> = _uiState.asStateFlow()

    // ========================================================================
    //  Initialization
    // ========================================================================

    init {
        // Observe ECU connection state
        viewModelScope.launch {
            connectionManager.state.collect { connState ->
                _uiState.update { prev ->
                    prev.copy(
                        isConnected = connState.status == com.ztune.libretune.core.EcuConnectionStatus.CONNECTED,
                        connectionStatus = formatStatus(connState.status, connState.transportName)
                    )
                }
            }
        }

        // Load saved dashboard or use defaults
        loadDashboard()
    }

    // ========================================================================
    //  Public actions
    // ========================================================================

    /**
     * Load a dashboard by id. Pass null or "default" to load the default.
     */
    fun loadDashboard(id: String? = null) {
        val targetId = id ?: "default"
        val result = DashboardSerializer.loadDashboard(appContext, targetId)
        val config = result.getOrNull()
        if (config != null) {
            _uiState.update {
                it.copy(
                    widgets = config.widgets,
                    columns = config.columns,
                    dashboardName = config.name,
                    dashboardId = config.id
                )
            }
        } else {
            // No saved dashboard – load a demo set of gauges
            loadDemoGauges()
        }
    }

    /**
     * Save the current dashboard configuration to persistent storage.
     */
    fun saveDashboard() {
        val state = _uiState.value
        val config = DashboardConfig(
            id = state.dashboardId,
            name = state.dashboardName,
            columns = state.columns,
            widgets = state.widgets
        )
        DashboardSerializer.saveDashboard(appContext, config)
    }

    /**
     * Update channel values from an external data source (e.g. RealtimeStream).
     * Called by the streaming infrastructure on each data update.
     */
    fun updateChannelValues(values: Map<String, Double>) {
        _uiState.update { it.copy(channelValues = values) }
    }

    /**
     * Toggle the grid edit mode.
     */
    fun toggleEditMode() {
        _uiState.update { it.copy(isEditing = !it.isEditing, selectedWidgetId = null) }
    }

    /**
     * Select a widget for configuration (e.g. user tapped it).
     */
    fun selectWidget(widgetId: String) {
        _uiState.update { it.copy(selectedWidgetId = widgetId) }
    }

    /**
     * Dismiss the widget configuration sheet.
     */
    fun dismissWidgetConfig() {
        _uiState.update { it.copy(selectedWidgetId = null) }
    }

    /**
     * Update a widget's configuration in-place.
     */
    fun updateWidget(updated: GaugeWidgetConfig) {
        _uiState.update { state ->
            state.copy(
                widgets = state.widgets.map {
                    if (it.id == updated.id) updated else it
                }
            )
        }
        autoSave()
    }

    /**
     * Remove a widget from the grid (replaces with empty slot).
     */
    fun removeWidget(widgetId: String) {
        _uiState.update { state ->
            state.copy(
                widgets = state.widgets.map {
                    if (it.id == widgetId) GaugeWidgetConfig.emptySlot(it.id) else it
                },
                selectedWidgetId = null
            )
        }
        autoSave()
    }

    /**
     * Add a new widget to the first available empty slot.
     * If no empty slot exists, appends to the end.
     */
    fun addWidget(widget: GaugeWidgetConfig) {
        _uiState.update { state ->
            val existing = state.widgets.toMutableList()
            // Find first empty slot
            val emptyIdx = existing.indexOfFirst { it.isEmpty }
            if (emptyIdx >= 0) {
                existing[emptyIdx] = widget
            } else {
                existing.add(widget)
            }
            state.copy(widgets = existing)
        }
        autoSave()
    }

    /**
     * Change the number of grid columns.
     */
    fun setColumns(columns: Int) {
        _uiState.update { it.copy(columns = columns.coerceIn(1, 6)) }
        autoSave()
    }

    /**
     * Rename the current dashboard.
     */
    fun setDashboardName(name: String) {
        _uiState.update { it.copy(dashboardName = name) }
        autoSave()
    }

    // ========================================================================
    //  Internal
    // ========================================================================

    /**
     * Load a set of demo gauges for when no saved dashboard exists.
     */
    private fun loadDemoGauges() {
        val demoWidgets = listOf(
            GaugeWidgetConfig.create(
                id = "demo_rpm",
                channelName = "rpm",
                label = "RPM",
                units = "RPM",
                type = GaugeWidgetType.ANALOG_SWEEP,
                min = 0.0, max = 8000.0,
                highWarning = 6500.0,
                highDanger = 7500.0,
                decimals = 0
            ),
            GaugeWidgetConfig.create(
                id = "demo_coolant",
                channelName = "clt",
                label = "Coolant",
                units = "°C",
                type = GaugeWidgetType.ANALOG_SWEEP,
                min = 0.0, max = 130.0,
                highWarning = 100.0,
                highDanger = 115.0,
                decimals = 1
            ),
            GaugeWidgetConfig.create(
                id = "demo_map",
                channelName = "map",
                label = "MAP",
                units = "kPa",
                type = GaugeWidgetType.DIGITAL_LARGE,
                min = 0.0, max = 300.0,
                decimals = 1
            ),
            GaugeWidgetConfig.create(
                id = "demo_afr",
                channelName = "afr",
                label = "AFR",
                units = "λ",
                type = GaugeWidgetType.BAR_HORIZONTAL,
                min = 7.0, max = 22.0,
                lowWarning = 13.0,
                highWarning = 15.5,
                lowDanger = 11.0,
                highDanger = 17.0,
                decimals = 2
            ),
            GaugeWidgetConfig.create(
                id = "demo_tps",
                channelName = "tps",
                label = "TPS",
                units = "%",
                type = GaugeWidgetType.BAR_VERTICAL,
                min = 0.0, max = 100.0,
                decimals = 1
            ),
            GaugeWidgetConfig.create(
                id = "demo_bat",
                channelName = "batteryVoltage",
                label = "Battery",
                units = "V",
                type = GaugeWidgetType.DIGITAL_COMPACT,
                min = 8.0, max = 16.0,
                lowWarning = 11.0,
                lowDanger = 10.0,
                decimals = 2
            ),
            GaugeWidgetConfig.create(
                id = "demo_ign",
                channelName = "ignitionAdv",
                label = "Ign Adv",
                units = "°",
                type = GaugeWidgetType.DIGITAL_LARGE,
                min = -10.0, max = 50.0,
                decimals = 1
            ),
            GaugeWidgetConfig.create(
                id = "demo_iat",
                channelName = "iat",
                label = "IAT",
                units = "°C",
                type = GaugeWidgetType.DIGITAL_COMPACT,
                min = -40.0, max = 80.0,
                highWarning = 60.0,
                decimals = 1
            ),
            GaugeWidgetConfig.emptySlot("slot_8")
        )
        _uiState.update {
            it.copy(
                widgets = demoWidgets,
                columns = 3,
                dashboardName = "Demo",
                dashboardId = "demo"
            )
        }
    }

    /**
     * Auto-save on config changes (debounced in a real app; immediate for now).
     */
    private fun autoSave() {
        // In a production app this would be debounced via a coroutine job.
        // For now we save immediately on any configuration change.
        saveDashboard()
    }

    private fun formatStatus(
        status: com.ztune.libretune.core.EcuConnectionStatus,
        transportName: String?
    ): String = when (status) {
        com.ztune.libretune.core.EcuConnectionStatus.DISCONNECTED -> "Disconnected"
        com.ztune.libretune.core.EcuConnectionStatus.CONNECTING -> "Connecting…"
        com.ztune.libretune.core.EcuConnectionStatus.CONNECTED ->
            transportName?.let { "Connected · $it" } ?: "Connected"
        com.ztune.libretune.core.EcuConnectionStatus.RECONNECTING -> "Reconnecting…"
        com.ztune.libretune.core.EcuConnectionStatus.SYNCING -> "Syncing…"
        com.ztune.libretune.core.EcuConnectionStatus.ERROR -> "Connection Error"
    }

    /**
     * Generate a unique widget id.
     */
    private fun nextWidgetId(): String = "w_${UUID.randomUUID().toString().take(8)}"
}
