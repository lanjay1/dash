package com.ztune.libretune.core.datalog

import com.ztune.libretune.core.ini.expression.Expr
import com.ztune.libretune.core.ini.expression.Parser as ExprParser
import com.ztune.libretune.core.ini.expression.evaluate as evalExpr

/**
 * A user-defined math channel that computes a value from other channels
 * using an expression string.
 *
 * Example:
 * ```
 * MathChannelDefinition(
 *     name = "boostTarget",
 *     expression = "rpm * 0.01 + 5",
 *     units = "psi",
 *     description = "Target boost pressure"
 * )
 * ```
 *
 * Converted from LibreTune's Rust `datalog::math_channel` module.
 */
data class MathChannelDefinition(
    val name: String = "",
    val expression: String = "",
    val units: String = "",
    val description: String = ""
)

/**
 * Engine that manages and evaluates user-defined math channels.
 *
 * Math channels allow users to define computed channels using the same
 * expression syntax as the INI parser (arithmetic, ternary, functions, etc.).
 * At evaluation time, the expression is evaluated against the current
 * channel values, with math channels able to reference both real channels
 * and previously-defined math channels.
 *
 * Expression ASTs are cached per unique expression string so that repeated
 * evaluation avoids redundant parsing.
 *
 * Thread safety: [evaluateAll] and [evaluateChannel] are safe to call from
 * any thread.  Channel registration ([addChannel]/[removeChannel]) should
 * not be called concurrently with evaluation.
 */
class MathChannelEngine {

    /** Registered math channel definitions, keyed by name. */
    private val channels = mutableMapOf<String, MathChannelDefinition>()

    /**
     * Expression parse cache: expression string → parsed AST (or null if
     * the expression could not be parsed).
     */
    private val exprCache = mutableMapOf<String, Expr?>()

    // ---------------------------------------------------------------------
    // Channel management
    // ---------------------------------------------------------------------

    /**
     * Add (or replace) a math channel definition.
     *
     * If a channel with the same [definition.name] already exists it is
     * silently replaced.  The expression cache for the new expression is
     * populated lazily on first evaluation.
     *
     * @param definition The math channel to register.
     */
    fun addChannel(definition: MathChannelDefinition) {
        require(definition.name.isNotBlank()) { "Math channel name must not be blank" }
        require(definition.expression.isNotBlank()) { "Math channel expression must not be blank" }

        // Invalidate cache for the old expression if the channel is being replaced
        val old = channels[definition.name]
        if (old != null && old.expression != definition.expression) {
            exprCache.remove(old.expression)
        }

        channels[definition.name] = definition
    }

    /**
     * Remove a math channel by name.
     *
     * If no channel with the given name exists this is a no-op.
     */
    fun removeChannel(name: String) {
        val removed = channels.remove(name)
        if (removed != null) {
            exprCache.remove(removed.expression)
        }
    }

    /**
     * Remove all registered math channels and clear the expression cache.
     */
    fun clearAll() {
        channels.clear()
        exprCache.clear()
    }

    /** Get the names of all registered math channels in insertion order. */
    fun getChannelNames(): List<String> = channels.keys.toList()

    /** Get the definition for a specific math channel, or `null`. */
    fun getChannelDefinition(name: String): MathChannelDefinition? = channels[name]

    /**
     * Get all registered channel definitions.
     */
    fun getAllDefinitions(): List<MathChannelDefinition> = channels.values.toList()

    // ---------------------------------------------------------------------
    // Evaluation
    // ---------------------------------------------------------------------

    /**
     * Evaluate all registered math channels against the given values.
     *
     * Channels are evaluated in registration order so that a math channel
     * may reference the result of an earlier-defined math channel.  The
     * computed values are merged into the result map alongside the original
     * [currentValues].
     *
     * If a math channel's expression references an undefined variable or
     * fails to evaluate, it receives `Double.NaN` in the output.
     *
     * @param currentValues The current real channel values (`channelName → value`).
     * @return A new map containing both the original values and all computed
     *   math channel values.
     */
    fun evaluateAll(currentValues: Map<String, Double>): Map<String, Double> {
        if (channels.isEmpty()) return currentValues

        // Use a mutable copy so math channels can reference earlier math channels
        val merged = currentValues.toMutableMap()

        for ((name, def) in channels) {
            val value = evaluateChannelInternal(def, merged)
            merged[name] = value
        }

        return merged
    }

    /**
     * Evaluate a single math channel.
     *
     * Unlike [evaluateAll], this does NOT include the results of other
     * math channels in the variable set.  Only the values from
     * [currentValues] are available.  Use [evaluateAll] if you need
     * inter-channel dependencies.
     *
     * @param name The registered math channel name.
     * @param currentValues The current real channel values.
     * @return The computed value, or `Double.NaN` if the channel is not
     *   registered or evaluation fails.
     */
    fun evaluateChannel(name: String, currentValues: Map<String, Double>): Double {
        val def = channels[name] ?: return Double.NaN
        return evaluateChannelInternal(def, currentValues)
    }

    // ---------------------------------------------------------------------
    // Internal
    // ---------------------------------------------------------------------

    /**
     * Evaluate a single channel definition against the given variable set.
     *
     * Parses and caches the expression AST on first call.  Returns `NaN`
     * on parse or evaluation failure.
     */
    private fun evaluateChannelInternal(
        def: MathChannelDefinition,
        variables: Map<String, Double>
    ): Double {
        // Lazily parse and cache the AST
        val ast: Expr? = exprCache.getOrPut(def.expression) {
            ExprParser(def.expression).parse().getOrNull()
        } ?: return Double.NaN  // parse failure

        return evalExpr(ast, variables).getOrDefault(Double.NaN)
    }
}
