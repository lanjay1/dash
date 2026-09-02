@file:Suppress("unused")

package com.ztune.libretune.ui.screens.dashboard.gauges

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
 * Digital gauge composable — large numeric readout with units and color-coded zones.
 *
 * Supports two modes based on [GaugeWidgetType]:
 * - [GaugeWidgetType.DIGITAL_LARGE]: Full-size readout with prominent value.
 * - [GaugeWidgetType.DIGITAL_COMPACT]: Compact readout for tight grid cells.
 *
 * @param config  The gauge widget configuration.
 * @param value   The current value to display.
 * @param modifier Compose modifier.
 */
@Composable
fun DigitalGauge(
    config: GaugeWidgetConfig,
    value: Double,
    modifier: Modifier = Modifier
) {
    val isCompact = config.type == GaugeWidgetType.DIGITAL_COMPACT
    val clampedValue = config.clamp(value)
    val zone = config.zoneFor(value)
    val zoneColor = zoneColorFor(zone)
    val animatedColor = animateColorAsState(
        targetValue = zoneColor,
        animationSpec = androidx.compose.animation.core.tween(durationMillis = 200)
    )

    val displayColor = if (zone != null) animatedColor.value else {
        schemeForeground(config.colorScheme)
    }

    val surfaceVariant = MaterialTheme.colorScheme.surfaceVariant
    val onSurface = MaterialTheme.colorScheme.onSurface
    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant
    val surface = MaterialTheme.colorScheme.surface

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(surfaceVariant.copy(alpha = 0.3f))
            .padding(if (isCompact) 8.dp else 12.dp),
        contentAlignment = Alignment.Center
    ) {
        if (isCompact) {
            CompactDigitalContent(
                config = config,
                value = clampedValue,
                displayColor = displayColor,
                onSurfaceVariant = onSurfaceVariant
            )
        } else {
            LargeDigitalContent(
                config = config,
                value = clampedValue,
                displayColor = displayColor,
                onSurfaceVariant = onSurfaceVariant,
                surface = surface
            )
        }
    }
}

@Composable
private fun LargeDigitalContent(
    config: GaugeWidgetConfig,
    value: Double,
    displayColor: Color,
    onSurfaceVariant: Color,
    surface: Color
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // Label
        if (config.label.isNotBlank()) {
            Text(
                text = config.label,
                style = MaterialTheme.typography.labelMedium,
                color = onSurfaceVariant,
                textAlign = TextAlign.Center,
                maxLines = 1
            )
            Spacer(Modifier.height(4.dp))
        }

        // Value + Units
        Row(
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.Center
        ) {
            Text(
                text = formatValue(value, config.decimals),
                color = displayColor,
                fontSize = 36.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                letterSpacing = (-0.5).sp,
                textAlign = TextAlign.Center,
                maxLines = 1
            )
            if (config.units.isNotBlank()) {
                Text(
                    text = " ${config.units}",
                    color = onSurfaceVariant,
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.End,
                    maxLines = 1
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        // Zone indicator bar
        val zone = config.zoneFor(value)
        ZoneIndicatorBar(
            config = config,
            value = value,
            surface = surface,
            modifier = Modifier
                .fillMaxWidth(0.7f)
                .height(4.dp)
        )
    }
}

@Composable
private fun CompactDigitalContent(
    config: GaugeWidgetConfig,
    value: Double,
    displayColor: Color,
    onSurfaceVariant: Color
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        if (config.label.isNotBlank()) {
            Text(
                text = config.label,
                style = MaterialTheme.typography.labelSmall,
                color = onSurfaceVariant,
                maxLines = 1
            )
            Spacer(Modifier.height(2.dp))
        }
        Row(
            verticalAlignment = Alignment.Bottom
        ) {
            Text(
                text = formatValue(value, config.decimals),
                color = displayColor,
                fontSize = 22.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = FontFamily.Monospace,
                maxLines = 1
            )
            if (config.units.isNotBlank()) {
                Text(
                    text = config.units,
                    color = onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1
                )
            }
        }
    }
}

/**
 * A thin horizontal bar below the digital readout showing the gauge range
 * with warning/danger zones colored.
 */
@Composable
private fun ZoneIndicatorBar(
    config: GaugeWidgetConfig,
    value: Double,
    surface: Color,
    modifier: Modifier = Modifier
) {
    val range = config.max - config.min
    if (range == 0.0) return

    androidx.compose.foundation.Canvas(
        modifier = modifier.clip(RoundedCornerShape(2.dp))
    ) {
        val w = size.width
        val h = size.height

        // Background
        drawRect(color = surface.copy(alpha = 0.4f))

        // Low danger
        if (!config.lowDanger.isNaN()) {
            val endFrac = ((config.lowDanger - config.min) / range).coerceIn(0.0, 1.0).toFloat()
            drawRect(color = Color(0xFFFF5252).copy(alpha = 0.5f), size = Size(w * endFrac, h))
        }

        // Low warning
        if (!config.lowWarning.isNaN()) {
            val startFrac = if (!config.lowDanger.isNaN()) {
                ((config.lowDanger - config.min) / range).coerceIn(0.0, 1.0).toFloat()
            } else 0f
            val endFrac = ((config.lowWarning - config.min) / range).coerceIn(0.0, 1.0).toFloat()
            drawRect(
                color = Color(0xFFFFB74D).copy(alpha = 0.4f),
                topLeft = androidx.compose.ui.geometry.Offset(w * startFrac, 0f),
                size = Size(w * (endFrac - startFrac), h)
            )
        }

        // High warning
        if (!config.highWarning.isNaN()) {
            val startFrac = ((config.highWarning - config.min) / range).coerceIn(0.0, 1.0).toFloat()
            val endFrac = if (!config.highDanger.isNaN()) {
                ((config.highDanger - config.min) / range).coerceIn(0.0, 1.0).toFloat()
            } else 1f
            drawRect(
                color = Color(0xFFFFB74D).copy(alpha = 0.4f),
                topLeft = androidx.compose.ui.geometry.Offset(w * startFrac, 0f),
                size = Size(w * (endFrac - startFrac), h)
            )
        }

        // High danger
        if (!config.highDanger.isNaN()) {
            val startFrac = ((config.highDanger - config.min) / range).coerceIn(0.0, 1.0).toFloat()
            drawRect(
                color = Color(0xFFFF5252).copy(alpha = 0.5f),
                topLeft = androidx.compose.ui.geometry.Offset(w * startFrac, 0f),
                size = Size(w * (1f - startFrac), h)
            )
        }

        // Value position indicator
        val valFrac = ((config.clamp(value) - config.min) / range).coerceIn(0.0, 1.0).toFloat()
        val indicatorX = w * valFrac
        drawCircle(
            color = Color.White,
            radius = h * 0.9f,
            center = androidx.compose.ui.geometry.Offset(indicatorX, h / 2f)
        )
    }
}

// ========================================================================
//  Color helpers
// ========================================================================

private fun zoneColorFor(zone: String?): Color = when (zone) {
    "low_danger", "high_danger" -> Color(0xFFFF5252)
    "low_warning", "high_warning" -> Color(0xFFFFB74D)
    else -> Color.Unspecified
}

private fun schemeForeground(scheme: GaugeColorScheme): Color = when (scheme) {
    GaugeColorScheme.GREEN -> Color(0xFF4CAF50)
    GaugeColorScheme.BLUE -> Color(0xFF42A5F5)
    GaugeColorScheme.RED -> Color(0xFFEF5350)
    GaugeColorScheme.YELLOW -> Color(0xFFFFC107)
    GaugeColorScheme.CYAN -> Color(0xFF26C6DA)
    GaugeColorScheme.DEFAULT -> Color(0xFF5CD6C0)
}
