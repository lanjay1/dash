package com.ztune.libretune.core.datalog

/**
 * A datalog session represents a single recording of ECU data over time.
 *
 * Metadata about a recording: when it started/ended, how many samples were
 * captured, which channels were logged, and where the CSV file lives on disk.
 *
 * Converted from LibreTune's Rust `datalog::DatalogSession`.
 */
data class DataLogSession(
    val id: Long = 0,
    val name: String = "",
    val startedAt: Long = System.currentTimeMillis(),
    val endedAt: Long = 0,
    val channelNames: List<String> = emptyList(),
    val sampleCount: Int = 0,
    val filePath: String? = null,
    val notes: String = ""
) {
    /** Duration of the session in milliseconds, or 0 if still recording / zero-length. */
    val durationMs: Long
        get() = if (endedAt > startedAt) endedAt - startedAt else 0L

    /** Human-readable duration string (e.g. "00:05:23"). */
    val durationFormatted: String
        get() {
            val totalSecs = durationMs / 1000
            val hrs = totalSecs / 3600
            val mins = (totalSecs % 3600) / 60
            val secs = totalSecs % 60
            return "${hrs.toString().padStart(2, '0')}:" +
                    "${mins.toString().padStart(2, '0')}:" +
                    "${secs.toString().padStart(2, '0')}"
        }

    /** Whether the session has been completed (endedAt > 0). */
    val isComplete: Boolean
        get() = endedAt > 0

    companion object {
        /** CSV column name used for the timestamp field. */
        const val COLUMN_TIMESTAMP = "timestamp"
    }
}
