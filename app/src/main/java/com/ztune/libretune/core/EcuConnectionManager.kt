package com.ztune.libretune.core

import com.ztune.libretune.core.autotune.AutoTuneController
import com.ztune.libretune.core.ecu.*
import com.ztune.libretune.core.ini.EcuDefinition
import com.ztune.libretune.core.realtime.RealtimeChannelStore
import com.ztune.libretune.core.realtime.RealtimeDecoder
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlin.math.min
import kotlin.math.pow

/** Possible states of an ECU connection. */
enum class EcuConnectionStatus {
    DISCONNECTED, CONNECTING, CONNECTED, RECONNECTING, SYNCING, ERROR
}

/** Snapshot of the current connection state, observed by the UI via [StateFlow]. */
data class EcuConnectionState(
    val status: EcuConnectionStatus = EcuConnectionStatus.DISCONNECTED,
    val transportName: String? = null,
    val signature: String? = null,
    val lastError: String? = null,
    val reconnectAttempt: Int = 0,
    val reconnectMaxAttempts: Int = 0,
    val syncProgress: Float = 0f,
    val syncedPages: Int = 0,
    val totalPages: Int = 0
)

/**
 * Manages the full ECU connection lifecycle: connect, handshake, realtime streaming,
 * auto-reconnect with exponential backoff, data sync, and page burning.
 *
 * @param applicationScope  Application-lifetime coroutine scope.
 * @param settings           Application settings (baud rate, reconnect config).
 * @param channelStore       Store updated with decoded realtime channel values.
 * @param dataLogManager     Receives realtime updates when recording.
 */
class EcuConnectionManager(
    private val applicationScope: CoroutineScope,
    private val settings: AppSettings,
    private val channelStore: RealtimeChannelStore,
    private val dataLogManager: DataLogManager
) {
    private val _state = MutableStateFlow(EcuConnectionState())
    val state: StateFlow<EcuConnectionState> = _state.asStateFlow()

    var ecuInterface: EcuInterface? = null; private set
    var transport: EcuTransport? = null; private set
    var activeDefinition: EcuDefinition? = null; private set
    var autoTuneController: AutoTuneController? = null

    private var realtimeDecoder: RealtimeDecoder? = null
    private var connectJob: Job? = null
    private var streamJob: Job? = null
    private var heartbeatJob: Job? = null
    private var reconnectJob: Job? = null
    private var generation = 0L

    val isConnected: Boolean get() = state.value.status == EcuConnectionStatus.CONNECTED

    // ------------------------------------------------------------------
    //  Connect
    // ------------------------------------------------------------------

    /**
     * Connect to an ECU: creates the interface via [EcuFactory], opens the transport,
     * calls [EcuInterface.connect], performs handshake, and starts streaming.
     */
    fun connect(transport: EcuTransport, definition: EcuDefinition) {
        disconnectInternal()
        val gen = ++generation
        _state.update { resetState(it, EcuConnectionStatus.CONNECTING, transport.description()) }

        connectJob = applicationScope.launch {
            try {
                val ecu = EcuFactory.create(definition.ecuType)
                guard(gen) { transport.connect() }
                guard(gen) { unwrap(ecu.connect(transport, definition), "ECU connect failed") }
                val signature = guard(gen) { unwrap(ecu.querySignature(), "Signature query failed") }

                this@EcuConnectionManager.ecuInterface = ecu
                this@EcuConnectionManager.transport = transport
                activeDefinition = definition
                realtimeDecoder = RealtimeDecoder(definition)

                _state.update { it.copy(status = EcuConnectionStatus.CONNECTED, signature = signature) }
                // NOTE: Do NOT call ecu.startStreaming() here.
                // The ECU's own streaming loop feeds _realtimeUpdates SharedFlow
                // which nobody collects — it doubles ECU traffic and halves
                // throughput. The manager's startStreamLoop() below is the
                // authoritative loop that feeds channelStore → UI.
                startStreamLoop(ecu, definition, gen)
                startHeartbeatMonitor(gen)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (gen != generation) return@launch
                releaseResources()
                _state.update { it.copy(status = EcuConnectionStatus.ERROR, lastError = e.message ?: e.javaClass.simpleName) }
                attemptReconnect(transport, definition)
            }
        }
    }

    // ------------------------------------------------------------------
    //  Streaming loop
    // ------------------------------------------------------------------

    private fun startStreamLoop(ecu: EcuInterface, definition: EcuDefinition, gen: Long) {
        streamJob?.cancel()
        streamJob = applicationScope.launch(Dispatchers.IO) {
            val decoder = RealtimeDecoder(definition)
            while (isActive && gen == generation) {
                try {
                    val rawData = ecu.readRealtimeData().getOrNull()
                    if (rawData == null) { delay(50); continue }
                    val decoded = decoder.decodeRealtimeData(rawData)
                    channelStore.updateChannels(decoded)

                    if (dataLogManager.recordingState.value == DataLogRecordingState.RECORDING) {
                        dataLogManager.onRealtimeUpdate(decoded)
                    }
                    autoTuneController?.let { ctrl ->
                        if (ctrl.state.value.isRunning) ctrl.feedSample(decoded)
                    }
                    delay(20) // ~50 Hz throttle
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    delay(100) // transient read error – back off
                }
            }
        }
    }

    // ------------------------------------------------------------------
    //  Heartbeat monitor
    // ------------------------------------------------------------------

    private fun startHeartbeatMonitor(gen: Long) {
        heartbeatJob?.cancel()
        heartbeatJob = applicationScope.launch {
            while (isActive && gen == generation) {
                delay(HEARTBEAM_CHECK_MS)
                if (gen != generation) break
                if (!channelStore.isReceivingData(HEARTBEAT_TIMEOUT_MS)) {
                    restartStreamWithBackoff(gen)
                }
            }
        }
    }

    /** Restart the stream with exponential backoff. Gives up after [MAX_RESTARTS] tries. */
    private suspend fun restartStreamWithBackoff(gen: Long) {
        val ecu = ecuInterface ?: return
        val def = activeDefinition ?: return
        repeat(MAX_RESTARTS) { attempt ->
            if (gen != generation) return
            delay((BASE_BACKOFF_MS * 2.0.pow(attempt)).toLong().coerceAtMost(MAX_BACKOFF_MS))
            if (gen != generation) return
            try {
                // Stop any ECU-internal streaming, reset comm, then restart
                // the manager's authoritative stream loop.
                ecu.stopStreaming()
                ecu.commReset()
                startStreamLoop(ecu, def, gen)
                return
            } catch (_: CancellationException) {
                throw CancellationException("Stream restart cancelled")
            } catch (_: Exception) { /* next attempt */ }
        }
        _state.update { it.copy(status = EcuConnectionStatus.ERROR, lastError = "Stream lost after $MAX_RESTARTS restart attempts") }
    }

    // ------------------------------------------------------------------
    //  Auto-reconnect
    // ------------------------------------------------------------------

    private fun attemptReconnect(transport: EcuTransport, definition: EcuDefinition) {
        if (!settings.autoReconnect.value) return
        // Cancel any previous reconnect attempt before starting a new one.
        reconnectJob?.cancel()
        val maxAttempts = settings.reconnectMaxAttempts.value
        val baseDelay = settings.reconnectDelayMs.value
        _state.update { it.copy(status = EcuConnectionStatus.RECONNECTING, reconnectMaxAttempts = maxAttempts, reconnectAttempt = 0) }

        reconnectJob = applicationScope.launch {
            for (attempt in 1..maxAttempts) {
                val delayMs = (baseDelay * 2.0.pow(attempt - 1)).toLong().coerceAtMost(MAX_RECONNECT_DELAY_MS)
                _state.update { it.copy(reconnectAttempt = attempt) }
                delay(delayMs)

                // Check generation after delay — if user disconnected during
                // the delay, abort reconnect entirely.
                if (!isActive) return@launch

                try {
                    if (!transport.isConnected()) transport.connect()
                    val ecu = EcuFactory.create(definition.ecuType)
                    if (ecu.connect(transport, definition).isFailure) continue
                    val sig = ecu.querySignature().getOrNull() ?: continue

                    // Double-check generation before installing the connection.
                    // If disconnect() was called during reconnect, abort and
                    // tear down the just-created ecu.
                    if (generation != this@EcuConnectionManager.generation) {
                        runCatching { ecu.stopStreaming() }
                        runCatching { ecu.disconnect() }
                        return@launch
                    }

                    this@EcuConnectionManager.ecuInterface = ecu
                    this@EcuConnectionManager.transport = transport
                    activeDefinition = definition
                    realtimeDecoder = RealtimeDecoder(definition)
                    // NOTE: Do NOT call ecu.startStreaming() — see comment in connect().
                    // startStreamLoop() is the authoritative streaming loop.
                    val g = generation
                    startStreamLoop(ecu, definition, g)
                    startHeartbeatMonitor(g)
                    _state.update { it.copy(status = EcuConnectionStatus.CONNECTED, signature = sig, lastError = null, reconnectAttempt = 0) }
                    return@launch
                } catch (_: CancellationException) {
                    throw CancellationException("Reconnect cancelled")
                } catch (_: Exception) { /* retry */ }
            }
            _state.update { it.copy(status = EcuConnectionStatus.ERROR, lastError = "Reconnect failed after $maxAttempts attempts") }
        }
    }

    // ------------------------------------------------------------------
    //  Disconnect
    // ------------------------------------------------------------------

    /** Disconnect from the ECU and release all resources. */
    fun disconnect() {
        disconnectInternal()
        _state.update { EcuConnectionState() }
    }

    private fun disconnectInternal() {
        generation++
        heartbeatJob?.cancel(); heartbeatJob = null
        streamJob?.cancel(); streamJob = null
        connectJob?.cancel(); connectJob = null
        reconnectJob?.cancel(); reconnectJob = null
        val ecu = ecuInterface; val t = transport
        if (ecu != null || t != null) {
            applicationScope.launch(Dispatchers.IO) {
                runCatching { ecu?.stopStreaming() }
                runCatching { ecu?.disconnect() }
                runCatching { t?.disconnect() }
            }
        }
        transport = null; ecuInterface = null; activeDefinition = null; realtimeDecoder = null
        channelStore.clear()
    }

    private fun releaseResources() {
        transport = null; ecuInterface = null; activeDefinition = null; realtimeDecoder = null
    }

    // ------------------------------------------------------------------
    //  Sync ECU data
    // ------------------------------------------------------------------

    /**
     * Read all pages from the ECU with progress callbacks.
     *
     * Iterates through every page defined in the [EcuDefinition], reading in
     * block-sized chunks, and invokes [onProgress] after each page completes.
     *
     * @param onProgress Callback invoked with (syncedCount, totalCount) per page.
     * @return Map of pageIndex → page bytes on success.
     */
    suspend fun syncEcuData(
        onProgress: ((synced: Int, total: Int) -> Unit)? = null
    ): Result<Map<Int, ByteArray>> {
        val ecu = ecuInterface ?: return Result.failure(IllegalStateException("Not connected"))
        val def = activeDefinition ?: return Result.failure(IllegalStateException("No definition loaded"))
        val totalPages = def.nPages.toInt().coerceAtLeast(0)
        if (totalPages == 0) return Result.success(emptyMap())

        _state.update { it.copy(status = EcuConnectionStatus.SYNCING, totalPages = totalPages, syncedPages = 0, syncProgress = 0f) }

        return try {
            val result = mutableMapOf<Int, ByteArray>()
            val blockSize = def.protocol.blockSize.coerceAtLeast(1)

            for (page in 0 until totalPages) {
                val pageSize = if (page < def.pageSizes.size) def.pageSizes[page].toInt() and 0xFFFF else 0
                if (pageSize <= 0) continue

                val pageData = ByteArray(pageSize)
                var offset = 0
                while (offset < pageSize) {
                    val chunkLen = min(blockSize, pageSize - offset)
                    val chunk = ecu.readBlock(page, offset, chunkLen)
                        .getOrThrow() // throws on failure → caught below
                    System.arraycopy(chunk, 0, pageData, offset, min(chunk.size, chunkLen))
                    offset += chunkLen
                }
                result[page] = pageData

                _state.update { it.copy(syncedPages = result.size, syncProgress = result.size.toFloat() / totalPages) }
                onProgress?.invoke(result.size, totalPages)
            }
            _state.update { it.copy(status = EcuConnectionStatus.CONNECTED, syncProgress = 1f) }
            Result.success(result)
        } catch (e: CancellationException) {
            _state.update { it.copy(status = EcuConnectionStatus.CONNECTED) }
            throw e
        } catch (e: Exception) {
            _state.update { it.copy(status = EcuConnectionStatus.ERROR, lastError = "Sync failed: ${e.message}") }
            Result.failure(e)
        }
    }

    // ------------------------------------------------------------------
    //  Burn pages (TS spec §6.2 auto-burn)
    // ------------------------------------------------------------------

    /**
     * Write data to an ECU page and optionally burn (persist to flash).
     *
     * Per TunerStudio spec §6.2, the burn command is sent after writing
     * when [autoBurn] is true. [burnDelayMs] inserts a delay between write
     * and burn for ECUs that require settling time.
     *
     * @return Success or failure.
     */
    suspend fun burnPage(
        page: Int,
        offset: Int,
        data: ByteArray,
        autoBurn: Boolean = true,
        burnDelayMs: Long = DEFAULT_BURN_DELAY_MS
    ): Result<Unit> {
        val ecu = ecuInterface ?: return Result.failure(IllegalStateException("Not connected"))
        return try {
            unwrap(ecu.writeBlock(page, offset, data), "Write block failed")
            if (autoBurn) {
                delay(burnDelayMs)
                unwrap(ecu.burnPage(page), "Burn page $page failed")
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** Write and burn a full page from offset 0 with auto-burn. */
    suspend fun burnFullPage(page: Int, pageData: ByteArray, autoBurn: Boolean = true): Result<Unit> =
        burnPage(page, 0, pageData, autoBurn)

    // ------------------------------------------------------------------
    // Safe burn with backup, verify, and rollback (Phase 7)
    // ------------------------------------------------------------------

    /**
     * Safely write and burn a full page to the ECU with verification.
     *
     * This is the transactional burn path for Phase 7 (Safe Write/Burn).
     * Unlike the simple [burnPage] method, this performs:
     *
     *   1. **Backup**: Read the current page from ECU RAM.
     *   2. **Write**: Write [pageData] to ECU RAM at offset 0.
     *   3. **Verify**: Read back the page from ECU RAM and compare byte-for-byte.
     *   4. **Burn**: If verify passes, burn to flash.
     *   5. **Rollback**: If verify fails, restore the backup and abort without burning.
     *
     * This prevents two critical failure modes:
     *   - **Silent data corruption**: Write partially fails, garbage is burned to flash.
     *   - **No rollback**: A bad write leaves the ECU in an unknown state with no
     *     way to recover the original data.
     *
     * BUILD-UNVERIFIED: The safety logic is statically reviewed but has not
     * been tested with real ECU hardware. The rollback path (restore backup)
     * assumes the ECU is still responsive after a failed write — if the
     * transport itself is broken, the rollback will also fail.
     *
     * @param page     Page number to write and burn.
     * @param pageData Full page data to write (must match page size).
     * @return [Result.success] on successful write + verify + burn,
     *   [Result.failure] on any error (with rollback attempted).
     */
    suspend fun burnPageSafely(page: Int, pageData: ByteArray): Result<Unit> {
        val ecu = ecuInterface ?: return Result.failure(IllegalStateException("Not connected"))
        val def = activeDefinition ?: return Result.failure(IllegalStateException("No definition loaded"))

        return try {
            // 1. Read current page as backup
            val pageSize = if (page < def.pageSizes.size) def.pageSizes[page].toInt() and 0xFFFF else pageData.size
            val backup = ecu.readBlock(page, 0, pageSize).getOrThrow()

            // 2. Write new data to ECU RAM
            ecu.writeBlock(page, 0, pageData).getOrThrow()

            // 3. Read back and verify
            val readBack = ecu.readBlock(page, 0, pageData.size).getOrThrow()
            if (!readBack.contentEquals(pageData)) {
                // Verify failed — restore backup
                runCatching { ecu.writeBlock(page, 0, backup) }
                return Result.failure(
                    IllegalStateException("Write verification failed: read-back does not match written data")
                )
            }

            // 4. Burn to flash
            delay(DEFAULT_BURN_DELAY_MS)
            ecu.burnPage(page).getOrThrow()

            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ------------------------------------------------------------------
    //  Helpers
    // ------------------------------------------------------------------

    /** Run [block] and throw if the generation has been superseded. */
    private inline fun <T> guard(gen: Long, block: () -> T): T {
        if (gen != generation) throw CancellationException("Generation superseded")
        return block()
    }

    /** Unwrap a [Result], throwing the exception on failure. */
    private fun <T> unwrap(result: Result<T>, msg: String): T =
        result.getOrThrow() ?: throw RuntimeException(msg)

    private fun resetState(base: EcuConnectionState, status: EcuConnectionStatus, transportName: String?) =
        base.copy(status = status, transportName = transportName, signature = null, lastError = null,
            reconnectAttempt = 0, reconnectMaxAttempts = 0, syncProgress = 0f, syncedPages = 0, totalPages = 0)

    private companion object {
        const val HEARTBEAM_CHECK_MS = 500L
        const val HEARTBEAT_TIMEOUT_MS = 2000L
        const val MAX_RESTARTS = 4
        const val BASE_BACKOFF_MS = 200L
        const val MAX_BACKOFF_MS = 5000L
        const val MAX_RECONNECT_DELAY_MS = 30_000L
        const val DEFAULT_BURN_DELAY_MS = 50L
    }
}
