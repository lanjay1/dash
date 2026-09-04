@file:Suppress("unused")

package com.ztune.libretune.ui.screens.settings

import android.content.Context
import android.content.pm.PackageManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ztune.libretune.core.AppSettings
import com.ztune.libretune.core.ThemeMode
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * UI state for the Settings screen.
 *
 * @property themeMode          Current theme preference.
 * @property baudRate           Default USB baud rate.
 * @property autoReconnect      Whether auto-reconnect is enabled.
 * @property reconnectMaxAttempts Maximum reconnect attempts.
 * @property reconnectDelayMs    Delay between reconnect attempts.
 * @property appVersionName     Version name from PackageInfo.
 * @property appVersionCode     Version code from PackageInfo.
 * @property exportSuccess      Brief success message after export, or null.
 * @property exportError        Error message after a failed export, or null.
 */
data class SettingsUiState(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val baudRate: Int = AppSettings.DEFAULT_BAUD,
    val autoReconnect: Boolean = true,
    val reconnectMaxAttempts: Int = AppSettings.DEFAULT_MAX_ATTEMPTS,
    val reconnectDelayMs: Long = AppSettings.DEFAULT_DELAY_MS,
    val appVersionName: String = "",
    val appVersionCode: Long = 0L,
    val exportSuccess: String? = null,
    val exportError: String? = null,
    // Units
    val temperatureUnit: String = "°C",
    val pressureUnit: String = "kPa",
    val useLambda: Boolean = false,
    // Table Editor
    val autoSaveTables: Boolean = false,
    val show3DView: Boolean = false,
    val cellHeatmap: Boolean = true,
    val decimalPrecision: Int = 1,
    // Datalogging
    val datalogSampleRate: Int = 50,
    val autoRecordDatalog: Boolean = false,
    // AutoTune
    val autoTuneAuthority: Int = 10,
    val lambdaDelayMs: Long = 200,
    // Developer
    val showDebugOverlay: Boolean = false,
    val verboseLogging: Boolean = false
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settings: AppSettings,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        SettingsUiState(
            appVersionName = readVersionName(),
            appVersionCode = readVersionCode()
        )
    )
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    // Observe all settings flows and merge into a single UI state.
    init {
        viewModelScope.launch {
            settings.themeMode.collect { mode ->
                _uiState.update { it.copy(themeMode = mode) }
            }
        }
        viewModelScope.launch {
            settings.usbBaudRate.collect { baud ->
                _uiState.update { it.copy(baudRate = baud) }
            }
        }
        viewModelScope.launch {
            settings.autoReconnect.collect { enabled ->
                _uiState.update { it.copy(autoReconnect = enabled) }
            }
        }
        viewModelScope.launch {
            settings.reconnectMaxAttempts.collect { max ->
                _uiState.update { it.copy(reconnectMaxAttempts = max) }
            }
        }
        viewModelScope.launch {
            settings.reconnectDelayMs.collect { delay ->
                _uiState.update { it.copy(reconnectDelayMs = delay) }
            }
        }
    }

    // ========================================================================
    //  Theme
    // ========================================================================

    fun setThemeMode(mode: ThemeMode) {
        settings.setThemeMode(mode)
    }

    // ========================================================================
    //  Baud rate
    // ========================================================================

    fun setBaudRate(baud: Int) {
        settings.setUsbBaudRate(baud)
    }

    // ========================================================================
    //  Auto-reconnect
    // ========================================================================

    fun setAutoReconnect(enabled: Boolean) {
        settings.setAutoReconnect(enabled)
    }

    fun setReconnectMaxAttempts(value: Int) {
        settings.setReconnectMaxAttempts(value.coerceIn(1, 20))
    }

    fun setReconnectDelayMs(value: Long) {
        settings.setReconnectDelayMs(value.coerceIn(500L, 30_000L))
    }

    // ========================================================================
    //  Export / Import
    // ========================================================================

    /** Export all settings to a JSON string (clipboard / file share). */
    fun exportSettings() {
        try {
            val json = buildString {
                appendLine("{\n")
                appendLine("  \"themeMode\": \"${settings.themeMode.value.name}\",")
                appendLine("  \"baudRate\": ${settings.usbBaudRate.value},")
                appendLine("  \"autoReconnect\": ${settings.autoReconnect.value},")
                appendLine("  \"reconnectMaxAttempts\": ${settings.reconnectMaxAttempts.value},")
                appendLine("  \"reconnectDelayMs\": ${settings.reconnectDelayMs.value}")
                appendLine("}")
            }
            // Copy to clipboard as a simple export.
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
            if (clipboard != null) {
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("LibreTune Settings", json))
            }
            _uiState.update { it.copy(exportSuccess = "Settings copied to clipboard", exportError = null) }
        } catch (e: Exception) {
            _uiState.update { it.copy(exportError = e.message ?: "Export failed", exportSuccess = null) }
        }
    }

    /** Clear all export/import banners. */
    fun dismissMessages() {
        _uiState.update { it.copy(exportSuccess = null, exportError = null) }
    }

    // ===== Units setters =====
    fun setTemperatureUnit(unit: String) = _uiState.update { it.copy(temperatureUnit = unit) }
    fun setPressureUnit(unit: String) = _uiState.update { it.copy(pressureUnit = unit) }
    fun setUseLambda(value: Boolean) = _uiState.update { it.copy(useLambda = value) }

    // ===== Table Editor setters =====
    fun setAutoSaveTables(value: Boolean) = _uiState.update { it.copy(autoSaveTables = value) }
    fun setShow3DView(value: Boolean) = _uiState.update { it.copy(show3DView = value) }
    fun setCellHeatmap(value: Boolean) = _uiState.update { it.copy(cellHeatmap = value) }
    fun setDecimalPrecision(value: Int) = _uiState.update { it.copy(decimalPrecision = value) }

    // ===== Datalogging setters =====
    fun setDatalogSampleRate(value: Int) = _uiState.update { it.copy(datalogSampleRate = value) }
    fun setAutoRecordDatalog(value: Boolean) = _uiState.update { it.copy(autoRecordDatalog = value) }

    // ===== AutoTune setters =====
    fun setAutoTuneAuthority(value: Int) = _uiState.update { it.copy(autoTuneAuthority = value) }
    fun setLambdaDelay(value: Long) = _uiState.update { it.copy(lambdaDelayMs = value) }

    // ===== Developer setters =====
    fun setShowDebugOverlay(value: Boolean) = _uiState.update { it.copy(showDebugOverlay = value) }
    fun setVerboseLogging(value: Boolean) = _uiState.update { it.copy(verboseLogging = value) }

    // ========================================================================
    //  Helpers
    // ========================================================================

    private fun readVersionName(): String {
        return try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown"
        } catch (_: PackageManager.NameNotFoundException) { "unknown" }
    }

    private fun readVersionCode(): Long {
        return try {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            @Suppress("DEPRECATION")
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                info.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                info.versionCode.toLong()
            }
        } catch (_: PackageManager.NameNotFoundException) { 0L }
    }
}
