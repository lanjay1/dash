package com.ztune.libretune.core.ecu

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * TCP socket transport for ECU communication over a network.
 *
 * Useful for Wi-Fi ECU adapters (e.g. ESP32-based tuners),
 * serial-to-TCP bridges, or ECU simulators running on a remote host.
 */
class TcpTransport(
    private val host: String,
    private val port: Int,
    private val connectTimeoutMs: Int = DEFAULT_CONNECT_TIMEOUT_MS,
    private val readTimeoutMs: Int = DEFAULT_READ_TIMEOUT_MS,
    private val soTimeoutMs: Int = DEFAULT_SO_TIMEOUT_MS
) : EcuTransport {

    private var socket: Socket? = null
    private var inputStream: InputStream? = null
    private var outputStream: OutputStream? = null
    private var _connected = false
    private val mutex = Mutex()

    companion object {
        const val DEFAULT_CONNECT_TIMEOUT_MS = 5_000
        const val DEFAULT_READ_TIMEOUT_MS = 2_000
        const val DEFAULT_SO_TIMEOUT_MS = 2_000
    }

    // ------------------------------------------------------------------ connect
    override suspend fun connect() {
        withContext(Dispatchers.IO) {
            if (_connected) return@withContext

            val sock = Socket()
            try {
                sock.soTimeout = soTimeoutMs
                sock.connect(InetSocketAddress(host, port), connectTimeoutMs)
            } catch (e: IOException) {
                try { sock.close() } catch (_: IOException) {}
                throw TransportException(
                    "Failed to connect to TCP $host:$port", e
                )
            }

            try {
                sock.tcpNoDelay = true
            } catch (_: IOException) {
                // Non-critical; best-effort to reduce latency
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
            try { inputStream?.close() } catch (_: IOException) {}
            try { outputStream?.close() } catch (_: IOException) {}
            try { socket?.close() } catch (_: IOException) {}
            inputStream = null
            outputStream = null
            socket = null
        }
    }

    // --------------------------------------------------------------- isConnected
    override fun isConnected(): Boolean {
        if (!_connected) return false
        val sock = socket ?: return false
        // Socket.isClosed / isOutputShutdown are cheap checks that detect
        // half-closed connections without a round-trip.
        return !sock.isClosed && !sock.isOutputShutdown
    }

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
                    throw TransportException("TCP write failed for $host:$port", e)
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
                                throw TransportException(
                                    "TCP stream closed by $host:$port"
                                )
                            }
                            // n == 0 → no data yet; spin until deadline
                        }
                    } catch (e: IOException) {
                        _connected = false
                        throw TransportException(
                            "TCP read error from $host:$port", e
                        )
                    }
                }

                if (totalRead == 0) {
                    throw TransportException(
                        "TCP read timeout: no data received from $host:$port"
                    )
                }
                buf.copyOf(totalRead)
            }
        }
    }

    // --------------------------------------------------------------- description
    override fun description(): String = "TCP $host:$port"

    override fun transportType(): TransportType = TransportType.TCP
}