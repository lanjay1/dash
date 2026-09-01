package com.ztune.libretune.core.ini.types

/**
 * A real-time output channel read from the ECU streaming data.
 * Matches LibreTune's Rust `OutputChannel` struct.
 */
data class OutputChannel(
    val name: String,
    val offset: Int = 0,
    val dataType: DataType = DataType.U08,
    val scale: Double = 1.0,
    val translate: Double = 0.0,
    val units: String = "",
    /** Optional expression string for calculated (derived) channels. */
    val expression: String? = null,
    val minValue: Double = Double.MIN_VALUE,
    val maxValue: Double = Double.MAX_VALUE
)
