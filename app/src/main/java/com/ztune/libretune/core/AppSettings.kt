package com.ztune.libretune.core

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Theme preference modes.
 */
enum class ThemeMode { SYSTEM, LIGHT, DARK }

/**
 * Application settings backed by [android.content.SharedPreferences].
 *
 * Exposes a [StateFlow] for each setting so the UI can reactively observe
 * changes (e.g. theme toggling takes effect immediately).
 *
 * This class is constructed manually (not via Hilt) and provided as a
 * `@Singleton` through [di.AppModule].
 */
class AppSettings(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ------------------------------------------------------------------
    //  Theme
    // ------------------------------------------------------------------

    private val _themeMode = MutableStateFlow(
        readEnum(K_THEME, ThemeMode.SYSTEM) { ThemeMode.valueOf(it) }
    )
    val themeMode: StateFlow<ThemeMode> = _themeMode.asStateFlow()

    fun setThemeMode(mode: ThemeMode) {
        prefs.edit { putString(K_THEME, mode.name) }
        _themeMode.update { mode }
    }

    // ------------------------------------------------------------------
    //  USB / Connection
    // ------------------------------------------------------------------

    private val _usbBaudRate = MutableStateFlow(
        read(K_BAUD, DEFAULT_BAUD) { it.toInt() }
    )
    val usbBaudRate: StateFlow<Int> = _usbBaudRate.asStateFlow()

    fun setUsbBaudRate(baud: Int) {
        prefs.edit { putString(K_BAUD, baud.toString()) }
        _usbBaudRate.update { baud }
    }

    private val _autoReconnect = MutableStateFlow(
        prefs.getBoolean(K_AUTO_RECONNECT, true)
    )
    val autoReconnect: StateFlow<Boolean> = _autoReconnect.asStateFlow()

    fun setAutoReconnect(enabled: Boolean) {
        prefs.edit { putBoolean(K_AUTO_RECONNECT, enabled) }
        _autoReconnect.update { enabled }
    }

    private val _reconnectMaxAttempts = MutableStateFlow(
        read(K_MAX_ATTEMPTS, DEFAULT_MAX_ATTEMPTS) { it.toInt() }
    )
    val reconnectMaxAttempts: StateFlow<Int> = _reconnectMaxAttempts.asStateFlow()

    fun setReconnectMaxAttempts(value: Int) {
        prefs.edit { putString(K_MAX_ATTEMPTS, value.toString()) }
        _reconnectMaxAttempts.update { value }
    }

    private val _reconnectDelayMs = MutableStateFlow(
        read(K_DELAY_MS, DEFAULT_DELAY_MS) { it.toLong() }
    )
    val reconnectDelayMs: StateFlow<Long> = _reconnectDelayMs.asStateFlow()

    fun setReconnectDelayMs(value: Long) {
        prefs.edit { putString(K_DELAY_MS, value.toString()) }
        _reconnectDelayMs.update { value }
    }

    // ------------------------------------------------------------------
    //  Helpers
    // ------------------------------------------------------------------

    private fun <T> read(key: String, fallback: T, convert: (String) -> T?): T =
        prefs.getString(key, null)?.let { runCatching { convert(it) }.getOrNull() } ?: fallback

    private inline fun <reified T : Enum<T>> readEnum(key: String, fallback: T, convert: (String) -> T?): T =
        prefs.getString(key, null)?.let { runCatching { convert(it) }.getOrNull() } ?: fallback

    companion object {
        private const val PREFS_NAME = "libretune_settings"
        private const val K_THEME = "theme_mode"
        private const val K_BAUD = "usb_baud_rate"
        private const val K_AUTO_RECONNECT = "auto_reconnect"
        private const val K_MAX_ATTEMPTS = "reconnect_max_attempts"
        private const val K_DELAY_MS = "reconnect_delay_ms"

        const val DEFAULT_BAUD = 115200
        const val DEFAULT_MAX_ATTEMPTS = 5
        const val DEFAULT_DELAY_MS = 2_000L
    }
}
