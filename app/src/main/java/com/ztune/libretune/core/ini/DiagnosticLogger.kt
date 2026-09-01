package com.ztune.libretune.core.ini

import com.ztune.libretune.core.ini.types.DataType

/**
 * A diagnostic logger definition (tooth logger, composite logger, etc.).
 * These blocks have their own start/stop/read commands and record layout.
 */
data class DiagnosticLogger(
    val name: String = "",
    val title: String = "",
    val startCommand: String = "",
    val stopCommand: String = "",
    val readCommand: String = "",
    /** Bytes per record. */
    val recordSize: Int = 0,
    /** Total buffer size in bytes. */
    val bufferSize: Int = 0,
    val channels: List<LoggerChannel> = emptyList()
)

/** A channel within a diagnostic logger. */
data class LoggerChannel(
    val name: String = "",
    val offset: Int = 0,
    /** -1 means this is not a bit field. */
    val bitOffset: Int = -1,
    val bitWidth: Int = 0,
    val dataType: DataType = DataType.U16,
    val scale: Double = 1.0,
    val translate: Double = 0.0,
    val units: String = "",
    val label: String = ""
)
