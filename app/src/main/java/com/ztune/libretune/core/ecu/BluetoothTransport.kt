package com.ztune.libretune.core.ecu

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/**
 * Bluetooth Classic (SPP / RFCOMM) transport for ECU communication.
 *
 * Requires `android.permission.BLUETOOTH_CONNECT` on Android 12+.
 * Connects to a paired Bluetooth device and opens an RFCOMM socket
 * using the standard SPP UUID.
 */
class BluetoothTransport(
    private val context: Context,
    private val deviceNameOrAddress: String,
    private val connectTimeoutMs: Int = DEFAULT_CONNECT_TIMEOUT_MS,
    private val readTimeoutMs: Int = DEFAULT_READ_TIMEOUT_MS
) : EcuTransport {

    private var socket: BluetoothSocket? = null
    private var inputStream: InputStream? = null
    private var outputStream: OutputStream? = null
    private var _connected = false
    private val mutex = Mutex()

    companion object {
        /** Standard SPP (Serial Port Profile) UUID used by virtually all ECU adapters. */
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

        const val DEFAULT_CONNECT_TIMEOUT_MS = 10_000
        const val DEFAULT_READ_TIMEOUT_MS = 2_000
    }

    // ------------------------------------------------------------------ connect
    @SuppressLint("MissingPermission")
    override suspend fun connect() {
        withContext(Dispatchers.IO) {
            if (_connected) return@withContext

            val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE)
                    as? BluetoothManager
                ?: throw TransportException("Bluetooth not available on this device")

            val adapter: BluetoothAdapter = bluetoothManager.adapter
                ?: throw TransportException("Bluetooth adapter not available")

            if (!adapter.isEnabled) {
                throw TransportException("Bluetooth is disabled. Please enable Bluetooth first.")
            }

            val target: BluetoothDevice = resolveDevice(adapter, deviceNameOrAddress)

            // Attempt insecure RFCOMM first, fall back to secure.
            val sock: BluetoothSocket = try {
                target.createRfcommSocketToServiceRecord(SPP_UUID)
            } catch (e: IOException) {
                throw TransportException(
                    "Failed to create RFCOMM socket for ${target.address}", e
                )
            }

            try {
                // NOTE: BluetoothSocket has no built-in connect timeout.
                // The blocking connect() runs on Dispatchers.IO; callers should
                // cancel the surrounding coroutine (or wrap in withTimeout)
                // if a bounded connect deadline is required.
                sock.connect()
            } catch (e: IOException) {
                try { sock.close() } catch (_: IOException) {}
                throw TransportException(
                    "Failed to connect to Bluetooth device ${target.name ?: target.address}",
                    e
                )
            }

            socket = sock
            inputStream = sock.inputStream
            outputStream = sock.outputStream
            _connected = true
        }
    }

    // --------------------------------------------------------------- disconnect
    override suspend fun disconnect() {
        withContext(Dispatchers.IO) {
            _connected = false
            try {
                inputStream?.close()
            } catch (_: IOException) {}
            try {
                outputStream?.close()
            } catch (_: IOException) {}
            try {
                socket?.close()
            } catch (_: IOException) {}
            inputStream = null
            outputStream = null
            socket = null
        }
    }

    // --------------------------------------------------------------- isConnected
    override fun isConnected(): Boolean = _connected

    // -------------------------------------------------------------------- send
    override suspend fun send(data: ByteArray) {
        mutex.withLock {
            val out = outputStream
                ?: throw TransportException("Not connected")
            withContext(Dispatchers.IO) {
                try {
                    out.write(data)
                    out.flush()
                } catch (e: IOException) {
                    _connected = false
                    throw TransportException("Bluetooth write failed", e)
                }
            }
        }
    }

    // ------------------------------------------------------------------ receive
    override suspend fun receive(expectedLength: Int): ByteArray {
        return mutex.withLock {
            val input = inputStream
                ?: throw TransportException("Not connected")
            withContext(Dispatchers.IO) {
                val capped = expectedLength.coerceIn(1, 4096)
                val buf = ByteArray(capped)
                var totalRead = 0
                val deadline = System.currentTimeMillis() + readTimeoutMs

                while (totalRead < capped && System.currentTimeMillis() < deadline) {
                    val remaining = capped - totalRead
                    val chunkSize = remaining.coerceAtMost(512)
                    try {
                        val n = input.read(buf, totalRead, chunkSize)
                        when {
                            n > 0 -> totalRead += n
                            n < 0 -> {
                                _connected = false
                                throw TransportException("Bluetooth stream closed by remote")
                            }
                            // n == 0 → no data yet; spin until deadline
                        }
                    } catch (e: IOException) {
                        _connected = false
                        throw TransportException("Bluetooth read error", e)
                    }
                }

                if (totalRead == 0) {
                    throw TransportException("Bluetooth read timeout: no data received")
                }
                buf.copyOf(totalRead)
            }
        }
    }

    // --------------------------------------------------------------- description
    override fun description(): String {
        val name = socket?.remoteDevice?.name
        val address = socket?.remoteDevice?.address ?: deviceNameOrAddress
        return if (name != null) "BT: $name ($address)" else "BT: $address"
    }

    override fun transportType(): TransportType = TransportType.BLUETOOTH

    // --------------------------------------------------------- private helpers

    /**
     * Resolve a device name or MAC address string to a [BluetoothDevice].
     * First tries an exact MAC address match, then falls back to a
     * case-insensitive name search among paired devices.
     */
    @SuppressLint("MissingPermission")
    private fun resolveDevice(adapter: BluetoothAdapter, target: String): BluetoothDevice {
        // Direct address lookup
        if (BluetoothAdapter.checkBluetoothAddress(target)) {
            val device = adapter.getRemoteDevice(target)
            if (device in adapter.bondedDevices) {
                return device
            }
            throw TransportException(
                "Bluetooth device $target is not paired. Pair it in system settings first."
            )
        }

        // Name-based lookup among bonded devices
        val matched = adapter.bondedDevices.firstOrNull {
            it.name?.equals(target, ignoreCase = true) == true
        }
        if (matched != null) return matched

        throw TransportException(
            "No paired Bluetooth device found matching \"$target\". " +
                "Available paired devices: ${adapter.bondedDevices.map { "${it.name} (${it.address})" }}"
        )
    }

    /** List the names and addresses of all currently paired Bluetooth devices. */
    @SuppressLint("MissingPermission")
    fun pairedDevices(): List<PairedBluetoothDevice> {
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE)
                as? BluetoothManager
            ?: return emptyList()
        val adapter = manager.adapter ?: return emptyList()
        return adapter.bondedDevices.map {
            PairedBluetoothDevice(name = it.name ?: "Unknown", address = it.address)
        }
    }

    /** Simple data class describing a paired Bluetooth device. */
    data class PairedBluetoothDevice(val name: String, val address: String)
}
