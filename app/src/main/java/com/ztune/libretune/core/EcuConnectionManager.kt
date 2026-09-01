package com.ztune.libretune.core

import com.ztune.libretune.core.ecu.EcuInterface
import com.ztune.libretune.core.ecu.EcuTransport
import com.ztune.libretune.core.ini.EcuDefinition
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Possible states of an ECU connection.
 */
enum class EcuConnectionStatus {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    RECONNECTING,
    ERROR
}

/**
 * Snapshot of the current connection state, observed by the UI via [StateFlow].
 */
data class EcuConnectionState(
    val status: EcuConnectionStatus = EcuConnectionStatus.DISCONNECTED,
    val transportName: String? = null,
    val signature: String? = null,
    val lastError: String? = null,
    val reconnectAttempt: Int = 0,
    val reconnectMaxAttempts: Int = 0
)

/**
 * Manages the ECU connection lifecycle: connect, disconnect, auto-reconnect,
 * handshake, and protocol-client access.
 *
 * This is a stub/placeholder that will be expanded with full USB-serial transport
 * support, reconnection logic, and streaming management.
 *
 * @param applicationScope  A [CoroutineScope] bound to the application lifetime.
 */
class EcuConnectionManager(
    private val applicationScope: CoroutineScope,
    private val settings: AppSettings
) {

    private val _state = MutableStateFlow(EcuConnectionState())
    val state: StateFlow<EcuConnectionState> = _state.asStateFlow()

    /** Currently active ECU interface (null when disconnected). */
    var ecuInterface: EcuInterface? = null
        private set

    /** Currently active transport. */
    var transport: EcuTransport? = null
        private set

    /** The parsed ECU definition loaded for the connected ECU. */
    var activeDefinition: EcuDefinition? = null
        private set

    private var connectJob: Job? = null
    private var generation = 0L

    val isConnected: Boolean get() = transport != null

    /**
     * Connect to an ECU using the given [transport] and [definition].
     *
     * For now this is a stub — the real implementation will:
     * 1. Build the ECU interface via [EcuFactory][com.ztune.libretune.core.ecu.EcuFactory]
     * 2. Perform the transport handshake
     * 3. Start the real-time data watcher
     */
    fun connect(transport: EcuTransport, definition: EcuDefinition) {
        disconnectInternal()
        val gen = ++generation
        _state.update {
            it.copy(
                status = EcuConnectionStatus.CONNECTING,
                transportName = transport.description(),
                signature = null,
                lastError = null,
                reconnectAttempt = 0,
                reconnectMaxAttempts = 0
            )
        }
        connectJob = applicationScope.launch {
            try {
                // Stub: mark as connected immediately
                this@EcuConnectionManager.transport = transport
                activeDefinition = definition
                if (gen != generation) {
                    transport.disconnect()
                    return@launch
                }
                _state.update {
                    it.copy(
                        status = EcuConnectionStatus.CONNECTED,
                        transportName = transport.description(),
                        signature = definition.signature.ifEmpty { "(stub)" }
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                this@EcuConnectionManager.transport = null
                activeDefinition = null
                _state.update {
                    it.copy(
                        status = EcuConnectionStatus.ERROR,
                        lastError = e.message ?: e.javaClass.simpleName
                    )
                }
            }
        }
    }

    /**
     * Disconnect from the current ECU and reset state.
     */
    fun disconnect() {
        connectJob?.cancel()
        connectJob = null
        disconnectInternal()
        _state.update {
            EcuConnectionState()
        }
    }

    /**
     * Perform a protocol handshake to identify the ECU.
     *
     * Stub — returns the active definition's signature.
     */
    suspend fun handshake(commandTemplate: String = "Q"): String {
        val sig = activeDefinition?.signature ?: "(no definition)"
        _state.update { it.copy(signature = sig) }
        return sig
    }

    // ------------------------------------------------------------------
    //  Internal
    // ------------------------------------------------------------------

    private fun disconnectInternal() {
        generation++
        connectJob?.cancel()
        connectJob = null
        transport?.let { runCatching { it.disconnect() } }
        transport = null
        ecuInterface = null
        activeDefinition = null
    }
}
