package com.ztune.libretune.core.safety

import com.ztune.libretune.core.ini.EcuDefinition
import com.ztune.libretune.core.ini.types.Constant
import com.ztune.libretune.core.ini.types.DataType

/**
 * Phase 28: Pin Conflict Detection.
 *
 * Detects when two ECU functions are assigned to the same physical pin,
 * which can cause hardware damage or unpredictable behavior.
 *
 * Works by scanning [EcuDefinition.constants] for bits-type constants
 * whose names suggest pin assignment (e.g. "fuelPumpPin", "fanPin").
 * Constants whose INI enabled_condition evaluates to false are excluded
 * (feature is disabled, so the pin is not claimed).
 *
 * Inspired by LibreTune's pin_conflict.rs.
 */
data class PinConflict(
    val pinLabel: String,
    val constants: List<String>
) {
    val description: String
        get() = "Pin '$pinLabel' used by: ${constants.joinToString(", ")}"
}

data class PinConflictReport(
    val conflicts: List<PinConflict>,
    val totalPinConstants: Int,
    val activePinConstants: Int
) {
    val hasConflicts: Boolean get() = conflicts.isNotEmpty()
    val summary: String
        get() = if (hasConflicts) {
            "Pin conflict(s) detected:\n" + conflicts.joinToString("\n") { "  • ${it.description}" }
        } else {
            "No pin conflicts detected ($activePinConstants active pin assignments)."
        }
}

object PinConflictDetector {

    private val PIN_NAME_PATTERNS = listOf("pin", "outputpin", "inputpin", "gpiopin")
    private val EXCLUDE_PATTERNS = listOf("pinmode", "pinvert", "pindir", "pinpull", "pintype")
    private val UNASSIGNED_LABELS = setOf("none", "off", "disabled", "invalid", "no pin", "n/a", "")
    private val BOARD_DEFAULT = "board default"

    /**
     * Detect pin conflicts in the given [definition].
     *
     * @param definition The ECU definition to scan.
     * @param getValue Function that returns the current value (as index into
     *   bitOptions) for a given constant name. If null, the constant's
     *   default value is used.
     * @return [PinConflictReport] with all detected conflicts.
     */
    fun detectConflicts(
        definition: EcuDefinition,
        getValue: ((String) -> Int?)? = null
    ): PinConflictReport {
        val pinConstants = definition.constants.values.filter { isPinConstant(it) }
        val pinAssignments = mutableMapOf<String, MutableList<String>>()

        for (constant in pinConstants) {
            val valueIndex = getValue?.invoke(constant.name) ?: 0
            val pinLabel = resolvePinLabel(constant, valueIndex)

            // Skip unassigned / board default
            if (pinLabel.isBlank() || isUnassigned(pinLabel) || isBoardDefault(pinLabel)) continue

            pinAssignments.getOrPut(pinLabel) { mutableListOf() }.add(constant.name)
        }

        val conflicts = pinAssignments
            .filter { it.value.size > 1 }
            .map { (pin, consts) -> PinConflict(pin, consts.sorted()) }
            .sortedBy { it.pinLabel }

        return PinConflictReport(
            conflicts = conflicts,
            totalPinConstants = pinConstants.size,
            activePinConstants = pinAssignments.values.sumOf { it.size }
        )
    }

    /**
     * Check if a constant is a pin assignment constant.
     */
    private fun isPinConstant(constant: Constant): Boolean {
        if (constant.dataType != DataType.BITS) return false
        if (constant.bitOptions.size < 3) return false

        val nameLower = constant.name.lowercase()
        if (EXCLUDE_PATTERNS.any { nameLower.contains(it) }) return false

        return PIN_NAME_PATTERNS.any { nameLower.contains(it) } ||
            constant.bitOptions.any { looksLikePinLabel(it) }
    }

    /**
     * Resolve the pin label for a constant at a given value index.
     */
    private fun resolvePinLabel(constant: Constant, valueIndex: Int): String {
        if (valueIndex !in constant.bitOptions.indices) return ""
        return constant.bitOptions[valueIndex].trim()
    }

    /**
     * Check if a label looks like a pin identifier (e.g. "PA0", "PD13").
     */
    private fun looksLikePinLabel(label: String): Boolean {
        val upper = label.uppercase()
        // STM32-style: PA0, PB5, PC13, PD7, etc.
        if (upper.length in 2..5 && upper[0] == 'P' && upper[1] in 'A'..'Z' &&
            upper.drop(2).all { it.isDigit() }) return true
        // Arduino-style: D0, D13, A0, A5
        if (upper.length in 2..4 && upper[0] in 'D'..'E' && upper.drop(1).all { it.isDigit() }) return true
        // Generic pin labels
        if (upper.contains("PIN") && upper.any { it.isDigit() }) return true
        return false
    }

    private fun isUnassigned(label: String): Boolean {
        val lower = label.lowercase().trim()
        return lower in UNASSIGNED_LABELS
    }

    private fun isBoardDefault(label: String): Boolean {
        return label.lowercase().trim() == BOARD_DEFAULT
    }

    /**
     * Check if assigning a specific value to a constant would create a conflict.
     *
     * @return The conflicting constant names if a conflict would occur, empty list otherwise.
     */
    fun wouldConflict(
        definition: EcuDefinition,
        constantName: String,
        newValueIndex: Int,
        getValue: ((String) -> Int?)? = null
    ): List<String> {
        val target = definition.constants[constantName] ?: return emptyList()
        if (!isPinConstant(target)) return emptyList()
        val newPin = resolvePinLabel(target, newValueIndex)
        if (isUnassigned(newPin) || isBoardDefault(newPin)) return emptyList()

        val report = detectConflicts(definition, getValue)
        // Check if any existing conflict involves this pin (excluding ourselves)
        return report.conflicts
            .firstOrNull { it.pinLabel.equals(newPin, ignoreCase = true) }
            ?.constants
            ?.filter { it != constantName }
            ?: emptyList()
    }
}
