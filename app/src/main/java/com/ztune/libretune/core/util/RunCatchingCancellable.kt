package com.ztune.libretune.core.util

import kotlin.coroutines.cancellation.CancellationException

/**
 * Run [block] and wrap the result in [Result], re-throwing [CancellationException]
 * instead of swallowing it.
 *
 * ## Why this exists
 *
 * Kotlin's standard `runCatching { ... }` catches `Throwable`, which includes
 * [CancellationException]. When used inside a `suspend fun`, this **breaks
 * structured concurrency**: coroutine cancellation (e.g. from `Job.cancel()`,
 * `withTimeout()`, or scope cancellation) is caught and returned as
 * `Result.failure(CancellationException)` instead of propagating.
 *
 * Consequences of using plain `runCatching` in suspend functions:
 *   - `disconnect()` cannot abort an in-flight `readBlock()` — the read
 *     "fails" with a wrapped CancellationException instead of being cancelled.
 *   - `withTimeout` inside the block doesn't actually time out — the
 *     `TimeoutCancellationException` is caught and converted to a Result.
 *   - Reconnect logic can "resurrect" a connection the user explicitly
 *     disconnected, because the disconnect's cancellation was swallowed.
 *
 * ## Usage
 *
 * Replace:
 * ```kotlin
 * suspend fun readBlock(...): Result<ByteArray> = runCatching {
 *     // ... suspend calls ...
 * }
 * ```
 * with:
 * ```kotlin
 * suspend fun readBlock(...): Result<ByteArray> = runCatchingCancellable {
 *     // ... suspend calls ...
 * }
 * ```
 *
 * ## When NOT to use
 *
 * Do NOT use this for non-suspending code — plain `runCatching` is fine there
 * because there is no coroutine cancellation to propagate.
 *
 * ## Naming
 *
 * The name `runCatchingCancellable` follows the Kotlin coroutines convention
 * of suffixing cancellable variants (e.g. `launch` vs `launch`, `runInterruptible`).
 */
inline fun <T> runCatchingCancellable(block: () -> T): Result<T> = try {
    Result.success(block())
} catch (e: CancellationException) {
    // Re-throw cancellation so structured concurrency works correctly.
    // This is the critical difference from plain runCatching.
    throw e
} catch (e: Throwable) {
    Result.failure(e)
}
