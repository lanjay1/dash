@file:Suppress("unused")

package com.ztune.libretune.ui.screens.dashboard.gauges

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ztune.libretune.core.dash.GaugeColorScheme
import com.ztune.libretune.core.dash.GaugeWidgetConfig
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Style parameters for analog gauge rendering.
 */
@Immutable
data class AnalogGaugeStyle(
    val arcSweep: Float = 270f,       // degrees of the gauge arc
    val startAngle: Float = 135f,      // starting angle from 3 o'clock position
    val needleWidth: Float = 4f,       // dp
    val needleLength: Float = 0.7f,    // fraction of radius
    val outerArcStroke: Float = 14f,   // dp
    val tickMajorLength: Float = 14f,  // dp
    val tickMinorLength: Float = 8f,   // dp
    val majorTickCount: Int = 10,
    val minorTicksPerMajor: Int = 5
)

/**
 * Analog gauge composable with a 270-degree arc, tick marks, and a needle.
 *
 * Renders a professional ECU-style analog gauge using [Canvas].
 *
 * @param config  The gauge widget configuration defining scale, zones, and label.
 * @param value   The current value to display.
 * @param modifier Compose modifier.
 * @param style   Custom style overrides.
 */
@Composable
fun AnalogGauge(
    config: GaugeWidgetConfig,
    value: Double,
    modifier: Modifier = Modifier,
    style: AnalogGaugeStyle = AnalogGaugeStyle()
) {
    val clampedValue = config.clamp(value)
    val fraction = if (config.max != config.min) {
        ((clampedValue - config.min) / (config.max - config.min)).toFloat().coerceIn(0.0f, 1.0f)
    } else 0f

    val zone = config.zoneFor(value)
    val colors = resolveAnalogColors(config.colorScheme, zone)
    val textMeasurer = rememberTextMeasurer()

    val onSurface = MaterialTheme.colorScheme.onSurface
    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant
    val surface = MaterialTheme.colorScheme.surface

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .aspectRatio(1f)
        ) {
            val cx = size.width / 2f
            val cy = size.height / 2f
            val radius = (size.minDimension / 2f) * 0.82f
            val arcStrokePx = style.outerArcStroke.dp.toPx()
            val startRad = Math.toRadians(style.startAngle.toDouble())
            val sweepRad = Math.toRadians(style.arcSweep.toDouble())
            val endRad = startRad + sweepRad

            // Background arc (track)
            drawArc(
                color = surface.copy(alpha = 0.6f),
                startAngle = style.startAngle,
                sweepAngle = style.arcSweep,
                useCenter = false,
                topLeft = Offset(cx - radius, cy - radius),
                size = Size(radius * 2f, radius * 2f),
                style = Stroke(width = arcStrokePx, cap = StrokeCap.Round)
            )

            // Warning zone arcs (if defined)
            drawZoneArcs(
                config = config,
                cx = cx, cy = cy, radius = radius,
                startRad = startRad, sweepRad = sweepRad,
                arcStrokePx = arcStrokePx
            )

            // Value arc (filled portion)
            val valueSweep = style.arcSweep * fraction
            if (fraction > 0.001f) {
                drawArc(
                    color = colors.primary,
                    startAngle = style.startAngle,
                    sweepAngle = valueSweep,
                    useCenter = false,
                    topLeft = Offset(cx - radius, cy - radius),
                    size = Size(radius * 2f, radius * 2f),
                    style = Stroke(width = arcStrokePx, cap = StrokeCap.Round)
                )
            }

            // Tick marks
            val totalMinorTicks = style.majorTickCount * style.minorTicksPerMajor
            for (i in 0..totalMinorTicks) {
                val tickFraction = i.toFloat() / totalMinorTicks
                val angle = startRad + sweepRad * tickFraction
                val isMajor = i % style.minorTicksPerMajor == 0
                val tickLen = if (isMajor) style.tickMajorLength.dp.toPx() else style.tickMinorLength.dp.toPx()
                val outerR = radius + arcStrokePx / 2f + 2f.dp.toPx()
                val innerR = outerR + tickLen
                val cosA = cos(angle).toFloat()
                val sinA = sin(angle).toFloat()

                drawLine(
                    color = if (isMajor) onSurfaceVariant else onSurfaceVariant.copy(alpha = 0.4f),
                    start = Offset(cx + cosA * outerR, cy + sinA * outerR),
                    end = Offset(cx + cosA * innerR, cy + sinA * innerR),
                    strokeWidth = if (isMajor) 2.dp.toPx() else 1.dp.toPx(),
                    cap = StrokeCap.Round
                )

                // Major tick labels
                if (isMajor) {
                    val labelR = innerR + 12.dp.toPx()
                    val labelX = cx + cosA * labelR
                    val labelY = cy + sinA * labelR
                    val tickValue = config.min + (config.max - config.min) * tickFraction
                    val label = formatTickLabel(tickValue, config.decimals)
                    val textLayout = textMeasurer.measure(
                        text = label,
                        style = androidx.compose.ui.text.TextStyle(
                            color = onSurfaceVariant,
                            fontSize = 9.sp
                        )
                    )
                    drawText(
                        textLayoutResult = textLayout,
                        topLeft = Offset(
                            labelX - textLayout.size.width / 2f,
                            labelY - textLayout.size.height / 2f
                        )
                    )
                }
            }

            // Needle
            val needleAngle = startRad + sweepRad * fraction
            val needleRad = radius * style.needleLength
            val needleBaseWidth = style.needleWidth.dp.toPx()
            val cosN = cos(needleAngle).toFloat()
            val sinN = sin(needleAngle).toFloat()
            val perpCos = cos(needleAngle + PI / 2).toFloat()
            val perpSin = sin(needleAngle + PI / 2).toFloat()

            // Needle shadow
            val needlePath = Path().apply {
                moveTo(cx + perpCos * needleBaseWidth, cy + perpSin * needleBaseWidth)
                lineTo(cx + cosN * needleRad, cy + sinN * needleRad)
                lineTo(cx - perpCos * needleBaseWidth, cy - perpSin * needleBaseWidth)
                close()
            }

            drawPath(
                path = needlePath,
                color = colors.needle,
                style = Stroke(width = 1.5.dp.toPx(), cap = StrokeCap.Round)
            )
            drawPath(
                path = needlePath,
                color = colors.needle
            )

            // Center cap
            drawCircle(
                color = colors.cap,
                radius = 6.dp.toPx(),
                center = Offset(cx, cy)
            )
            drawCircle(
                color = surface,
                radius = 3.dp.toPx(),
                center = Offset(cx, cy)
            )

            // Value text (center of gauge, below the cap)
            val valueText = formatValue(clampedValue, config.decimals)
            val valueLayout = textMeasurer.measure(
                text = valueText,
                style = androidx.compose.ui.text.TextStyle(
                    color = colors.valueText,
                    fontSize = 20.sp
                )
            )
            drawText(
                textLayoutResult = valueLayout,
                topLeft = Offset(
                    cx - valueLayout.size.width / 2f,
                    cy + 18.dp.toPx()
                )
            )

            // Units text
            if (config.units.isNotBlank()) {
                val unitLayout = textMeasurer.measure(
                    text = config.units,
                    style = androidx.compose.ui.text.TextStyle(
                        color = onSurfaceVariant,
                        fontSize = 10.sp
                    )
                )
                drawText(
                    textLayoutResult = unitLayout,
                    topLeft = Offset(
                        cx - unitLayout.size.width / 2f,
                        cy + 18.dp.toPx() + valueLayout.size.height + 2.dp.toPx()
                    )
                )
            }
        }

        // Label below the gauge
        if (config.label.isNotBlank()) {
            Text(
                text = config.label,
                style = MaterialTheme.typography.labelMedium,
                color = onSurfaceVariant
            )
        }
    }
}

// ========================================================================
//  Color resolution
// ========================================================================

@Immutable
private data class AnalogColors(
    val safe: Color,
    val primary: Color,
    val warning: Color,
    val danger: Color,
    val needle: Color,
    val cap: Color,
    val valueText: Color
)

private fun resolveAnalogColors(scheme: GaugeColorScheme, zone: String?): AnalogColors {
    val safe = when (scheme) {
        GaugeColorScheme.GREEN -> Color(0xFF4CAF50)
        GaugeColorScheme.BLUE -> Color(0xFF42A5F5)
        GaugeColorScheme.RED -> Color(0xFFEF5350)
        GaugeColorScheme.YELLOW -> Color(0xFFFFC107)
        GaugeColorScheme.CYAN -> Color(0xFF26C6DA)
        GaugeColorScheme.DEFAULT -> Color(0xFF5CD6C0) // matches app primary
    }
    val primary = when (scheme) {
        GaugeColorScheme.GREEN -> Color(0xFF81C784)
        GaugeColorScheme.BLUE -> Color(0xFF90CAF9)
        GaugeColorScheme.RED -> Color(0xFFEF9A9A)
        GaugeColorScheme.YELLOW -> Color(0xFFFFD54F)
        GaugeColorScheme.CYAN -> Color(0xFF80DEEA)
        GaugeColorScheme.DEFAULT -> Color(0xFF7DF5DE)
    }
    val valueText = when {
        zone == "high_danger" || zone == "low_danger" -> Color(0xFFFF5252)
        zone == "high_warning" || zone == "low_warning" -> Color(0xFFFFB74D)
        else -> primary
    }
    return AnalogColors(
        safe = safe,
        primary = primary,
        warning = Color(0xFFFFB74D),
        danger = Color(0xFFFF5252),
        needle = Color(0xFFE0E0E0),
        cap = Color(0xFFBDBDBD),
        valueText = valueText
    )
}

// ========================================================================
//  Zone arc drawing
// ========================================================================

private fun DrawScope.drawZoneArcs(
    config: GaugeWidgetConfig,
    cx: Float,
    cy: Float,
    radius: Float,
    startRad: Double,
    sweepRad: Double,
    arcStrokePx: Float
) {
    val range = config.max - config.min
    if (range == 0.0) return

    // High warning zone
    if (!config.highWarning.isNaN()) {
        val frac = ((config.highWarning - config.min) / range).coerceIn(0.0, 1.0)
        val zoneStart = startRad + sweepRad * frac
        val zoneEnd = if (!config.highDanger.isNaN()) {
            startRad + sweepRad * ((config.highDanger - config.min) / range).coerceIn(0.0, 1.0)
        } else {
            startRad + sweepRad
        }
        drawArc(
            color = Color(0xFFFFB74D).copy(alpha = 0.35f),
            startAngle = Math.toDegrees(zoneStart).toFloat(),
            sweepAngle = Math.toDegrees(zoneEnd - zoneStart).toFloat(),
            useCenter = false,
            topLeft = Offset(cx - radius, cy - radius),
            size = Size(radius * 2f, radius * 2f),
            style = Stroke(width = arcStrokePx, cap = StrokeCap.Butt)
        )
    }

    // High danger zone
    if (!config.highDanger.isNaN()) {
        val frac = ((config.highDanger - config.min) / range).coerceIn(0.0, 1.0)
        val zoneStart = startRad + sweepRad * frac
        drawArc(
            color = Color(0xFFFF5252).copy(alpha = 0.4f),
            startAngle = Math.toDegrees(zoneStart).toFloat(),
            sweepAngle = Math.toDegrees(startRad + sweepRad - zoneStart).toFloat(),
            useCenter = false,
            topLeft = Offset(cx - radius, cy - radius),
            size = Size(radius * 2f, radius * 2f),
            style = Stroke(width = arcStrokePx, cap = StrokeCap.Butt)
        )
    }

    // Low warning zone
    if (!config.lowWarning.isNaN()) {
        val frac = ((config.lowWarning - config.min) / range).coerceIn(0.0, 1.0)
        val zoneEnd = startRad + sweepRad * frac
        val zoneStart = if (!config.lowDanger.isNaN()) {
            startRad + sweepRad * ((config.lowDanger - config.min) / range).coerceIn(0.0, 1.0)
        } else {
            startRad
        }
        drawArc(
            color = Color(0xFFFFB74D).copy(alpha = 0.35f),
            startAngle = Math.toDegrees(zoneStart).toFloat(),
            sweepAngle = Math.toDegrees(zoneEnd - zoneStart).toFloat(),
            useCenter = false,
            topLeft = Offset(cx - radius, cy - radius),
            size = Size(radius * 2f, radius * 2f),
            style = Stroke(width = arcStrokePx, cap = StrokeCap.Butt)
        )
    }

    // Low danger zone
    if (!config.lowDanger.isNaN()) {
        val frac = ((config.lowDanger - config.min) / range).coerceIn(0.0, 1.0)
        val zoneEnd = startRad + sweepRad * frac
        drawArc(
            color = Color(0xFFFF5252).copy(alpha = 0.4f),
            startAngle = Math.toDegrees(startRad).toFloat(),
            sweepAngle = Math.toDegrees(zoneEnd - startRad).toFloat(),
            useCenter = false,
            topLeft = Offset(cx - radius, cy - radius),
            size = Size(radius * 2f, radius * 2f),
            style = Stroke(width = arcStrokePx, cap = StrokeCap.Butt)
        )
    }
}

// ========================================================================
//  Formatting helpers (shared across gauges)
// ========================================================================

/** Format a value with the specified number of decimal places. */
internal fun formatValue(value: Double, decimals: Int): String {
    return if (decimals <= 0) {
        String.format("%.0f", value)
    } else {
        String.format("%.${decimals}f", value)
    }
}

/** Format a tick mark label, using K/M suffixes for large values. */
internal fun formatTickLabel(value: Double, decimals: Int): String {
    return when {
        value >= 10000 && decimals == 0 -> String.format("%.0f", value)
        value >= 1000 && decimals == 0 -> String.format("%.0f", value)
        else -> formatValue(value, decimals)
    }
}
