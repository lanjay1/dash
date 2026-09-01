@file:Suppress("unused")

package com.ztune.libretune.core.dash

import com.ztune.libretune.core.ini.types.GaugeConfig
import com.ztune.libretune.core.ini.types.GaugeType
import kotlinx.serialization.Serializable

/**
 * Layout configuration for a single dashboard page.
 *
 * A dashboard is a grid of [columns] x [rows] cells.  Each cell is filled by
 * one [GaugeWidgetConfig] (which may span multiple cells via its `width`/`height`).
 *
 * @property id              Unique identifier (filename-safe, used for persistence).
 * @property name            Human-readable dashboard name (e.g. "Track Day", "Street").
 * @property columns         Number of grid columns.
 * @property rows            Number of grid rows.
 * @property widgets         Ordered list of gauge widget configurations.
 * @property showIndicators  Whether the indicator strip (boolean lights) is visible.
 * @property indicatorChannels Ordered list of channel names to show as indicator lights.
 */
@Serializable
data class DashboardConfig(
    val id: String = "default",
    val name: String = "Default",
    val columns: Int = 3,
    val rows: Int = 4,
    val widgets: List<GaugeWidgetConfig> = emptyList(),
    val showIndicators: Boolean = true,
    val indicatorChannels: List<String> = emptyList()
) {
    val totalCells: Int get() = columns * rows

    /**
     * Return only the widgets whose [GaugeWidgetConfig.type] is not [GaugeWidgetType.EMPTY].
     */
    val activeWidgets: List<GaugeWidgetConfig>
        get() = widgets.filter { it.type != GaugeWidgetType.EMPTY }

    /**
     * Number of active (non-empty) widgets.
     */
    val activeWidgetCount: Int get() = activeWidgets.size

    /**
     * Build a map from channel name to the widget config that reads it.
     * Channels bound to multiple widgets will return the first match.
     */
    fun channelToWidgetMap(): Map<String, GaugeWidgetConfig> =
        activeWidgets.associateBy { it.channelName }

    /**
     * Collect all unique data channel names referenced by the widgets and indicators.
     */
    fun allChannelNames(): Set<String> {
        val channels = mutableSetOf<String>()
        for (w in widgets) {
            if (w.channelName.isNotBlank()) channels.add(w.channelName)
        }
        channels.addAll(indicatorChannels)
        return channels
    }

    companion object {
        /**
         * Create a default dashboard from ECU definition gauge configs.
         *
         * This mirrors the logic in LibreTune's Rust `dash` module where
         * the INI `[GaugeConfigurations]` section is parsed into a list of
         * gauges, then laid out on a default grid.
         *
         * @param gauges Map of gauge name -> [GaugeConfig] parsed from the INI file.
         * @param columns Grid columns (defaults to 3).
         * @param rows    Grid rows (defaults to 4).
         * @param id      Dashboard id (defaults to "default").
         * @param name    Dashboard display name.
         * @return A [DashboardConfig] with one [GaugeWidgetConfig] per gauge entry.
         */
        fun fromEcuGauges(
            gauges: Map<String, GaugeConfig>,
            columns: Int = 3,
            rows: Int = 4,
            id: String = "default",
            name: String = "Default"
        ): DashboardConfig {
            val widgets = gauges.entries.mapIndexed { index, (name, cfg) ->
                val widgetType = mapGaugeTypeToWidgetType(cfg.gaugeType)
                GaugeWidgetConfig(
                    id = "gauge_$index",
                    type = widgetType,
                    channelName = cfg.channelName.ifBlank { name },
                    label = cfg.title.ifBlank { name },
                    units = cfg.units,
                    min = cfg.minVal,
                    max = cfg.maxVal,
                    lowWarning = cfg.lowWarning,
                    highWarning = cfg.highWarning,
                    lowDanger = cfg.lowDanger,
                    highDanger = cfg.highDanger,
                    decimals = cfg.decimals,
                    // Wideband gauges get a 2-column span by default
                    width = if (cfg.gaugeType == GaugeType.WIDEBAND) 2 else 1,
                    height = 1
                )
            }

            // Collect indicator channel names from gauge configs whose gaugeType is EMPTY
            // (in LibreTune INI files, indicators are often listed as EMPTY-type gauges)
            val indicatorChannels = gauges.values
                .filter { it.gaugeType == GaugeType.EMPTY && it.channelName.isNotBlank() }
                .map { it.channelName }

            return DashboardConfig(
                id = id,
                name = name,
                columns = columns,
                rows = rows,
                widgets = widgets,
                showIndicators = indicatorChannels.isNotEmpty(),
                indicatorChannels = indicatorChannels
            )
        }

        /**
         * Map the coarse INI [GaugeType] to the most appropriate fine-grained [GaugeWidgetType].
         */
        private fun mapGaugeTypeToWidgetType(gaugeType: GaugeType): GaugeWidgetType =
            when (gaugeType) {
                GaugeType.ANALOG   -> GaugeWidgetType.ANALOG_SWEEP
                GaugeType.DIGITAL  -> GaugeWidgetType.DIGITAL_LARGE
                GaugeType.BAR      -> GaugeWidgetType.BAR_HORIZONTAL
                GaugeType.WIDEBAND -> GaugeWidgetType.WIDEBAND_LINEAR
                GaugeType.EMPTY    -> GaugeWidgetType.EMPTY
            }

        /**
         * Create a blank dashboard with all empty slots.
         */
        fun blank(
            id: String = "blank",
            name: String = "Blank",
            columns: Int = 3,
            rows: Int = 4
        ): DashboardConfig {
            val widgets = (0 until columns * rows).map { idx ->
                GaugeWidgetConfig.emptySlot("slot_$idx")
            }
            return DashboardConfig(
                id = id,
                name = name,
                columns = columns,
                rows = rows,
                widgets = widgets
            )
        }
    }
}
