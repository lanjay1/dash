package com.ztune.libretune.core.ecu

/**
 * Abstract transport interface for ECU serial communication.
 * Implementations wrap USB serial, Bluetooth, TCP, or mock transports.
 */
interface EcuTransport {
    /** Connect to the ECU */
    suspend fun connect()

    /** Disconnect from the ECU */
    suspend fun disconnect()

    /** Whether the transport is currently connected */
    fun isConnected(): Boolean

    /** Send raw bytes to the ECU */
    suspend fun send(data: ByteArray)

    /** 
     * Receive bytes from the ECU.
     * @param expectedLength Hint for how many bytes to expect. May time out earlier.
     * @return The received bytes.
     */
    suspend fun receive(expectedLength: Int = 256): ByteArray

    /** Human-readable description of this transport */
    fun description(): String

    /** The transport type identifier */
    fun transportType(): TransportType
}

enum class TransportType {
    USB_SERIAL,
    BLUETOOTH,
    TCP,
    MOCK
}

/**
 * Exception thrown when a transport operation fails (connect / disconnect /
 * send / receive).  Wraps the underlying I/O error if any.
 */
class TransportException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Exception thrown when an ECU protocol-level error occurs: bad or unexpected
 * response framing, CRC mismatch, JSON error response from the ECU, missing
 * required fields, etc.
 *
 * Distinct from [TransportException] so callers can differentiate between
 * "couldn't talk to the ECU" and "ECU replied but the reply was invalid".
 */
class ProtocolException(message: String, cause: Throwable? = null) : Exception(message, cause)