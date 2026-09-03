@file:Suppress("unused")

package com.ztune.libretune.ui.screens.dashboard.gauges

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ztune.libretune.core.dash.GaugeColorScheme
import com.ztune.libretune.core.dash.GaugeWidgetConfig
import com.ztune.libretune.core.dash.GaugeWidgetType

/**
 * Bar gauge composable — horizontal or vertical progress bar with colored zones.
 *
 * Renders a segmented bar gauge with warning/danger zone overlays and a
 * value readout. The orientation is determined by [GaugeWidgetType]:
 * - [GaugeWidgetType.BAR_HORIZONTAL]: Bar grows left-to-right.
 * - [GaugeWidgetType.BAR_VERTICAL]: Bar grows bottom-to-top.
 *
 * @param config   The gauge widget configuration.
 * @param value    The current value to display.
 * @param modifier Compose modifier.
 */
@Composable
fun BarGauge(
    config: GaugeWidgetConfig,
    value: Double,
    modifier: Modifier = Modifier
) {
    val isVertical = config.type == GaugeWidgetType.BAR_VERTICAL
    val clampedValue = config.clamp(value)
    val zone = config.zoneFor(value)
    val zoneColor = zoneColorForBar(zone)
    val animatedColor by animateColorAsState(
        targetValue = zoneColor,
        animationSpec = androidx.compose.animation.core.tween(durationMillis = 200)
    )
    val onSurface = MaterialTheme.colorScheme.onSurface
    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant
    val surfaceVariant = MaterialTheme.colorScheme.surfaceVariant
    val surface = MaterialTheme.colorScheme.surface
    val barColor = if (zone != null) animatedColor else barSchemeColor(config.colorScheme)

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(surfaceVariant.copy(alpha = 0.3f))
            .padding(10.dp),
        contentAlignment = Alignment.Center
    ) {
        if (isVertical) {
            VerticalBarContent(
                config = config,
                value = clampedValue,
                barColor = barColor,
                surface = surface,
                onSurface = onSurface,
                onSurfaceVariant = onSurfaceVariant
            )
        } else {
            HorizontalBarContent(
                config = config,
                value = clampedValue,
                barColor = barColor,
                surface = surface,
                onSurface = onSurface,
                onSurfaceVariant = onSurfaceVariant
            )
        }
    }
}

@Composable
private fun HorizontalBarContent(
    config: GaugeWidgetConfig,
    value: Double,
    barColor: Color,
    surface: Color,
    onSurface: Color,
    onSurfaceVariant: Color
) {
    val range = config.max - config.min
    val fraction = if (range != 0.0) ((value - config.min) / range).toFloat().coerceIn(0f, 1f) else 0f
    val animatedFraction by animateFloatAsState(
        targetValue = fraction,
        animationSpec = androidx.compose.animation.core.tween(durationMillis = 150)
    )

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // Top: label + value
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom
        ) {
            Text(
                text = config.label,
                style = MaterialTheme.typography.labelMedium,
                color = onSurfaceVariant,
                maxLines = 1
            )
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = formatValue(value, config.decimals),
                    color = barColor,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1
                )
                if (config.units.isNotBlank()) {
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = config.units,
                        style = MaterialTheme.typography.labelSmall,
                        color = onSurfaceVariant,
                        maxLines = 1
                    )
                }
            }
        }

        Spacer(Modifier.height(6.dp))

        // Bar
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(16.dp)
                .clip(RoundedCornerShape(8.dp))
        ) {
            val w = size.width
            val h = size.height
            val cornerR = 8.dp.toPx()

            // Background
            drawRoundRect(
                color = surface.copy(alpha = 0.5f),
                cornerRadius = CornerRadius(cornerR, cornerR)
            )

            // Zone backgrounds
            drawBarZoneBackgrounds(config, w, h)

            // Filled portion
            val barWidth = (w * animatedFraction).coerceAtLeast(0f)
            if (barWidth > 1f) {
                drawRoundRect(
                    color = barColor.copy(alpha = 0.85f),
                    topLeft = Offset(0f, 0f),
                    size = Size(barWidth, h),
                    cornerRadius = CornerRadius(cornerR, cornerR)
                )
            }

            // Scale ticks at min and max
            drawScaleTicks(config, w, h, isVertical = false)
        }

        // Min/Max labels
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = formatTickLabel(config.min, if (config.min >= 100) 0 else 1),
                style = MaterialTheme.typography.labelSmall,
                color = onSurfaceVariant.copy(alpha = 0.6f)
            )
            Text(
                text = formatTickLabel(config.max, if (config.max >= 100) 0 else 1),
                style = MaterialTheme.typography.labelSmall,
                color = onSurfaceVariant.copy(alpha = 0.6f)
            )
        }
    }
}

@Composable
private fun VerticalBarContent(
    config: GaugeWidgetConfig,
    value: Double,
    barColor: Color,
    surface: Color,
    onSurface: Color,
    onSurfaceVariant: Color
) {
    val range = config.max - config.min
    val fraction = if (range != 0.0) ((value - config.min) / range).toFloat().coerceIn(0f, 1f) else 0f
    val animatedFraction by animateFloatAsState(
        targetValue = fraction,
        animationSpec = androidx.compose.animation.core.tween(durationMillis = 150)
    )

    Row(
        modifier = Modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Bar column
        Canvas(
            modifier = Modifier
                .width(20.dp)
                .fillMaxHeight()
                .clip(RoundedCornerShape(10.dp))
        ) {
            val w = size.width
            val h = size.height
            val cornerR = 10.dp.toPx()

            // Background
            drawRoundRect(
                color = surface.copy(alpha = 0.5f),
                cornerRadius = CornerRadius(cornerR, cornerR)
            )

            // Zone backgrounds (vertical)
            drawBarZoneBackgrounds(config, w, h)

            // Filled portion (from bottom up)
            val barHeight = (h * animatedFraction).coerceAtLeast(0f)
            if (barHeight > 1f) {
                drawRoundRect(
                    color = barColor.copy(alpha = 0.85f),
                    topLeft = Offset(0f, h - barHeight),
                    size = Size(w, barHeight),
                    cornerRadius = CornerRadius(cornerR, cornerR)
                )
            }

            // Scale ticks
            drawScaleTicks(config, w, h, isVertical = true)
        }

        Spacer(Modifier.width(8.dp))

        // Label + value column
        Column(
            horizontalAlignment = Alignment.Start,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = config.label,
                style = MaterialTheme.typography.labelMedium,
                color = onSurfaceVariant,
                maxLines = 1
            )
            Text(
                text = formatValue(value, config.decimals),
                color = barColor,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                maxLines = 1
            )
            if (config.units.isNotBlank()) {
                Text(
                    text = config.units,
                    style = MaterialTheme.typography.labelSmall,
                    color = onSurfaceVariant,
                    maxLines = 1
                )
            }
        }
    }
}

// ========================================================================
//  Drawing helpers
// ========================================================================

/**
 * Draw warning/danger zone overlays as semi-transparent rectangles.
 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawBarZoneBackgrounds(
    config: GaugeWidgetConfig,
    barWidth: Float,
    barHeight: Float
) {
    val range = config.max - config.min
    if (range == 0.0) return

    val zones = listOf(
        Triple(config.lowDanger, "low_danger", Color(0xFFFF5252).copy(alpha = 0.3f)),
        Triple(config.lowWarning, "low_warning", Color(0xFFFFB74D).copy(alpha = 0.25f)),
        Triple(config.highWarning, "high_warning", Color(0xFFFFB74D).copy(alpha = 0.25f)),
        Triple(config.highDanger, "high_danger", Color(0xFFFF5252).copy(alpha = 0.3f))
    )

    for ((threshold, zoneName, color) in zones) {
        if (threshold.isNaN()) continue
        val frac = ((threshold - config.min) / range).coerceIn(0.0, 1.0).toFloat()
        val pos = barWidth * frac
        val markWidth = 1.5.dp.toPx()

        drawRect(
            color = color,
            topLeft = Offset(pos - markWidth / 2f, 0f),
            size = Size(markWidth, barHeight)
        )
    }
}

/**
 * Draw small tick marks at min/max and zone boundaries.
 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawScaleTicks(
    config: GaugeWidgetConfig,
    barWidth: Float,
    barHeight: Float,
    isVertical: Boolean
) {
    val range = config.max - config.min
    if (range == 0.0) return
    val tickLen = 3.dp.toPx()
    val tickColor = Color.White.copy(alpha = 0.3f)

    val thresholds = listOfNotNull(
        config.lowDanger, config.lowWarning,
        config.highWarning, config.highDanger
    )

    for (t in thresholds) {
        val frac = ((t - config.min) / range).coerceIn(0.0, 1.0).toFloat()
        if (isVertical) {
            val y = barHeight * (1f - frac)
            drawLine(tickColor, Offset(0f, y), Offset(tickLen, y))
            drawLine(tickColor, Offset(barWidth - tickLen, y), Offset(barWidth, y))
        } else {
            val x = barWidth * frac
            drawLine(tickColor, Offset(x, 0f), Offset(x, tickLen))
            drawLine(tickColor, Offset(x, barHeight - tickLen), Offset(x, barHeight))
        }
    }
}

private fun zoneColorForBar(zone: String?): Color = when (zone) {
    "low_danger", "high_danger" -> Color(0xFFFF5252)
    "low_warning", "high_warning" -> Color(0xFFFFB74D)
    else -> Color.Unspecified
}

private fun barSchemeColor(scheme: GaugeColorScheme): Color = when (scheme) {
    GaugeColorScheme.GREEN -> Color(0xFF4CAF50)
    GaugeColorScheme.BLUE -> Color(0xFF42A5F5)
    GaugeColorScheme.RED -> Color(0xFFEF5350)
    GaugeColorScheme.YELLOW -> Color(0xFFFFC107)
    GaugeColorScheme.CYAN -> Color(0xFF26C6DA)
    GaugeColorScheme.DEFAULT -> Color(0xFF5CD6C0)
}
