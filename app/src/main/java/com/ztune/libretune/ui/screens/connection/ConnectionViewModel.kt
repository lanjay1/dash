@file:Suppress("unused")

package com.ztune.libretune.ui.screens.connection

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.ztune.libretune.core.AppSettings
import com.ztune.libretune.core.EcuConnectionManager
import com.ztune.libretune.core.ecu.EcuTransport
import com.ztune.libretune.core.ecu.MockEcuTransport
import com.ztune.libretune.core.ecu.UsbSerialTransport
import com.ztune.libretune.core.ini.EcuDefinition
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * A discovered USB serial device for display in the connection list.
 */
data class UsbDeviceInfo(
    val name: String,
    val deviceId: Int,
    val vendorId: Int,
    val productId: Int,
    val usbDevice: UsbDevice? = null
)

/**
 * UI state for the Connection screen.
 *
 * @property availableDevices    List of detected USB serial devices.
 * @property selectedDeviceIndex Index into [availableDevices] of the user's selection.
 * @property baudRate            Currently selected baud rate.
 * @property isConnecting        Whether a connection attempt is in progress.
 * @property connectionError     Human-readable error string, or null.
 * @property isConnected         Whether the ECU is currently connected.
 * @property connectionSignature ECU signature after successful handshake.
 */
data class ConnectionUiState(
    val availableDevices: List<UsbDeviceInfo> = emptyList(),
    val selectedDeviceIndex: Int = -1,
    val baudRate: Int = 115200,
    val isConnecting: Boolean = false,
    val connectionError: String? = null,
    val isConnected: Boolean = false,
    val connectionSignature: String? = null
)

/**
 * Common baud rates for ECU serial communication.
 */
val BAUD_RATES = listOf(
    9600, 19200, 38400, 57600, 115200, 230400, 460800, 921600
)

@HiltViewModel
class ConnectionViewModel @Inject constructor(
    private val connectionManager: EcuConnectionManager,
    private val settings: AppSettings,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(ConnectionUiState())
    val uiState: StateFlow<ConnectionUiState> = _uiState.asStateFlow()

    /** Emitted once when a connection succeeds so the UI can navigate away. */
    private val _navigateToDashboard = MutableStateFlow(false)
    val navigateToDashboard: StateFlow<Boolean> = _navigateToDashboard.asStateFlow()

    init {
        // Load the saved default baud rate.
        _uiState.update { it.copy(baudRate = settings.usbBaudRate.value) }

        // Observe the saved baud rate for live updates from settings.
        viewModelScope.launch {
            settings.usbBaudRate.collect { baud ->
                _uiState.update { it.copy(baudRate = baud) }
            }
        }

        // Observe the ECU connection manager state.
        viewModelScope.launch {
            connectionManager.state.collect { connState ->
                _uiState.update { prev ->
                    prev.copy(
                        isConnecting = connState.status == com.ztune.libretune.core.EcuConnectionStatus.CONNECTING,
                        isConnected = connState.status == com.ztune.libretune.core.EcuConnectionStatus.CONNECTED,
                        connectionError = connState.lastError,
                        connectionSignature = connState.signature
                    )
                }
                // Auto-navigate to dashboard on successful connection.
                if (connState.status == com.ztune.libretune.core.EcuConnectionStatus.CONNECTED) {
                    _navigateToDashboard.update { true }
                }
            }
        }

        // Initial device scan.
        refreshDevices()
    }

    // ========================================================================
    //  Public actions
    // ========================================================================

    /** Enumerate available USB serial devices. */
    fun refreshDevices() {
        val usbManager = context.getSystemService(Context.USB_SERVICE) as? UsbManager
        val devices = mutableListOf<UsbDeviceInfo>()

        if (usbManager != null) {
            val drivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
            for (driver in drivers) {
                val dev = driver.device
                devices.add(
                    UsbDeviceInfo(
                        name = buildString {
                            append(driver.javaClass.simpleName)
                            if (dev.productName != null) append(" – ${dev.productName}")
                        },
                        deviceId = dev.deviceId,
                        vendorId = dev.vendorId,
                        productId = dev.productId,
                        usbDevice = dev
                    )
                )
            }
        }

        _uiState.update { prev ->
            val newIdx = if (prev.selectedDeviceIndex >= devices.size) -1
            else prev.selectedDeviceIndex
            prev.copy(availableDevices = devices, selectedDeviceIndex = newIdx)
        }
    }

    /** Select a device from the list by index. */
    fun selectDevice(index: Int) {
        _uiState.update { it.copy(selectedDeviceIndex = index, connectionError = null) }
    }

    /** Update the baud rate. */
    fun setBaudRate(baud: Int) {
        settings.setUsbBaudRate(baud)
        // The settings flow collector will update uiState, but we also set it
        // eagerly so the UI responds immediately.
        _uiState.update { it.copy(baudRate = baud) }
    }

    /** Connect to the selected USB device. */
    fun connectToDevice() {
        val state = _uiState.value
        val deviceInfo = state.availableDevices.getOrNull(state.selectedDeviceIndex)
        val usbDevice = deviceInfo?.usbDevice
            ?: run {
                _uiState.update { it.copy(connectionError = "No device selected") }
                return
            }

        val usbManager = context.getSystemService(Context.USB_SERVICE) as? UsbManager
            ?: run {
                _uiState.update { it.copy(connectionError = "USB service unavailable") }
                return
            }

        // Check USB permission.
        if (!usbManager.hasPermission(usbDevice)) {
            _uiState.update { it.copy(connectionError = "USB permission not granted. Please accept the permission dialog.") }
            // Note: In a full implementation, we would launch a PendingIntent via
            // UsbManager.requestPermission() here and handle the result.
            return
        }

        _uiState.update { it.copy(isConnecting = true, connectionError = null) }

        viewModelScope.launch {
            try {
                val transport = UsbSerialTransport(
                    usbManager = usbManager,
                    device = usbDevice,
                    baudRate = state.baudRate
                )
                transport.connect()

                // Use a default definition for now — real flow would query the
                // ECU signature and match it via EcuDefinitionRepository.
                val definition = EcuDefinition.default()
                connectionManager.connect(transport, definition)
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isConnecting = false,
                        connectionError = e.message ?: "Connection failed"
                    )
                }
            }
        }
    }

    /** Connect to a demo/mock ECU for testing without hardware. */
    fun connectDemoEcu() {
        _uiState.update { it.copy(isConnecting = true, connectionError = null) }

        viewModelScope.launch {
            delay(600) // simulate handshake delay for visual feedback
            try {
                val transport = MockEcuTransport()
                transport.connect()

                val definition = EcuDefinition.default()
                connectionManager.connect(transport, definition)
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isConnecting = false,
                        connectionError = e.message ?: "Demo connection failed"
                    )
                }
            }
        }
    }

    /** Disconnect the currently connected ECU. */
    fun disconnect() {
        connectionManager.disconnect()
        _uiState.update {
            it.copy(
                isConnecting = false,
                isConnected = false,
                connectionError = null,
                connectionSignature = null
            )
        }
    }

    /** Clear the navigation flag after the UI has handled it. */
    fun clearNavigationFlag() {
        _navigateToDashboard.update { false }
    }

    /** Dismiss the current error banner. */
    fun dismissError() {
        _uiState.update { it.copy(connectionError = null) }
    }
}
