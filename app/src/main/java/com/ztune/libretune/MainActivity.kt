package com.ztune.libretune

import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.ztune.libretune.core.AppSettings
import com.ztune.libretune.core.ThemeMode
import com.ztune.libretune.ui.navigation.LibreTuneApp
import com.ztune.libretune.ui.theme.LibreTuneTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Single-Activity entry point for LibreTune.
 *
 * Annotated with [AndroidEntryPoint] so Hilt can inject dependencies into
 * this Activity and any Compose ViewModels that use `@HiltViewModel`.
 *
 * Uses `launchMode="singleTask"` (declared in AndroidManifest) so that
 * USB_DEVICE_ATTACHED intents are delivered to the existing instance via
 * [onNewIntent] instead of launching a duplicate Activity.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var settings: AppSettings

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val themeMode by settings.themeMode.collectAsState()

            LibreTuneTheme(
                darkTheme = when (themeMode) {
                    ThemeMode.SYSTEM -> isSystemInDarkTheme()
                    ThemeMode.LIGHT -> false
                    ThemeMode.DARK -> true
                }
            ) {
                LibreTuneApp()
            }
        }

        // Handle USB_DEVICE_ATTACHED intent that launched this Activity
        // (cold start via device_filter.xml auto-launch).
        handleUsbIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Handle USB_DEVICE_ATTACHED intent delivered to the running instance
        // (warm start — app already running when ECU plugged in).
        setIntent(intent)
        handleUsbIntent(intent)
    }

    /**
     * Process a USB_DEVICE_ATTACHED intent.
     *
     * When the user plugs in a USB device and ZTune is registered as the
     * handler (via `device_filter.xml` + `USB_DEVICE_ATTACHED` intent filter
     * in AndroidManifest), Android delivers the device as an extra in the
     * intent.
     *
     * Currently we just log the event — a future Phase will route this to
     * the ConnectionViewModel so the user is taken directly to the connection
     * screen with the device pre-selected.
     */
    private fun handleUsbIntent(intent: Intent?) {
        if (intent?.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) {
            val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE) as? UsbDevice
            }
            Log.i(TAG, "USB device attached: ${device?.deviceName} " +
                "(VID=0x${device?.vendorId?.toString(16)}, PID=0x${device?.productId?.toString(16)})")
            // TODO (Phase 2+): route this device to ConnectionViewModel for
            // auto-selection in the device list. For now, the user manually
            // taps Refresh on the Connection screen to see the new device.
        }
    }

    private companion object {
        private const val TAG = "MainActivity"
    }
}
