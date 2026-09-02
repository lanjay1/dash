package com.ztune.libretune.core.ecu

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** USB serial transport using usb-serial-for-android library */
class UsbSerialTransport(
    private val usbManager: UsbManager,
    private val device: UsbDevice,
    private val baudRate: Int = DEFAULT_BAUD_RATE,
    private val dataBits: Int = UsbSerialPort.DATABITS_8,
    private val stopBits: Int = UsbSerialPort.STOPBITS_1,
    private val parity: Int = UsbSerialPort.PARITY_NONE
) : EcuTransport {

    private var port: UsbSerialPort? = null
    private var _connected = false
    private val mutex = Mutex()

    companion object {
        const val DEFAULT_BAUD_RATE = 115200
        const val READ_TIMEOUT_MS = 200
        const val WRITE_TIMEOUT_MS = 200
    }

    override suspend fun connect() {
        withContext(Dispatchers.IO) {
            val drivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
            val driver = drivers.find { it.device == device }
                ?: throw TransportException(
                    "No USB serial driver found for device ${device.deviceName}"
                )

            val connection = usbManager.openDevice(device)
                ?: throw TransportException("Failed to open USB device. Check permissions.")

            val p = driver.ports.firstOrNull()
                ?: throw TransportException("No serial ports on device")

            p.open(connection)
            p.setParameters(baudRate, dataBits, stopBits, parity)
            p.dtr = true
            p.rts = true
            port = p
            _connected = true
        }
    }

    override suspend fun disconnect() {
        withContext(Dispatchers.IO) {
            try {
                port?.close()
            } catch (_: Exception) {
                // Swallow close errors
            }
            port = null
            _connected = false
        }
    }

    override fun isConnected(): Boolean = _connected

    override suspend fun send(data: ByteArray) {
        mutex.withLock {
            val p = port ?: throw TransportException("Not connected")
            withContext(Dispatchers.IO) {
                p.write(data, WRITE_TIMEOUT_MS)
            }
        }
    }

    override suspend fun receive(expectedLength: Int): ByteArray {
        return mutex.withLock {
            val p = port ?: throw TransportException("Not connected")
            withContext(Dispatchers.IO) {
                val capped = expectedLength.coerceAtMost(4096)
                val buf = ByteArray(capped)
                var totalRead = 0
                val deadline = System.currentTimeMillis() + READ_TIMEOUT_MS

                while (totalRead < capped && System.currentTimeMillis() < deadline) {
                    val remaining = capped - totalRead
                    val chunkSize = remaining.coerceAtMost(512)
                    val tmpBuf = ByteArray(chunkSize)
                    val n = p.read(tmpBuf, READ_TIMEOUT_MS)
                    when {
                        n > 0 -> {
                            System.arraycopy(tmpBuf, 0, buf, totalRead, n)
                            totalRead += n
                        }
                        n < 0 -> throw TransportException("USB read error")
                        // n == 0: spin until timeout
                    }
                }

                if (totalRead == 0) {
                    throw TransportException("Read timeout: no data received")
                }
                buf.copyOf(totalRead)
            }
        }
    }

    override fun description(): String =
        "USB Serial: ${device.deviceName} @ ${baudRate}baud"

    override fun transportType(): TransportType = TransportType.USB_SERIAL

    /** List all available USB serial devices */
    fun availableDevices(): List<UsbDevice> {
        return UsbSerialProber.getDefaultProber()
            .findAllDrivers(usbManager)
            .map { it.device }
    }
}
