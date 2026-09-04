@file:Suppress("unused")

package com.ztune.libretune.ui.screens.connection

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.ztune.libretune.core.AppSettings
import com.ztune.libretune.core.EcuConnectionManager
import com.ztune.libretune.core.EcuDefinitionRepository
import com.ztune.libretune.core.ecu.EcuFactory
import com.ztune.libretune.core.ecu.EcuTransport
import com.ztune.libretune.core.ecu.MockEcuTransport
import com.ztune.libretune.core.ecu.UsbSerialTransport
import com.ztune.libretune.core.ini.EcuDefinition
import com.ztune.libretune.core.protocol.ms.MsProtocolClient
import com.ztune.libretune.core.protocol.ms.ProtocolMode
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import javax.inject.Inject
import kotlin.coroutines.resume

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
    private val definitionRepository: EcuDefinitionRepository,
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

    /**
     * Connect to the selected USB device.
     *
     * Flow:
     *   1. Validate device selection
     *   2. Check USB permission — if not granted, request via [UsbManager.requestPermission]
     *      with a [BroadcastReceiver] + [PendingIntent], and suspend until the user
     *      accepts/denies.
     *   3. Open transport
     *   4. Identify ECU + resolve INI definition (Phase 1)
     *   5. Connect via [EcuConnectionManager]
     */
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

        _uiState.update { it.copy(isConnecting = true, connectionError = null) }

        viewModelScope.launch {
            try {
                // ---- Phase 2: USB permission flow ----
                // If permission not yet granted, request it and suspend until
                // the user accepts or denies. Cancellation-safe: if the user
                // navigates away (viewModelScope cancelled), the receiver is
                // unregistered and the coroutine resumes with false.
                if (!usbManager.hasPermission(usbDevice)) {
                    Log.i(TAG, "Requesting USB permission for device ${usbDevice.deviceName}")
                    val granted = requestUsbPermission(usbManager, usbDevice)
                    if (!granted) {
                        _uiState.update {
                            it.copy(
                                isConnecting = false,
                                connectionError = "USB permission denied by user"
                            )
                        }
                        return@launch
                    }
                    Log.i(TAG, "USB permission granted for device ${usbDevice.deviceName}")
                }

                // ---- Open transport ----
                val transport = UsbSerialTransport(
                    usbManager = usbManager,
                    device = usbDevice,
                    baudRate = state.baudRate
                )
                transport.connect()

                // ---- Phase 1: Identify ECU and load matching definition ----
                val definition = identifyAndResolveDefinition(transport)
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

    // ========================================================================
    //  Phase 2: USB permission flow
    // ========================================================================

    /**
     * Request USB permission for [device] from the user via a system dialog.
     *
     * Registers a [BroadcastReceiver] for [UsbManager.ACTION_USB_PERMISSION],
     * launches the permission [PendingIntent] via [UsbManager.requestPermission],
     * and suspends until the user accepts or denies (or the coroutine is
     * cancelled).
     *
     * This function is cancellation-safe: if the coroutine is cancelled
     * (e.g. user navigates away, ViewModel cleared), the receiver is
     * unregistered and the coroutine resumes with `false`.
     *
     * @return `true` if permission was granted, `false` if denied or
     *         cancelled.
     */
    private suspend fun requestUsbPermission(
        usbManager: UsbManager,
        device: UsbDevice
    ): Boolean = suspendCancellableCoroutine { cont ->

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action != UsbManager.ACTION_USB_PERMISSION) return

                val granted = intent.getBooleanExtra(
                    UsbManager.EXTRA_PERMISSION_GRANTED, false
                )
                val dev = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)

                Log.i(TAG, "USB permission broadcast: granted=$granted, device=${dev?.deviceName}")

                // Unregister self before resuming to avoid double-unregister
                // when the coroutine is also cancelled.
                runCatching {
                    context?.unregisterReceiver(this)
                }

                if (cont.isActive) {
                    cont.resume(granted)
                }
            }
        }

        // Register receiver. Use RECEIVER_NOT_EXPORTED on API 33+ since the
        // broadcast comes from the system (UsbManager), not from another app.
        val filter = IntentFilter(UsbManager.ACTION_USB_PERMISSION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(
                receiver,
                filter,
                Context.RECEIVER_NOT_EXPORTED
            )
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(receiver, filter)
        }

        // Build the PendingIntent that the system will use to deliver the
        // permission result to our receiver.
        val intent = Intent(UsbManager.ACTION_USB_PERMISSION).apply {
            setPackage(context.packageName) // explicit broadcast to our own package
        }
        val pendingIntentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            0,
            intent,
            pendingIntentFlags
        )

        // If the coroutine is cancelled, unregister the receiver so we don't
        // leak it and don't resume after the caller is gone.
        cont.invokeOnCancellation {
            runCatching {
                context.unregisterReceiver(receiver)
            }
        }

        // Request permission — this shows the system permission dialog.
        usbManager.requestPermission(device, pendingIntent)
    }

    /**
     * Connect to a demo/mock ECU for testing without hardware.
     *
     * The [MockEcuTransport] implements the RAW MS serial protocol command
     * set (Q/A/r/w/B with big-endian offsets). [identifyAndResolveDefinition]
     * uses [MsProtocolClient] in [ProtocolMode.RAW] for the initial signature
     * query, so demo mode should now work end-to-end:
     *   1. MockEcuTransport responds to 'Q' with "Speeduino 202401" signature
     *   2. EcuDefinitionRepository matches it against the bundled placeholder INI
     *   3. EcuConnectionManager.connect proceeds with the matched definition
     *   4. Realtime streaming via 'A' command returns a realistic 20-byte block
     *
     * BUILD-UNVERIFIED: This flow is statically reviewed but has not been
     * tested at runtime. The mock returns fixed data — no real ECU behavior
     * is simulated (no RPM changes, no sensor variation).
     */
    fun connectDemoEcu() {
        _uiState.update { it.copy(isConnecting = true, connectionError = null) }

        viewModelScope.launch {
            delay(600) // simulate handshake delay for visual feedback
            try {
                // Use a realistic Speeduino signature so the definition lookup succeeds
                // against the bundled placeholder INI (once MockEcuTransport supports
                // envelope framing — see note above).
                val transport = MockEcuTransport(signature = "Speeduino 202401")
                transport.connect()

                // ---- Phase 1: Identify ECU and load matching definition ----
                val definition = identifyAndResolveDefinition(transport)
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

    // ========================================================================
    //  Phase 1: ECU identification + definition resolution
    // ========================================================================

    /**
     * Identify the ECU by querying its signature, then look up the matching
     * INI definition in [definitionRepository].
     *
     * This performs a lightweight handshake using [MsProtocolClient] directly
     * (rather than going through a full [EcuInterface] lifecycle) to send the
     * 'Q' (query signature) command. The transport is left open for the
     * subsequent [EcuConnectionManager.connect] call.
     *
     * Why not use EcuFactory.create() + EcuInterface.connect()?
     *   - EcuInterface.connect() calls transport.connect() again (redundant)
     *   - EcuInterface.disconnect() calls transport.disconnect() (would close
     *     the port we need for the real connection)
     *   - We only need one command (Q), so a full ECU lifecycle is overkill.
     *
     * Fallback behavior:
     *   - If signature query fails → return [EcuDefinition.default] (empty).
     *     The connection will still proceed, but with no tables/channels/menu.
     *   - If no matching INI is found in the repository → return a definition
     *     whose signature is set to the queried value, but with all other
     *     fields at default. The connection proceeds; UI shows a warning.
     *
     * @param transport Already-connected [EcuTransport].
     * @return The resolved [EcuDefinition] (never null).
     */
    private suspend fun identifyAndResolveDefinition(transport: EcuTransport): EcuDefinition {
        // 1. Query signature via MsProtocolClient in RAW mode.
        //    RAW mode (no framing, no CRC) is the correct default for the
        //    initial identification — MS/Speeduino use raw serial, and the
        //    'Q' signature command is the same across all MS-family ECUs.
        //    Once the ECU type is identified, the proper EcuInterface
        //    (MegaSquirtEcu/SpeeduinoEcu/FomeEcu) is created with the
        //    correct protocol mode.
        val protocolClient = MsProtocolClient(transport, ProtocolMode.RAW)
        val sigResult = protocolClient.querySignature()

        val signature = sigResult.getOrNull()
        if (signature.isNullOrEmpty()) {
            Log.w(TAG, "Signature query failed or returned empty: ${sigResult.exceptionOrNull()}")
            return EcuDefinition.default()
        }

        Log.i(TAG, "ECU signature: '$signature'")

        // 2. Look up matching INI definition in the repository (bundled assets).
        val matched = definitionRepository.findDefinitionForSignature(signature)
        if (matched != null) {
            Log.i(TAG, "Matched definition: sig=${matched.signature}, " +
                "tables=${matched.tables.size}, channels=${matched.outputChannels.size}")
            // Ensure the definition's signature field reflects the actual ECU
            // (in case of prefix match where def.signature is a prefix).
            if (matched.signature != signature) {
                matched.signature = signature
            }
            return matched
        }

        // 3. Fallback: no matching INI in assets. Return a definition with the
        //    queried signature so downstream consumers can at least see what
        //    the ECU reported. The connection proceeds but with no tables/channels.
        Log.w(TAG, "No matching INI definition found for signature '$signature'. " +
            "Falling back to empty definition with signature set.")
        val fallback = EcuDefinition.default()
        fallback.signature = signature
        fallback.ecuType = EcuFactory.detectFromSignature(signature)
        return fallback
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

    private companion object {
        private const val TAG = "ConnectionVM"
    }
}
