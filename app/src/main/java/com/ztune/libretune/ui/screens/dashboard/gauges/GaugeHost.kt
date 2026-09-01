@file:Suppress("unused")

package com.ztune.libretune.ui.screens.dashboard.gauges

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ztune.libretune.core.dash.GaugeWidgetConfig
import com.ztune.libretune.core.dash.GaugeWidgetType

/**
 * Dispatches to the appropriate gauge composable based on [GaugeWidgetConfig.type].
 *
 * This is the single entry point for rendering any gauge widget in the dashboard grid.
 * Empty slots render a placeholder with an add button.
 *
 * @param config    The gauge widget configuration.
 * @param value     The current channel value for this gauge.
 * @param modifier  Compose modifier.
 * @param onTap     Callback when the user taps the gauge (for configuration).
 * @param isEditing Whether the dashboard is in edit mode (shows delete handles, etc.).
 */
@Composable
fun GaugeHost(
    config: GaugeWidgetConfig,
    value: Double,
    modifier: Modifier = Modifier,
    onTap: (() -> Unit)? = null,
    isEditing: Boolean = false
) {
    val surfaceVariant = MaterialTheme.colorScheme.surfaceVariant
    val shape = RoundedCornerShape(16.dp)
    val clickModifier = if (onTap != null) {
        Modifier.clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            onClick = onTap
        )
    } else {
        Modifier
    }

    Box(
        modifier = modifier
            .clip(shape)
            .then(clickModifier)
            .then(
                if (isEditing) {
                    Modifier.border(2.dp, MaterialTheme.colorScheme.primary, shape)
                } else {
                    Modifier
                }
            )
            .padding(4.dp),
        contentAlignment = Alignment.Center
    ) {
        when (config.type) {
            // Analog variants
            GaugeWidgetType.ANALOG_SWEEP,
            GaugeWidgetType.ANALOG_HALF,
            GaugeWidgetType.ANALOG_QUARTER -> {
                AnalogGauge(
                    config = config,
                    value = value,
                    modifier = Modifier.fillMaxSize()
                )
            }

            // Digital variants
            GaugeWidgetType.DIGITAL_LARGE,
            GaugeWidgetType.DIGITAL_COMPACT -> {
                DigitalGauge(
                    config = config,
                    value = value,
                    modifier = Modifier.fillMaxSize()
                )
            }

            // Bar variants
            GaugeWidgetType.BAR_HORIZONTAL,
            GaugeWidgetType.BAR_VERTICAL -> {
                BarGauge(
                    config = config,
                    value = value,
                    modifier = Modifier.fillMaxSize()
                )
            }

            // Wideband (uses analog sweep style with AFR-optimized defaults)
            GaugeWidgetType.WIDEBAND_LINEAR,
            GaugeWidgetType.WIDEBAND_LOG -> {
                AnalogGauge(
                    config = config,
                    value = value,
                    modifier = Modifier.fillMaxSize()
                )
            }

            // Indicator (boolean on/off light)
            GaugeWidgetType.INDICATOR -> {
                IndicatorGauge(
                    config = config,
                    value = value,
                    modifier = Modifier.fillMaxSize()
                )
            }

            // Text readout
            GaugeWidgetType.TEXT -> {
                TextGauge(
                    config = config,
                    value = value,
                    modifier = Modifier.fillMaxSize()
                )
            }

            // Empty placeholder slot
            GaugeWidgetType.EMPTY -> {
                EmptySlot(
                    onTap = onTap,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}

/**
 * Boolean indicator light — shows ON (lit circle) when value > 0, OFF when 0.
 */
@Composable
private fun IndicatorGauge(
    config: GaugeWidgetConfig,
    value: Double,
    modifier: Modifier = Modifier
) {
    val isOn = value > 0.0
    val onColor = Color(0xFF4CAF50)
    val offColor = MaterialTheme.colorScheme.surfaceVariant
    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant
    val infiniteTransition = rememberInfiniteTransition(label = "indicator_pulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.85f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(800),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )

    Column(
        modifier = modifier.padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .background(
                    color = if (isOn) onColor else offColor,
                    shape = CircleShape
                )
                .alpha(if (isOn) pulseAlpha else 1f),
            contentAlignment = Alignment.Center
        ) {
            if (isOn) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = "On",
                    tint = Color.White,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
        if (config.label.isNotBlank()) {
            Text(
                text = config.label,
                style = MaterialTheme.typography.labelSmall,
                color = onSurfaceVariant,
                textAlign = TextAlign.Center,
                maxLines = 2
            )
        }
    }
}

/**
 * Plain text readout — value and label, no graphical gauge element.
 */
@Composable
private fun TextGauge(
    config: GaugeWidgetConfig,
    value: Double,
    modifier: Modifier = Modifier
) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant

    Column(
        modifier = modifier.padding(8.dp),
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
        }
        Text(
            text = formatValue(value, config.decimals),
            color = onSurface,
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
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

/**
 * Empty slot placeholder — dashed border with an add icon.
 * Tapping it triggers the [onTap] callback (to open the widget picker).
 */
@Composable
private fun EmptySlot(
    onTap: (() -> Unit)?,
    modifier: Modifier = Modifier
) {
    val outline = MaterialTheme.colorScheme.outline

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .then(
                if (onTap != null) {
                    Modifier.clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onTap
                    )
                } else {
                    Modifier
                }
            )
            .border(1.dp, outline.copy(alpha = 0.4f), RoundedCornerShape(12.dp)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = Icons.Default.Add,
                contentDescription = "Add gauge",
                tint = outline.copy(alpha = 0.5f),
                modifier = Modifier.size(24.dp)
            )
        }
    }
}
