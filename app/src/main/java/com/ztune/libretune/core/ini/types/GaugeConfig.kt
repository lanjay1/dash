package com.ztune.libretune.core.ini.types

/** Visual style of a gauge widget. */
enum class GaugeType {
    ANALOG,
    DIGITAL,
    BAR,
    WIDEBAND,
    EMPTY
}

/** Configuration for a single gauge on the dashboard.
 *  Matches LibreTune's Rust `GaugeConfig` struct.
 */
data class GaugeConfig(
    val name: String,
    val channelName: String = "",
    val gaugeType: GaugeType = GaugeType.ANALOG,
    val minVal: Double = 0.0,
    val maxVal: Double = 100.0,
    val lowWarning: Double = Double.NaN,
    val highWarning: Double = Double.NaN,
    val lowDanger: Double = Double.NaN,
    val highDanger: Double = Double.NaN,
    val title: String = "",
    val units: String = "",
    val decimals: Int = 1,
    /** Virtual dashboard number (0-based). */
    val vd: Int = 0
)
