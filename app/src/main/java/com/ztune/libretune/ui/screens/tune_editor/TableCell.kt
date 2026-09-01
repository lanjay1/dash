package com.ztune.libretune.ui.screens.tune_editor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * A single table cell that displays a formatted value with a color-coded background.
 *
 * @param value      The numeric value to display.
 * @param format     A [String.format]-style pattern, e.g. "0.0" or "0.00".
 * @param min        The minimum value in the table (for color mapping).
 * @param max        The maximum value in the table (for color mapping).
 * @param isSelected Whether this cell is currently selected by the user.
 * @param onClick    Callback invoked when the cell is tapped.
 */
@Composable
fun TableCell(
    value: Double,
    format: String,
    min: Double,
    max: Double,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val backgroundColor = TableColorUtils.valueToColor(value, min, max)
    val textColor = TableColorUtils.contrastTextColor(backgroundColor)
    val displayText = formatCellValue(value, format)

    Box(
        modifier = modifier
            .width(80.dp)
            .height(48.dp)
            .clipToBounds()
            .then(
                if (isSelected) {
                    Modifier.border(
                        width = 2.dp,
                        color = MaterialTheme.colorScheme.primary,
                        shape = MaterialTheme.shapes.extraSmall
                    )
                } else {
                    Modifier.border(
                        width = 0.5.dp,
                        color = MaterialTheme.colorScheme.outlineVariant,
                        shape = MaterialTheme.shapes.extraSmall
                    )
                }
            )
            .background(backgroundColor)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = displayText,
            color = textColor,
            fontSize = 11.sp,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Clip
        )
    }
}

/**
 * A single axis-bin label cell (the header row or header column).
 *
 * @param label The bin value string to display.
 * @param isXHeader `true` for X-axis headers (top), `false` for Y-axis headers (left).
 */
@Composable
fun TableBinCell(
    label: String,
    isXHeader: Boolean,
    modifier: Modifier = Modifier,
) {
    val bgColor = MaterialTheme.colorScheme.surfaceVariant
    val textColor = MaterialTheme.colorScheme.onSurfaceVariant

    Box(
        modifier = modifier
            .then(
                if (isXHeader) {
                    Modifier
                        .width(80.dp)
                        .height(32.dp)
                } else {
                    Modifier
                        .width(56.dp)
                        .height(48.dp)
                }
            )
            .background(bgColor)
            .border(
                width = 0.5.dp,
                color = MaterialTheme.colorScheme.outlineVariant,
                shape = MaterialTheme.shapes.extraSmall
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = textColor,
            fontSize = 10.sp,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

// ---------------------------------------------------------------------------
//  Internal helpers
// ---------------------------------------------------------------------------

/**
 * Formats a double value using the given format pattern.
 *
 * Supports patterns like "0.0", "0.00", "0" (integer), etc.
 * Falls back to the raw [Double.toString] if formatting fails.
 */
internal fun formatCellValue(value: Double, format: String): String {
    return try {
        String.format("%$format", value)
    } catch (_: Exception) {
        value.toString()
    }
}
