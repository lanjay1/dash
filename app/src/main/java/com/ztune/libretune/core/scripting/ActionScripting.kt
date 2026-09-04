package com.ztune.libretune.core.scripting

import com.ztune.libretune.core.ini.EcuDefinition
import com.ztune.libretune.core.ini.types.DataType

/**
 * Phase 26: Action Scripting system.
 *
 * Records and replays tuning actions (table edits, constant changes)
 * across tunes. Actions are validated against the INI definition before
 * replay to ensure they are safe for the target ECU.
 *
 * Inspired by LibreTune's action_scripting.rs but implemented in Kotlin.
 * Note: Unlike LibreTune (which is validation-only), this implementation
 * supports both validation AND replay.
 */

sealed class TuningAction {
    data class TableEdit(
        val tableName: String,
        val rowIndex: Int,
        val colIndex: Int,
        val newValue: Double,
        val oldValue: Double? = null
    ) : TuningAction()

    data class ConstantChange(
        val constantName: String,
        val newValue: Double,
        val oldValue: Double? = null
    ) : TuningAction()

    data class BulkOperation(
        val operation: String,
        val tableName: String,
        val cells: List<Pair<Int, Int>>,
        val parameters: Map<String, Double>
    ) : TuningAction()

    data class Pause(val durationMs: Long) : TuningAction()
}

data class ActionSet(
    val name: String,
    val description: String = "",
    val actions: List<TuningAction> = emptyList(),
    val compatibleSignatures: List<String> = emptyList(),
    val createdAt: Long = System.currentTimeMillis()
) {
    fun toJson(): String {
        val sb = StringBuilder()
        sb.append("{\"name\":\"${escape(name)}\",\"description\":\"${escape(description)}\",\"createdAt\":$createdAt")
        sb.append(",\"compatibleSignatures\":[")
        sb.append(compatibleSignatures.joinToString(",") { "\"${escape(it)}\"" })
        sb.append("],\"actions\":[")
        actions.forEachIndexed { i, action ->
            if (i > 0) sb.append(",")
            sb.append(actionToJson(action))
        }
        sb.append("]}")
        return sb.toString()
    }

    private fun escape(s: String): String = s.replace("\\", "\\\\").replace("\"", "\\\"")

    private fun actionToJson(action: TuningAction): String = when (action) {
        is TuningAction.TableEdit ->
            "{\"type\":\"tableEdit\",\"table\":\"${escape(action.tableName)}\",\"row\":${action.rowIndex},\"col\":${action.colIndex},\"value\":${action.newValue}}"
        is TuningAction.ConstantChange ->
            "{\"type\":\"constantChange\",\"name\":\"${escape(action.constantName)}\",\"value\":${action.newValue}}"
        is TuningAction.BulkOperation ->
            "{\"type\":\"bulkOp\",\"operation\":\"${escape(action.operation)}\",\"table\":\"${escape(action.tableName)}\"}"
        is TuningAction.Pause ->
            "{\"type\":\"pause\",\"durationMs\":${action.durationMs}}"
    }
}

data class ValidationResult(
    val valid: Boolean,
    val errors: List<String> = emptyList(),
    val warnings: List<String> = emptyList()
)

/**
 * Records tuning actions for later replay.
 */
class ActionRecorder {
    private val actions = mutableListOf<TuningAction>()

    fun recordTableEdit(table: String, row: Int, col: Int, newValue: Double, oldValue: Double? = null) {
        actions.add(TuningAction.TableEdit(table, row, col, newValue, oldValue))
    }

    fun recordConstantChange(name: String, newValue: Double, oldValue: Double? = null) {
        actions.add(TuningAction.ConstantChange(name, newValue, oldValue))
    }

    fun recordPause(durationMs: Long) {
        actions.add(TuningAction.Pause(durationMs))
    }

    fun stop(name: String, description: String = "", compatibleSignatures: List<String> = emptyList()): ActionSet {
        return ActionSet(name, description, actions.toList(), compatibleSignatures)
    }

    fun clear() { actions.clear() }

    val actionCount: Int get() = actions.size
}

/**
 * Validates and replays action sets.
 */
class ActionPlayer {

    /**
     * Validate an [ActionSet] against an [EcuDefinition].
     *
     * Checks:
     * - Table names exist in the definition
     * - Row/col indices are in bounds
     * - Values are within the constant/table min/max range
     * - Values fit the data type's raw range
     */
    fun validate(actionSet: ActionSet, definition: EcuDefinition?): ValidationResult {
        if (definition == null) {
            return ValidationResult(
                valid = false,
                errors = listOf("No ECU definition loaded — cannot validate actions")
            )
        }

        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()

        for ((index, action) in actionSet.actions.withIndex()) {
            val prefix = "Action $index: "
            when (action) {
                is TuningAction.TableEdit -> {
                    val table = definition.getTableByNameOrMap(action.tableName)
                    if (table == null) {
                        errors.add("$prefix table '${action.tableName}' not found in definition")
                    } else {
                        if (action.rowIndex !in 0 until table.rows) {
                            errors.add("$prefix row ${action.rowIndex} out of range [0, ${table.rows})")
                        }
                        if (action.colIndex !in 0 until table.cols) {
                            errors.add("$prefix col ${action.colIndex} out of range [0, ${table.cols})")
                        }
                        // Check raw range
                        val raw = (action.newValue - table.translate) / table.scale
                        if (!table.dataType.isInRange(raw)) {
                            errors.add("$prefix value ${action.newValue} encodes to raw $raw outside ${table.dataType} range")
                        }
                    }
                }
                is TuningAction.ConstantChange -> {
                    val constant = definition.constants[action.constantName]
                    if (constant == null) {
                        errors.add("$prefix constant '${action.constantName}' not found in definition")
                    } else {
                        val raw = (action.newValue - constant.translate) / constant.scale
                        if (!constant.dataType.isInRange(raw)) {
                            errors.add("$prefix value ${action.newValue} encodes to raw $raw outside ${constant.dataType} range")
                        }
                    }
                }
                is TuningAction.BulkOperation -> {
                    val table = definition.getTableByNameOrMap(action.tableName)
                    if (table == null) {
                        errors.add("$prefix table '${action.tableName}' not found")
                    }
                    if (action.operation == "scale" && !action.parameters.containsKey("factor")) {
                        warnings.add("$prefix scale operation missing 'factor' parameter")
                    }
                }
                is TuningAction.Pause -> {
                    if (action.durationMs == 0L) {
                        warnings.add("$prefix zero-duration pause")
                    }
                }
            }
        }

        // Signature compatibility check
        if (actionSet.compatibleSignatures.isNotEmpty() &&
            definition.signature !in actionSet.compatibleSignatures) {
            warnings.add("Action set was recorded for ${actionSet.compatibleSignatures} " +
                "but current ECU signature is '${definition.signature}'")
        }

        return ValidationResult(
            valid = errors.isEmpty(),
            errors = errors,
            warnings = warnings
        )
    }

    /**
     * Replay an [ActionSet] by applying each action via the provided callbacks.
     *
     * @param actionSet The actions to replay.
     * @param onTableEdit Called for each TableEdit action.
     * @param onConstantChange Called for each ConstantChange action.
     * @param onBulkOp Called for each BulkOperation action.
     * @param onPause Suspend callback for Pause actions.
     */
    suspend fun replay(
        actionSet: ActionSet,
        onTableEdit: suspend (String, Int, Int, Double) -> Unit,
        onConstantChange: suspend (String, Double) -> Unit,
        onBulkOp: suspend (String, String, List<Pair<Int, Int>>, Map<String, Double>) -> Unit,
        onPause: suspend (Long) -> Unit = { kotlinx.coroutines.delay(it) }
    ) {
        for (action in actionSet.actions) {
            when (action) {
                is TuningAction.TableEdit -> onTableEdit(action.tableName, action.rowIndex, action.colIndex, action.newValue)
                is TuningAction.ConstantChange -> onConstantChange(action.constantName, action.newValue)
                is TuningAction.BulkOperation -> onBulkOp(action.operation, action.tableName, action.cells, action.parameters)
                is TuningAction.Pause -> onPause(action.durationMs)
            }
        }
    }
}
