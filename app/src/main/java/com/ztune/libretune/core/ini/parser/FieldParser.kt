package com.ztune.libretune.core.ini.parser

import com.ztune.libretune.core.ini.types.*

/**
 * Utility functions for parsing individual field values from INI lines.
 * These are extracted as a standalone object so both [IniParser] and any
 * future post-processing code can reuse them.
 */
object FieldParser {

    // ------------------------------------------------------------------
    // Data type mapping
    // ------------------------------------------------------------------

    /**
     * Parse a data-type string such as "u08", "s16", "f32", "bits", "string".
     * Case-insensitive.  Returns [DataType.U08] for unrecognised strings.
     */
    fun parseDataType(s: String): DataType {
        return when (s.trim().lowercase()) {
            "u08", "u8", "unsigned8", "uint8" -> DataType.U08
            "s08", "s8", "int8", "signed8" -> DataType.S08
            "u16", "unsigned16", "uint16", "word" -> DataType.U16
            "s16", "int16", "signed16" -> DataType.S16
            "u32", "unsigned32", "uint32", "dword" -> DataType.U32
            "s32", "int32", "signed32" -> DataType.S32
            "f32", "float", "float32" -> DataType.F32
            "bits", "bitfield" -> DataType.BITS
            "string", "str", "ascii" -> DataType.STRING
            "array" -> DataType.U08   // arrays fall back to u08 element type
            "2darray", "2d_array" -> DataType.U08
            else -> DataType.U08     // graceful fallback
        }
    }

    // ------------------------------------------------------------------
    // Endianness
    // ------------------------------------------------------------------

    /**
     * Parse an endianness string: "big", "little", "bigEndian", "littleEndian".
     * Returns [Endianness.DEFAULT] (little-endian) for unrecognised input.
     */
    fun parseEndianness(s: String): Endianness = when (s.trim().lowercase()) {
        "big", "bigendian", "big_endian", "be" -> Endianness.BIG_ENDIAN
        "little", "littleendian", "little_endian", "le" -> Endianness.LITTLE_ENDIAN
        else -> Endianness.DEFAULT
    }

    // ------------------------------------------------------------------
    // Table type
    // ------------------------------------------------------------------

    /**
     * Parse a table-type specifier: "2D", "3D", "1D", "curve".
     * Returns [TableType.TABLE_3D] as the default.
     */
    fun parseTableType(s: String): TableType = when (s.trim().lowercase()) {
        "1d", "1dtable", "table1d" -> TableType.TABLE_1D
        "2d", "2dtable", "table2d", "xtable" -> TableType.TABLE_2D
        "curve" -> TableType.TABLE_2D   // curves are 2D under the hood
        else -> TableType.TABLE_3D       // 3D is the most common default
    }

    // ------------------------------------------------------------------
    // Number parsing
    // ------------------------------------------------------------------

    /**
     * Parse an integer, handling hex (`0x…` / `0X…`), and plain decimal.
     * Returns 0 on failure.
     */
    fun parseInt(s: String): Int {
        val t = s.trim()
        if (t.isEmpty()) return 0
        return try {
            if (t.startsWith("0x", ignoreCase = true)) {
                t.substring(2).toInt(16)
            } else if (t.startsWith("-0x", ignoreCase = true)) {
                -t.substring(3).toInt(16)
            } else {
                // strip trailing 'L' suffix that some INI files use
                t.removeSuffix("L").removeSuffix("l").toInt()
            }
        } catch (_: NumberFormatException) {
            0
        }
    }

    /**
     * Parse a double.  Tries Kotlin's built-in parser first, then falls back
     * to stripping non-numeric trailing characters.
     */
    fun parseDouble(s: String): Double {
        val t = s.trim()
        if (t.isEmpty()) return 0.0
        return try {
            t.toDouble()
        } catch (_: NumberFormatException) {
            // Some INI files have trailing units or letters
            val numeric = t.takeWhile { it.isDigit() || it == '.' || it == '-' || it == '+' || it == 'e' || it == 'E' }
            try {
                numeric.toDouble()
            } catch (_: NumberFormatException) {
                0.0
            }
        }
    }

    // ------------------------------------------------------------------
    // Brace / expression extraction
    // ------------------------------------------------------------------

    /**
     * If [s] starts with `{` and ends with `}`, return the inner text.
     * Otherwise return null.
     */
    fun extractExpression(s: String): String? {
        val t = s.trim()
        if (t.startsWith("{") && t.endsWith("}")) {
            return t.substring(1, t.length - 1).trim()
        }
        return null
    }

    /**
     * Test whether a string is an expression reference (wrapped in braces
     * but is NOT a semicolon-delimited field block).  Heuristic: if it
     * contains a semicolon *at the top level* (not nested in braces) it
     * is a field block; otherwise it's an expression.
     */
    fun isExpressionRef(s: String): Boolean {
        val inner = extractExpression(s) ?: return false
        return !inner.contains(";")
    }

    // ------------------------------------------------------------------
    // Semicolon-aware field-block splitting
    // ------------------------------------------------------------------

    /**
     * Split a field block (the text between `{` and `}`) by semicolons,
     * respecting nested braces and quoted strings.
     *
     * Example: `"u08; 1; 0x100; 0.1; 0; volts; 0; 255"`
     *          -> `["u08", "1", "0x100", "0.1", "0", "volts", "0", "255"]`
     */
    fun splitFieldBlock(block: String): List<String> {
        val result = mutableListOf<String>()
        var current = StringBuilder()
        var braceDepth = 0
        var inQuote = false
        var quoteChar = '\u0000'

        for (ch in block) {
            when {
                inQuote -> {
                    current.append(ch)
                    if (ch == quoteChar) inQuote = false
                }
                ch == '"' || ch == '\'' -> {
                    inQuote = true
                    quoteChar = ch
                    current.append(ch)
                }
                ch == '{' -> {
                    braceDepth++
                    current.append(ch)
                }
                ch == '}' -> {
                    braceDepth--
                    current.append(ch)
                }
                ch == ';' && braceDepth == 0 -> {
                    result.add(current.toString().trim())
                    current = StringBuilder()
                }
                else -> current.append(ch)
            }
        }
        val last = current.toString().trim()
        if (last.isNotEmpty() || result.isNotEmpty()) {
            result.add(last)
        }
        return result
    }

    // ------------------------------------------------------------------
    // Condition extraction
    // ------------------------------------------------------------------

    /**
     * Given the raw field string (inside the braces), check whether the
     * last element looks like a condition (e.g. `CLT < 100`).
     *
     * Conditions typically contain comparison operators and are not pure
     * numbers or simple identifiers.
     *
     * Returns a pair of (fieldWithoutCondition, conditionString?)
     */
    fun extractCondition(field: String): Pair<String, String?> {
        // Quick check: if no comparison operators, no condition
        if (!field.contains("<") && !field.contains(">") &&
            !field.contains("==") && !field.contains("!=")) {
            return Pair(field, null)
        }

        val parts = splitFieldBlock(field)
        if (parts.size < 2) return Pair(field, null)

        val lastPart = parts.last().trim()
        // A condition contains at least one comparison operator
        val hasComparison = listOf("<", ">", "==", "!=", "<=", ">=").any { op ->
            lastPart.contains(op)
        }
        // Also should not be a plain number
        val isPlainNumber = lastPart.toDoubleOrNull() != null

        return if (hasComparison && !isPlainNumber) {
            val prefix = parts.dropLast(1).joinToString("; ")
            Pair(prefix, lastPart)
        } else {
            Pair(field, null)
        }
    }

    // ------------------------------------------------------------------
    // Define resolution
    // ------------------------------------------------------------------

    /**
     * Replace `$defineName` references in [s] with the first element of the
     * corresponding define's value list.  Unknown defines are left as-is.
     */
    fun resolveDefines(s: String, defines: Map<String, List<String>>): String {
        var result = s
        // Iterate to handle chained defines (though rare)
        var changed = true
        var iterations = 0
        while (changed && iterations < 10) {
            changed = false
            iterations++
            for ((name, values) in defines) {
                if (values.isEmpty()) continue
                val token = "\$$name"
                if (result.contains(token)) {
                    result = result.replace(token, values[0])
                    changed = true
                }
            }
        }
        return result
    }

    /**
     * Replace `$defineName` and return the full list of expanded values.
     * Used for bit-field options where the define expands to a comma list.
     */
    fun resolveDefinesToList(s: String, defines: Map<String, List<String>>): List<String> {
        val resolved = resolveDefines(s, defines)
        // If the resolved string contains commas, split it
        if (resolved.contains(",")) {
            return splitByCommas(resolved)
        }
        return listOf(resolved)
    }

    // ------------------------------------------------------------------
    // Key-value splitting (single `=` at the top level)
    // ------------------------------------------------------------------

    /**
     * Split a line into (key, value) on the first `=` that is not inside
     * braces or quotes.
     *
     * Returns (line, "") if no `=` is found.
     */
    fun splitKeyValue(line: String): Pair<String, String> {
        var braceDepth = 0
        var inQuote = false
        var quoteChar = '\u0000'

        for (i in line.indices) {
            val ch = line[i]
            when {
                inQuote -> {
                    if (ch == quoteChar) inQuote = false
                }
                ch == '"' || ch == '\'' -> {
                    inQuote = true
                    quoteChar = ch
                }
                ch == '{' -> braceDepth++
                ch == '}' -> braceDepth--
                ch == '=' && braceDepth == 0 -> {
                    val key = line.substring(0, i).trim()
                    val value = line.substring(i + 1).trim()
                    return Pair(key, value)
                }
            }
        }
        return Pair(line.trim(), "")
    }

    // ------------------------------------------------------------------
    // Description extraction (text after the field block)
    // ------------------------------------------------------------------

    /**
     * Given the value portion of a key-value line (everything after `=`),
     * split into (fieldBlockOrValue, description).
     *
     * The field block is the part inside `{…}` (if present), or the whole
     * value.  The description follows after any trailing whitespace.
     */
    fun splitFieldAndDescription(value: String): Pair<String, String> {
        val t = value.trim()
        // Find the closing brace of the first top-level `{…}` block
        var braceDepth = 0
        var inQuote = false
        var quoteChar = '\u0000'
        var endOfField = -1

        for (i in t.indices) {
            val ch = t[i]
            when {
                inQuote -> {
                    if (ch == quoteChar) inQuote = false
                }
                ch == '"' || ch == '\'' -> {
                    inQuote = true
                    quoteChar = ch
                }
                ch == '{' -> braceDepth++
                ch == '}' -> {
                    braceDepth--
                    if (braceDepth == 0) {
                        endOfField = i
                        break
                    }
                }
            }
        }

        return if (endOfField >= 0 && endOfField + 1 < t.length) {
            val field = t.substring(0, endOfField + 1)
            val desc = t.substring(endOfField + 1).trim()
            Pair(field, desc)
        } else {
            Pair(t, "")
        }
    }

    // ------------------------------------------------------------------
    // Constant field parsing
    // ------------------------------------------------------------------

    /**
     * Parse a [Constants] section field block into a [Constant].
     *
     * Supported formats:
     * - `{u08; page; offset; scale; translate; units; lo; hi}`
     * - `{bits; options=$defineName}`
     * - `{array; page; offset; count}`
     * - `{2Darray; xBinName; yBinName; page; offset; rows; cols; scale; translate; units}`
     * - `{u08; page; offset; {expression}; 0; kPa; 0; 255}` (expression-based scale)
     *
     * [field] is the raw text *inside* the outer braces (or the full value
     * if no braces).  [name] is the constant name.
     *
     * Returns null if the field cannot be parsed.
     */
    fun parseConstantField(field: String, name: String, defines: Map<String, List<String>> = emptyMap()): Constant? {
        // Strip outer braces if present
        val inner = extractExpression(field) ?: field.trim()

        // Split by semicolons respecting nesting
        val (cleanField, _condition) = extractCondition(inner)
        val parts = splitFieldBlock(cleanField)
        if (parts.isEmpty()) return null

        val typeStr = parts.getOrNull(0)?.trim() ?: return null

        // ---- Bits format: {bits; options=$defineName} ----
        if (typeStr.equals("bits", ignoreCase = true)) {
            val optionsRaw = parts.getOrNull(1)?.trim() ?: ""
            var bitOptions = emptyList<String>()
            if (optionsRaw.startsWith("options=")) {
                val ref = optionsRaw.removePrefix("options=").trim()
                bitOptions = resolveDefinesToList(ref, defines)
            }
            return Constant(
                name = name,
                page = 0,
                offset = 0,
                dataType = DataType.BITS,
                bitOptions = bitOptions,
                shape = Shape.Scalar
            )
        }

        // ---- Array format: {array; page; offset; count} ----
        if (typeStr.equals("array", ignoreCase = true)) {
            val page = parseInt(parts.getOrNull(1) ?: "0")
            val offset = parseInt(parts.getOrNull(2) ?: "0")
            val count = parseInt(parts.getOrNull(3) ?: "1")
            return Constant(
                name = name,
                page = page,
                offset = offset,
                dataType = DataType.U08,
                shape = Shape.Array1D(size = count)
            )
        }

        // ---- 2D Array format: {2Darray; xBin; yBin; page; offset; rows; cols; scale; translate; units} ----
        if (typeStr.equals("2darray", ignoreCase = true)) {
            val xBinsName = parts.getOrNull(1)?.trim() ?: ""
            val yBinsName = parts.getOrNull(2)?.trim() ?: ""
            val page = parseInt(parts.getOrNull(3) ?: "0")
            val offset = parseInt(parts.getOrNull(4) ?: "0")
            val rows = parseInt(parts.getOrNull(5) ?: "0")
            val cols = parseInt(parts.getOrNull(6) ?: "0")
            val scaleStr = parts.getOrNull(7)?.trim() ?: "1"
            val translateStr = parts.getOrNull(8)?.trim() ?: "0"
            val units = parts.getOrNull(9)?.trim() ?: ""

            val scaleExpr = if (isExpressionRef(scaleStr)) extractExpression(scaleStr) else null
            val translateExpr = if (isExpressionRef(translateStr)) extractExpression(translateStr) else null

            return Constant(
                name = name,
                page = page,
                offset = offset,
                dataType = DataType.U08,
                scale = if (scaleExpr == null) parseDouble(scaleStr) else 1.0,
                translate = if (translateExpr == null) parseDouble(translateStr) else 0.0,
                units = units,
                shape = Shape.Array2D(rows = rows, cols = cols),
                scaleExpr = scaleExpr,
                translateExpr = translateExpr,
                xBinsName = xBinsName,
                yBinsName = yBinsName
            )
        }

        // ---- Standard scalar format: {dataType; page; offset; scale; translate; units; lo; hi} ----
        val dataType = parseDataType(typeStr)
        val page = parseInt(parts.getOrNull(1) ?: "0")
        val offset = parseInt(parts.getOrNull(2) ?: "0")

        val scaleStr = parts.getOrNull(3)?.trim() ?: "1"
        val translateStr = parts.getOrNull(4)?.trim() ?: "0"
        val units = parts.getOrNull(5)?.trim() ?: ""
        val loStr = parts.getOrNull(6)?.trim() ?: ""
        val hiStr = parts.getOrNull(7)?.trim() ?: ""

        val scaleExpr = if (isExpressionRef(scaleStr)) extractExpression(scaleStr) else null
        val translateExpr = if (isExpressionRef(translateStr)) extractExpression(translateStr) else null
        val minExpr = if (isExpressionRef(loStr)) extractExpression(loStr) else null
        val maxExpr = if (isExpressionRef(hiStr)) extractExpression(hiStr) else null

        val scale = if (scaleExpr == null) parseDouble(scaleStr) else 1.0
        val translate = if (translateExpr == null) parseDouble(translateStr) else 0.0
        var min = if (minExpr == null && loStr.isNotEmpty()) parseDouble(loStr) else 0.0
        var max = if (maxExpr == null && hiStr.isNotEmpty()) parseDouble(hiStr) else 255.0

        // Set range based on data type byte limits
        if (loStr.isEmpty() || minExpr != null) {
            min = 0.0
        }
        if (hiStr.isEmpty() || maxExpr != null) {
            max = when (dataType) {
                DataType.U08, DataType.S08, DataType.BITS -> 255.0
                DataType.U16, DataType.S16 -> 65535.0
                DataType.U32, DataType.S32, DataType.F32 -> 4294967295.0
                DataType.STRING -> 0.0
            }
        }

        return Constant(
            name = name,
            page = page,
            offset = offset,
            dataType = dataType,
            scale = scale,
            translate = translate,
            min = min,
            max = max,
            units = units,
            shape = Shape.Scalar,
            scaleExpr = scaleExpr,
            translateExpr = translateExpr,
            minExpr = minExpr,
            maxExpr = maxExpr,
            rangeResolved = (minExpr == null && maxExpr == null)
        )
    }

    // ------------------------------------------------------------------
    // Output channel field parsing
    // ------------------------------------------------------------------

    /**
     * Parse an [OutputChannels] section field block.
     *
     * Formats:
     * - `{offset; dataType; scale; translate; units; lo; hi}`
     * - `{expression; "((rpm > 0) ? calc : 0)"; units; lo; hi}`
     */
    fun parseOutputChannelField(field: String, name: String): OutputChannel? {
        val inner = extractExpression(field) ?: field.trim()
        val parts = splitFieldBlock(inner)
        if (parts.isEmpty()) return null

        val typeStr = parts[0].trim()

        // Expression channel: {expression; "..."; units; lo; hi}
        if (typeStr.equals("expression", ignoreCase = true)) {
            val exprRaw = parts.getOrNull(1)?.trim() ?: ""
            // Strip surrounding quotes
            val expression = exprRaw.removeSurrounding("\"").removeSurrounding("'")
            val units = parts.getOrNull(2)?.trim()?.removeSurrounding("\"")
                ?.removeSurrounding("'") ?: ""
            val lo = parts.getOrNull(3)?.let { parseDouble(it) } ?: Double.MIN_VALUE
            val hi = parts.getOrNull(4)?.let { parseDouble(it) } ?: Double.MAX_VALUE
            return OutputChannel(
                name = name,
                offset = 0,
                dataType = DataType.F32,
                units = units,
                expression = expression,
                minValue = lo,
                maxValue = hi
            )
        }

        // Standard channel: {offset; dataType; scale; translate; units; lo; hi}
        val offset = parseInt(parts.getOrNull(0) ?: "0")
        val dataType = parseDataType(parts.getOrNull(1) ?: "u08")
        val scaleStr = parts.getOrNull(2)?.trim() ?: "1"
        val translateStr = parts.getOrNull(3)?.trim() ?: "0"
        val units = parts.getOrNull(4)?.trim()?.removeSurrounding("\"")
            ?.removeSurrounding("'") ?: ""
        val lo = parts.getOrNull(5)?.let { parseDouble(it) } ?: Double.MIN_VALUE
        val hi = parts.getOrNull(6)?.let { parseDouble(it) } ?: Double.MAX_VALUE

        return OutputChannel(
            name = name,
            offset = offset,
            dataType = dataType,
            scale = parseDouble(scaleStr),
            translate = parseDouble(translateStr),
            units = units,
            minValue = lo,
            maxValue = hi
        )
    }

    // ------------------------------------------------------------------
    // Table editor field parsing
    // ------------------------------------------------------------------

    /**
     * Parse a [TableEditor] field block.
     *
     * Format: `{tableType; xBins; yBins; page; xBinOffset; yBinOffset; valuesOffset; rows; cols; scale; translate; units}`
     *
     * For 2D tables, yBins/yBinOffset may be empty.
     */
    fun parseTableEditorField(field: String, name: String): TableDefinition? {
        val inner = extractExpression(field) ?: field.trim()
        val parts = splitFieldBlock(inner)
        if (parts.isEmpty()) return null

        val tableTypeStr = parts.getOrNull(0)?.trim() ?: "3D"
        val tableType = parseTableType(tableTypeStr)

        val xBinsName = parts.getOrNull(1)?.trim() ?: ""
        val yBinsName = parts.getOrNull(2)?.trim() ?: ""

        val page = parseInt(parts.getOrNull(3) ?: "0")
        val xBinOffset = parseInt(parts.getOrNull(4) ?: "0")
        val yBinOffset = parseInt(parts.getOrNull(5) ?: "0")
        val valuesOffset = parseInt(parts.getOrNull(6) ?: "0")

        val rows = parseInt(parts.getOrNull(7) ?: "0")
        val cols = parseInt(parts.getOrNull(8) ?: "0")

        val scaleStr = parts.getOrNull(9)?.trim() ?: "1"
        val translateStr = parts.getOrNull(10)?.trim() ?: "0"
        val units = parts.getOrNull(11)?.trim()?.removeSurrounding("\"")
            ?.removeSurrounding("'") ?: ""

        val scale = parseDouble(scaleStr)
        val translate = parseDouble(translateStr)

        // Determine if 2D (yBins missing or tableType is 2D)
        val is2D = tableType == TableType.TABLE_2D || tableType == TableType.TABLE_1D ||
                yBinsName.isEmpty()

        val xAxis = if (xBinsName.isNotEmpty()) {
            TableAxis(
                binsName = xBinsName,
                binsOffset = xBinOffset,
                binsPage = page
            )
        } else null

        val yAxis = if (!is2D && yBinsName.isNotEmpty()) {
            TableAxis(
                binsName = yBinsName,
                binsOffset = yBinOffset,
                binsPage = page
            )
        } else null

        // For 2D tables, cols is the size; rows is 1
        val effectiveRows = if (is2D) 1 else rows
        val effectiveCols = if (is2D && cols == 0) rows else cols

        return TableDefinition(
            name = name,
            tableType = tableType,
            page = page,
            xAxis = xAxis,
            yAxis = yAxis,
            valuesOffset = valuesOffset,
            valuesPage = page,
            scale = scale,
            translate = translate,
            units = units,
            rows = effectiveRows,
            cols = effectiveCols
        )
    }

    // ------------------------------------------------------------------
    // Gauge configuration parsing
    // ------------------------------------------------------------------

    /**
     * Parse a gauge configuration line.
     *
     * Format: `gaugeName = channelName, gaugeType, min, max, lowWarn, highWarn, lowDanger, highDanger, title, units, decimals, vd`
     */
    fun parseGaugeLine(line: String): GaugeConfig? {
        val (name, value) = splitKeyValue(line)
        if (name.isEmpty() || value.isEmpty()) return null

        val parts = splitByCommas(value)
        if (parts.isEmpty()) return null

        val channelName = parts.getOrNull(0)?.trim() ?: ""
        val gaugeTypeStr = parts.getOrNull(1)?.trim()?.lowercase() ?: "analog"
        val gaugeType = when (gaugeTypeStr) {
            "digital" -> GaugeType.DIGITAL
            "bar" -> GaugeType.BAR
            "wideband" -> GaugeType.WIDEBAND
            "empty" -> GaugeType.EMPTY
            else -> GaugeType.ANALOG
        }
        val minVal = parts.getOrNull(2)?.let { parseDouble(it) } ?: 0.0
        val maxVal = parts.getOrNull(3)?.let { parseDouble(it) } ?: 100.0
        val lowWarn = parts.getOrNull(4)?.let { parseDouble(it) }.takeIf { it != null && !it.isNaN() } ?: Double.NaN
        val highWarn = parts.getOrNull(5)?.let { parseDouble(it) }.takeIf { it != null && !it.isNaN() } ?: Double.NaN
        val lowDanger = parts.getOrNull(6)?.let { parseDouble(it) }.takeIf { it != null && !it.isNaN() } ?: Double.NaN
        val highDanger = parts.getOrNull(7)?.let { parseDouble(it) }.takeIf { it != null && !it.isNaN() } ?: Double.NaN
        val title = parts.getOrNull(8)?.trim()?.removeSurrounding("\"")
            ?.removeSurrounding("'") ?: ""
        val units = parts.getOrNull(9)?.trim()?.removeSurrounding("\"")
            ?.removeSurrounding("'") ?: ""
        val decimals = parts.getOrNull(10)?.let { parseInt(it) } ?: 1
        val vd = parts.getOrNull(11)?.let { parseInt(it) } ?: 0

        return GaugeConfig(
            name = name,
            channelName = channelName,
            gaugeType = gaugeType,
            minVal = minVal,
            maxVal = maxVal,
            lowWarning = lowWarn,
            highWarning = highWarn,
            lowDanger = lowDanger,
            highDanger = highDanger,
            title = title,
            units = units,
            decimals = decimals,
            vd = vd
        )
    }

    // ------------------------------------------------------------------
    // Internal helpers
    // ------------------------------------------------------------------

    /**
     * Split by commas, respecting quotes and nested braces.
     */
    internal fun splitByCommas(value: String): List<String> {
        val result = mutableListOf<String>()
        var current = StringBuilder()
        var braceDepth = 0
        var inQuote = false
        var quoteChar = '\u0000'

        for (ch in value) {
            when {
                inQuote -> {
                    current.append(ch)
                    if (ch == quoteChar) inQuote = false
                }
                ch == '"' || ch == '\'' -> {
                    inQuote = true
                    quoteChar = ch
                    current.append(ch)
                }
                ch == '{' -> {
                    braceDepth++
                    current.append(ch)
                }
                ch == '}' -> {
                    braceDepth--
                    current.append(ch)
                }
                ch == ',' && braceDepth == 0 -> {
                    result.add(current.toString().trim())
                    current = StringBuilder()
                }
                else -> current.append(ch)
            }
        }
        result.add(current.toString().trim())
        return result
    }
}
