@file:Suppress("unused")

package com.ztune.libretune.core.dash

import kotlinx.serialization.Serializable

/**
 * High-level gauge visual variant, matching LibreTune's Rust `GaugeVariant` enum.
 * These are the coarse categories exposed by the INI file gauge definitions.
 */
@Serializable
enum class GaugeVariant {
    /** Classic analog gauge with a rotating needle. */
    ANALOG,
    /** Digital numeric readout. */
    DIGITAL,
    /** Horizontal or vertical bar gauge. */
    BAR,
    /** Wideband AFR gauge (special analog style with lambda/AFR scale). */
    WIDEBAND,
    /** Placeholder / empty slot. */
    EMPTY;

    companion object {
        /** Map the INI [GaugeVariant] to the most appropriate default widget type. */
        fun toDefaultWidgetType(variant: GaugeVariant): GaugeWidgetType = when (variant) {
            ANALOG -> GaugeWidgetType.ANALOG_SWEEP
            DIGITAL -> GaugeWidgetType.DIGITAL_LARGE
            BAR -> GaugeWidgetType.BAR_HORIZONTAL
            WIDEBAND -> GaugeWidgetType.WIDEBAND_LINEAR
            EMPTY -> GaugeWidgetType.EMPTY
        }
    }
}

/**
 * All 13 supported gauge widget types, matching LibreTune's Rust `GaugeWidgetType`.
 *
 * These provide finer-grained control over how each gauge is rendered.
 * The mapping from [GaugeVariant] to [GaugeWidgetType] is many-to-one;
 * users can pick a more specific widget after the initial INI mapping.
 */
@Serializable
enum class GaugeWidgetType {
    // ---- Analog variants (3) ----
    /** 270-degree sweep analog gauge with needle. */
    ANALOG_SWEEP,
    /** 180-degree half-circle analog gauge. */
    ANALOG_HALF,
    /** 90-degree quarter-circle analog gauge (e.g. tachometer). */
    ANALOG_QUARTER,

    // ---- Digital variants (2) ----
    /** Large digital number readout, takes more screen space. */
    DIGITAL_LARGE,
    /** Compact digital readout for tight grid cells. */
    DIGITAL_COMPACT,

    // ---- Bar variants (2) ----
    /** Horizontal progress-style bar gauge. */
    BAR_HORIZONTAL,
    /** Vertical progress-style bar gauge. */
    BAR_VERTICAL,

    // ---- Specialized wideband (2) ----
    /** Wideband AFR on a linear scale (7.4 – 22.4 typical). */
    WIDEBAND_LINEAR,
    /** Wideband AFR on a logarithmic lambda scale. */
    WIDEBAND_LOG,

    // ---- Miscellaneous (4) ----
    /** Boolean on/off indicator light. */
    INDICATOR,
    /** Plain text label / readout (no graphical gauge). */
    TEXT,
    /** Empty placeholder slot in the grid. */
    EMPTY;

    companion object {
        /** Try to parse a string name (case-insensitive) into a [GaugeWidgetType]. */
        fun fromName(name: String): GaugeWidgetType? =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) }

        /** The broad [GaugeVariant] category this widget type belongs to. */
        fun GaugeWidgetType.variant(): GaugeVariant = when (this) {
            ANALOG_SWEEP, ANALOG_HALF, ANALOG_QUARTER -> GaugeVariant.ANALOG
            DIGITAL_LARGE, DIGITAL_COMPACT -> GaugeVariant.DIGITAL
            BAR_HORIZONTAL, BAR_VERTICAL -> GaugeVariant.BAR
            WIDEBAND_LINEAR, WIDEBAND_LOG -> GaugeVariant.WIDEBAND
            INDICATOR, TEXT -> GaugeVariant.DIGITAL
            EMPTY -> GaugeVariant.EMPTY
        }
    }
}

/**
 * Color scheme presets for gauge rendering.
 * Each value maps to a predefined set of foreground/background/zone colors.
 */
@Serializable
enum class GaugeColorScheme {
    /** Default theme colors (follows app theme). */
    DEFAULT,
    /** Green-dominant scheme (good for positive indicators). */
    GREEN,
    /** Blue-dominant scheme. */
    BLUE,
    /** Red-dominant scheme (good for temperature / warning gauges). */
    RED,
    /** Yellow-dominant scheme. */
    YELLOW,
    /** Cyan-dominant scheme. */
    CYAN;

    companion object {
        fun fromName(name: String): GaugeColorScheme =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
                ?: DEFAULT
    }
}

/**
 * Full configuration for a single gauge widget in the dashboard grid.
 *
 * This is the serializable model that the UI layer consumes.
 * It maps to LibreTune's Rust `Gauge` struct, enriched with layout hints.
 *
 * @property id          Unique identifier for this widget instance.
 * @property type        The specific widget rendering type (one of 13).
 * @property channelName Name of the data channel this gauge reads from
 *                      (must match an output channel from the INI definition).
 * @property label       Display label shown on/above the gauge.
 * @property units       Engineering units string (e.g. "RPM", "°C", "kPa").
 * @property min         Minimum value on the gauge scale.
 * @property max         Maximum value on the gauge scale.
 * @property lowWarning  Value below which the gauge enters the low-warning zone.
 * @property highWarning Value above which the gauge enters the high-warning zone.
 * @property lowDanger   Value below which the gauge enters the low-danger zone.
 * @property highDanger  Value above which the gauge enters the high-danger zone.
 * @property decimals    Number of decimal places for digital readouts.
 * @property colorScheme Color palette used when rendering this gauge.
 * @property width       Number of grid columns this widget spans.
 * @property height      Number of grid rows this widget spans.
 */
@Serializable
data class GaugeWidgetConfig(
    val id: String = "",
    val type: GaugeWidgetType = GaugeWidgetType.ANALOG_SWEEP,
    val channelName: String = "",
    val label: String = "",
    val units: String = "",
    val min: Double = 0.0,
    val max: Double = 100.0,
    val lowWarning: Double = Double.NaN,
    val highWarning: Double = Double.NaN,
    val lowDanger: Double = Double.NaN,
    val highDanger: Double = Double.NaN,
    val decimals: Int = 1,
    val colorScheme: GaugeColorScheme = GaugeColorScheme.DEFAULT,
    val width: Int = 1,
    val height: Int = 1
) {
    /**
     * Clamp a raw value into the [min]..[max] range.
     */
    fun clamp(value: Double): Double = value.coerceIn(min, max)

    /**
     * Determine the current warning/danger zone for a value.
     *
     * @return `null` for safe, `"low_warning"` / `"high_warning"` / `"low_danger"` / `"high_danger"`
     *         when the value falls in a threshold zone.
     */
    fun zoneFor(value: Double): String? {
        if (!lowDanger.isNaN() && value < lowDanger) return "low_danger"
        if (!highDanger.isNaN() && value > highDanger) return "high_danger"
        if (!lowWarning.isNaN() && value < lowWarning) return "low_warning"
        if (!highWarning.isNaN() && value > highWarning) return "high_warning"
        return null
    }

    /** Whether this widget is just a placeholder taking no data channel. */
    val isEmpty: Boolean get() = type == GaugeWidgetType.EMPTY && channelName.isBlank()

    companion object {
        /** Create a minimal widget config bound to a data channel. */
        fun create(
            id: String,
            channelName: String,
            label: String = channelName,
            units: String = "",
            type: GaugeWidgetType = GaugeWidgetType.ANALOG_SWEEP,
            min: Double = 0.0,
            max: Double = 100.0,
            decimals: Int = 1
        ): GaugeWidgetConfig = GaugeWidgetConfig(
            id = id,
            type = type,
            channelName = channelName,
            label = label,
            units = units,
            min = min,
            max = max,
            decimals = decimals
        )

        /** Create an empty placeholder widget for a grid cell. */
        fun emptySlot(id: String): GaugeWidgetConfig = GaugeWidgetConfig(
            id = id,
            type = GaugeWidgetType.EMPTY
        )
    }
}
