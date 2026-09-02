package com.ztune.libretune.ui.screens.tune_editor

import androidx.compose.ui.graphics.Color

/**
 * Utility for computing cell background colors based on a value's position
 * in the min–max range of the table.
 *
 * The gradient goes: **blue** (low) → **green** (mid) → **red** (high).
 */
object TableColorUtils {

    /**
 * Maps a [value] within [min]..[max] to a gradient color.
 *
 * - Values at or below [min] return pure blue.
 * - Values at or above [max] return pure red.
 * - Values in the lower half of the range interpolate blue → green.
 * - Values in the upper half of the range interpolate green → red.
 *
 * @param value The cell value to colorise.
 * @param min   The minimum expected value (maps to blue).
 * @param max   The maximum expected value (maps to red).
 * @return A [Color] representing the cell's background.
 */
    fun valueToColor(value: Double, min: Double, max: Double): Color {
        // Guard: avoid division by zero when range is flat.
        val range = max - min
        if (range <= 0.0) return Color(0xFF00AA00) // neutral green when no range

        // Clamp the value into [min, max] so we never overshoot.
        val fraction = (value - min) / range
        val t = fraction.coerceIn(0.0, 1.0)

        // Blue  (0.0) → Green (0.5) → Red   (1.0)
        // R:     0       → 0       → 255
        // G:     0       → 200     → 0
        // B:     255     → 0       → 0
        return when {
            t <= 0.5 -> {
                // Blue → Green
                val localT = t / 0.5 // 0..1 within the first half
                val red = 0f
                val green = (200f * localT)
                val blue = (255f * (1f - localT))
                Color(
                    red = red,
                    green = green,
                    blue = blue,
                    alpha = 0.85f
                )
            }
            else -> {
                // Green → Red
                val localT = (t - 0.5) / 0.5 // 0..1 within the second half
                val red = 255f * localT
                val green = 200f * (1f - localT)
                val blue = 0f
                Color(
                    red = red,
                    green = green,
                    blue = blue,
                    alpha = 0.85f
                )
            }
        }
    }

    /**
 * Determines a readable text color for a given background color.
 * Uses luminance calculation to pick black or white text.
 *
 * @param backgroundColor The cell's background color.
 * @return [Color.White] on dark backgrounds, [Color.Black] on light ones.
 */
    fun contrastTextColor(backgroundColor: Color): Color {
        val luminance = 0.299f * backgroundColor.red +
                0.587f * backgroundColor.green +
                0.114f * backgroundColor.blue
        return if (luminance > 0.5f) Color.Black else Color.White
    }
}
