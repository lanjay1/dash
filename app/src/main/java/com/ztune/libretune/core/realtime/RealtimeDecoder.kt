package com.ztune.libretune.core.realtime

import com.ztune.libretune.core.ini.EcuDefinition
import com.ztune.libretune.core.ini.types.*
import com.ztune.libretune.core.tune.ByteOrderReader
import com.ztune.libretune.core.tune.ByteOrderWriter
import com.ztune.libretune.core.ini.expression.Parser as ExprParser
import com.ztune.libretune.core.ini.expression.evaluate as evalExpr

/**
 * Decodes raw ECU realtime data bytes into named channel values.
 *
 * Converts the Rust `realtime/` module: takes a flat byte buffer produced by
 * the ECU's output-channel stream and, using the offset / data-type / scale /
 * translate metadata from the [EcuDefinition], produces a map of
 * `channelName → displayValue`.
 *
 * Calculated (expression-based) channels are evaluated after all direct-read
 * channels have been decoded, so they may reference earlier channel values.
 */
class RealtimeDecoder(private val definition: EcuDefinition) {

    /** Parsed-expression cache: expression string → AST. */
    private val exprCache = mutableMapOf<String, com.ztune.libretune.core.ini.expression.Expr>()

    // ----------------------------------------------------------------------
    // Public API
    // ----------------------------------------------------------------------

    /**
     * Decode every output channel defined in the [EcuDefinition] from [rawData].
     *
     * Direct-read channels are decoded first (offset + data-type → raw →
     * `raw * scale + translate`).  Expression-based channels are then evaluated
     * in definition order so they may reference values decoded earlier in the
     * same pass.
     *
     * @return A map of `channelName → decoded display value`.  Channels that
     *   cannot be decoded (e.g. out-of-bounds offset) receive `Double.NaN`.
     */
    fun decodeRealtimeData(rawData: ByteArray): Map<String, Double> {
        val values = mutableMapOf<String, Double>()
        val reader = ByteOrderReader(rawData, definition.endianness)

        for ((name, channel) in definition.outputChannels) {
            if (channel.expression != null) {
                // Calculated channel – evaluate expression against already-decoded values
                values[name] = evaluateChannelExpression(channel.expression, values)
            } else {
                // Direct read from offset
                try {
                    reader.setPosition(channel.offset)
                    val raw = reader.readValue(channel.dataType)
                    values[name] = raw * channel.scale + channel.translate
                } catch (e: Exception) {
                    values[name] = Double.NaN
                }
            }
        }

        return values
    }

    /**
     * Decode a single [constant] from the given [pageData] byte array.
     *
     * @param name     The constant's symbolic name (unused in decoding but
     *                 included for API clarity / logging).
     * @param pageData Raw bytes for the page that contains this constant.
     * @param constant The [Constant] metadata (offset, data-type, scale, translate).
     * @return The display value, or `Double.NaN` if the read fails.
     */
    fun decodeConstant(name: String, pageData: ByteArray, constant: Constant): Double {
        val reader = ByteOrderReader(pageData, definition.endianness)
        val raw = reader.readValueAt(constant.offset, constant.dataType)
            ?: return Double.NaN
        return raw * constant.scale + constant.translate
    }

    /**
     * Encode a display value back to raw bytes for a given [constant].
     *
     * Performs the inverse of [decodeConstant]:
     * `raw = (value - translate) / scale`, then writes the raw integer/float
     * bytes in the definition's endianness.
     *
     * @return A byte array containing exactly the encoded value.
     */
    fun encodeConstant(value: Double, constant: Constant): ByteArray {
        val writer = ByteOrderWriter(8, definition.endianness)
        val raw = (value - constant.translate) / constant.scale
        writer.writeValue(constant.dataType, raw)
        return writer.toByteArray()
    }

    /**
     * Decode a 2-D or 3-D table's cell values from [pageData].
     *
     * Cells are laid out row-major: index = `row * cols + col`.
     * Each cell is at `valuesOffset + index * dataType.byteSize`.
     *
     * @return A list of rows, each row being a list of decoded display values.
     *   Cells that cannot be decoded receive `Double.NaN`.
     */
    fun decodeTable(pageData: ByteArray, table: TableDefinition): List<List<Double>> {
        val reader = ByteOrderReader(pageData, definition.endianness)
        val result = mutableListOf<List<Double>>()

        for (row in 0 until table.rows) {
            val rowValues = mutableListOf<Double>()
            for (col in 0 until table.cols) {
                val cellOffset = table.valuesOffset + (row * table.cols + col) * table.dataType.byteSize
                try {
                    val raw = reader.readValueAt(cellOffset, table.dataType)
                        ?: throw IllegalArgumentException("readValueAt returned null")
                    rowValues.add(raw * table.scale + table.translate)
                } catch (e: Exception) {
                    rowValues.add(Double.NaN)
                }
            }
            result.add(rowValues)
        }

        return result
    }

    /**
     * Decode a 1-D curve's values from [pageData].
     *
     * @return A list of decoded display values, one per bin.
     */
    fun decodeCurve(pageData: ByteArray, curve: CurveDefinition): List<Double> {
        val reader = ByteOrderReader(pageData, definition.endianness)
        val result = mutableListOf<Double>()

        for (i in 0 until curve.size) {
            val offset = curve.valuesOffset + i * curve.dataType.byteSize
            try {
                val raw = reader.readValueAt(offset, curve.dataType)
                    ?: throw IllegalArgumentException("readValueAt returned null")
                result.add(raw * curve.scale + curve.translate)
            } catch (e: Exception) {
                result.add(Double.NaN)
            }
        }

        return result
    }

    /**
     * Decode an axis (bins) for a table from [pageData].
     *
     * @param axis The axis definition (binsOffset, size, dataType, scale, translate).
     * @return A list of decoded bin values, or an empty list if [axis] is null.
     */
    fun decodeTableAxis(pageData: ByteArray, axis: TableAxis?): List<Double> {
        if (axis == null) return emptyList()

        val reader = ByteOrderReader(pageData, definition.endianness)
        val result = mutableListOf<Double>()

        for (i in 0 until axis.size) {
            val offset = axis.binsOffset + i * axis.dataType.byteSize
            try {
                val raw = reader.readValueAt(offset, axis.dataType)
                    ?: throw IllegalArgumentException("readValueAt returned null")
                result.add(raw * axis.scale + axis.translate)
            } catch (e: Exception) {
                result.add(Double.NaN)
            }
        }

        return result
    }

    // ----------------------------------------------------------------------
    // Internal helpers
    // ----------------------------------------------------------------------

    /**
     * Evaluate an output-channel expression string.
     *
     * Parsed ASTs are cached per unique expression string so that repeated
     * calls avoid redundant lexing/parsing.
     *
     * @param expression  The raw expression text from the INI.
     * @param currentValues  Already-decoded channel values available as variables.
     * @return The computed value, or 0.0 on parse / evaluation failure.
     */
    private fun evaluateChannelExpression(
        expression: String,
        currentValues: Map<String, Double>
    ): Double {
        val ast = exprCache.getOrPut(expression) {
            ExprParser(expression).parse().getOrNull()
                ?: return 0.0  // parse failure – bail out
        }
        return evalExpr(ast, currentValues).getOrDefault(0.0)
    }
}
