package com.ztune.libretune.core.ini

import com.ztune.libretune.core.ini.types.*
import java.security.MessageDigest

// ============================================================================
// File-level helper functions
// ============================================================================

/**
 * Split an INI value line by commas, respecting quoted strings and nested braces.
 * Leading/trailing whitespace on each token is stripped.
 */
fun splitIniLine(value: String): List<String> {
    val result = mutableListOf<String>()
    var current = StringBuilder()
    var inQuote = false
    var quoteChar = '\u0000'
    var braceDepth = 0
    var i = 0

    while (i < value.length) {
        val ch = value[i]
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
            else -> {
                current.append(ch)
            }
        }
        i++
    }
    // Add last token
    val last = current.toString().trim()
    if (last.isNotEmpty() || result.isNotEmpty()) {
        result.add(last)
    }
    return result
}

/**
 * Split [name] into (prefix, digitRun, suffix) if it contains a run of
 * consecutive ASCII digits that is preceded and followed by non-digit text.
 * Returns `null` if no such digit run exists.
 *
 * Example: `"veTable1Tbl"` -> `("veTable", "1", "Tbl")`
 */
private fun splitDigitRun(name: String): Triple<String, String, String>? {
    var first = -1
    var last = -1

    for ((idx, ch) in name.withIndex()) {
        if (ch in '0'..'9') {
            if (first == -1) first = idx
            last = idx
        } else {
            // Found a non-digit after the run ended
            if (first != -1) break
        }
    }

    if (first == -1) return null
    // Must have non-digit text on both sides of the digit run
    if (first == 0 || last == name.length - 1) return null

    return Triple(
        name.substring(0, first),
        name.substring(first, last + 1),
        name.substring(last + 1)
    )
}

/**
 * Returns true when [labeled] and [candidate] are "digit siblings":
 * they share the same prefix and suffix but differ in their digit run
 * (e.g. `veTable1Tbl` and `veTable2Tbl`).
 */
private fun isDigitSibling(labeled: String, candidate: String): Boolean {
    val a = splitDigitRun(labeled) ?: return false
    val b = splitDigitRun(candidate) ?: return false
    return a.first == b.first && a.third == b.third
}

// ============================================================================
// EcuDefinition
// ============================================================================

/**
 * The complete, mutable INI definition for an ECU firmware.
 * Converted from LibreTune's Rust `EcuDefinition` struct and its `impl` block.
 *
 * Every map / list field is mutable because the INI parser populates them
 * incrementally as sections are encountered.
 */
class EcuDefinition(
    // ---- identity / protocol ----
    var ecuType: EcuType = EcuType.UNKNOWN,
    var signature: String = "",
    var signaturePrefix: String? = null,
    var queryCommand: String = "Q",
    var versionInfo: String = "",
    var iniSpecVersion: String = "3.64",

    // ---- preprocessor ----
    /** `#define` macros: macro name -> parameter list (empty list = object-like). */
    val defines: MutableMap<String, List<String>> = mutableMapOf(),
    /** Preprocessor symbols active at parse time. */
    val activeSymbols: MutableSet<String> = mutableSetOf(),
    /** All symbols that were tested with `#if` / `#ifdef`. */
    val testedSymbols: MutableSet<String> = mutableSetOf(),

    // ---- memory layout ----
    var endianness: Endianness = Endianness.DEFAULT,
    /** Per-page flash/RAM sizes in bytes (u16 in Rust -> Short in Kotlin). */
    var pageSizes: List<Short> = emptyList(),
    /** Number of pages (u8 in Rust -> Byte in Kotlin). */
    var nPages: Byte = 0,
    var protocol: ProtocolSettings = ProtocolSettings(),

    // ---- data sections ----
    val constants: MutableMap<String, Constant> = mutableMapOf(),
    val outputChannels: MutableMap<String, OutputChannel> = mutableMapOf(),
    val tables: MutableMap<String, TableDefinition> = mutableMapOf(),
    /** map_name -> table name. */
    val tableMapToName: MutableMap<String, String> = mutableMapOf(),
    val curves: MutableMap<String, CurveDefinition> = mutableMapOf(),
    /** map_name -> curve name. */
    val curveMapToName: MutableMap<String, String> = mutableMapOf(),
    val gauges: MutableMap<String, GaugeConfig> = mutableMapOf(),
    val settingGroups: MutableMap<String, SettingGroup> = mutableMapOf(),
    val menus: MutableList<Menu> = mutableListOf(),
    val dialogs: MutableMap<String, DialogDefinition> = mutableMapOf(),
    val helpTopics: MutableMap<String, HelpTopic> = mutableMapOf(),
    val datalogEntries: MutableList<DatalogEntry> = mutableListOf(),
    /** PC-only variable name -> value byte. */
    val pcVariables: MutableMap<String, Byte> = mutableMapOf(),
    val defaultValues: MutableMap<String, Double> = mutableMapOf(),
    val maximumElements: MutableMap<String, Int> = mutableMapOf(),

    // ---- UI / panels ----
    var frontpage: FrontPageConfig? = null,
    val indicatorPanels: MutableMap<String, IndicatorPanel> = mutableMapOf(),
    val readoutPanels: MutableMap<String, ReadoutPanel> = mutableMapOf(),

    // ---- commands & loggers ----
    val controllerCommands: MutableMap<String, ControllerCommand> = mutableMapOf(),
    val loggerDefinitions: MutableMap<String, LoggerDefinition> = mutableMapOf(),
    val diagnosticLoggers: MutableList<DiagnosticLogger> = mutableListOf(),
    val portEditors: MutableMap<String, PortEditorConfig> = mutableMapOf(),

    // ---- reference / calibration ----
    val referenceTables: MutableMap<String, ReferenceTable> = mutableMapOf(),

    // ---- table write helpers ----
    var tableWriteCommand: String? = null,
    var tableBlockingFactor: Int? = null,

    // ---- FTP / datalog views / key actions ----
    val ftpBrowsers: MutableMap<String, FTPBrowserConfig> = mutableMapOf(),
    val datalogViews: MutableMap<String, DatalogView> = mutableMapOf(),
    val keyActions: MutableList<KeyAction> = mutableListOf(),

    // ---- analysis configs ----
    var veAnalyze: VeAnalyzeConfig? = null,
    var wueAnalyze: WueAnalyzeConfig? = null,
    var gammaE: GammaEConfig? = null,

    // ---- maintenance ----
    val maintainConstantValues: MutableList<MaintainConstantValue> = mutableListOf(),
    val requiresPowerCycle: MutableList<String> = mutableListOf()
) {

    // ------------------------------------------------------------------
    // 1. Simple accessors
    // ------------------------------------------------------------------

    /** Check if [symbol] is in the set of active preprocessor symbols. */
    fun symbolIsActive(symbol: String): Boolean = symbol in activeSymbols

    /** Check if [symbol] was ever tested by `#if` / `#ifdef`. */
    fun testsSymbol(symbol: String): Boolean = symbol in testedSymbols

    /** Look up a constant by name, or null if not defined. */
    fun getConstant(name: String): Constant? = constants[name]

    /** Look up an output channel by name, or null if not defined. */
    fun getOutputChannel(name: String): OutputChannel? = outputChannels[name]

    /** Look up a table by its primary name, or null. */
    fun getTable(name: String): TableDefinition? = tables[name]

    /**
     * Try a direct table lookup by [nameOrMap]; if that fails, try interpreting it
     * as a `map_name` via [tableMapToName]. Returns null if neither resolves.
     */
    fun getTableByNameOrMap(nameOrMap: String): TableDefinition? {
        tables[nameOrMap]?.let { return it }
        val mapped = tableMapToName[nameOrMap] ?: return null
        return tables[mapped]
    }

    /**
     * Same pattern as [getTableByNameOrMap] but for curves.
     */
    fun getCurveByNameOrMap(nameOrMap: String): CurveDefinition? {
        curves[nameOrMap]?.let { return it }
        val mapped = curveMapToName[nameOrMap] ?: return null
        return curves[mapped]
    }

    /** Sum of all page sizes (total addressable memory). */
    fun totalMemorySize(): Int = pageSizes.sumOf { it.toInt() and 0xFFFF }

    // ------------------------------------------------------------------
    // 9. Structural hash
    // ------------------------------------------------------------------

    /**
     * Produce a deterministic MD5 hex digest based on fields that define the
     * *structure* of this INI definition: the signature, page count / sizes,
     * and every constant's name, data-type, offset, and scale.
     *
     * This is useful for cache invalidation – if the structural hash changes
     * the UI must rebuild its layout.
     */
    fun computeStructuralHash(): String {
        val md = MessageDigest.getInstance("MD5")
        val buf = StringBuilder()

        buf.appendLine(signature)
        buf.appendLine(nPages)
        for (ps in pageSizes) {
            buf.appendLine(ps.toInt())
        }

        // Iterate constants in sorted order for determinism
        for ((name, c) in constants.toSortedMap()) {
            buf.appendLine(name)
            buf.appendLine(c.dataType.name)
            buf.appendLine(c.offset)
            buf.appendLine(c.scale)
        }

        val bytes = md.digest(buf.toString().toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    // ------------------------------------------------------------------
    // 10. Capabilities
    // ------------------------------------------------------------------

    /**
     * Return an [IniCapabilities] snapshot indicating which INI sections
     * were populated, so the UI can gate features accordingly.
     */
    fun capabilities(): IniCapabilities {
        // Detect DFU / BLT / Lua by scanning controller commands and pc variables
        val dfuName = controllerCommands.entries
            .firstOrNull { (k, _) -> k.contains("dfu", ignoreCase = true) }
            ?.key
        val bltName = controllerCommands.entries
            .firstOrNull { (k, _) ->
                k.contains("blt", ignoreCase = true) ||
                k.contains("bootloader", ignoreCase = true)
            }
            ?.key
        val luaVar = pcVariables.keys
            .firstOrNull { it.contains("luaScript", ignoreCase = true) }

        return IniCapabilities(
            hasConstants = constants.isNotEmpty(),
            hasOutputChannels = outputChannels.isNotEmpty(),
            hasTables = tables.isNotEmpty(),
            hasCurves = curves.isNotEmpty(),
            hasGauges = gauges.isNotEmpty(),
            hasFrontpage = frontpage != null,
            hasDialogs = dialogs.isNotEmpty(),
            hasHelpTopics = helpTopics.isNotEmpty(),
            hasSettingGroups = settingGroups.isNotEmpty(),
            hasPcVariables = pcVariables.isNotEmpty(),
            hasDefaultValues = defaultValues.isNotEmpty(),
            hasDatalogEntries = datalogEntries.isNotEmpty(),
            hasDatalogViews = datalogViews.isNotEmpty(),
            hasLoggerDefinitions = loggerDefinitions.isNotEmpty(),
            hasControllerCommands = controllerCommands.isNotEmpty(),
            hasPortEditors = portEditors.isNotEmpty(),
            hasReferenceTables = referenceTables.isNotEmpty(),
            hasKeyActions = keyActions.isNotEmpty(),
            hasVeAnalyze = veAnalyze != null,
            hasWueAnalyze = wueAnalyze != null,
            hasGammaE = gammaE != null,
            supportsConsole = ecuType.supportsConsole(),
            dfuCommandName = dfuName,
            openBltCommandName = bltName,
            luaScriptConstant = luaVar
        )
    }

    // ------------------------------------------------------------------
    // 11. Table role inference
    // ------------------------------------------------------------------

    /**
     * Assign a semantic [TableRole] to every table in [tables] using:
     * 1. Explicit analysis-config references ([veAnalyze], [wueAnalyze]).
     * 2. Name-based heuristics (e.g. "ign" / "spark" / "advance" -> [TableRole.IGNITION]).
     * 3. Digit-sibling propagation: if `veTable1Tbl` is tagged VE, then `veTable2Tbl` is too.
     */
    fun inferTableRoles() {
        val assigned = mutableMapOf<String, TableRole>()

        // ---- 1. Explicit references from analysis configs ----

        // VE table(s)
        if (veAnalyze != null) {
            val veName = veAnalyze!!.veTableName
            if (veName.isNotEmpty() && veName in tables) {
                assigned[veName] = TableRole.VE
            }
            // Lambda target tables -> AFR_TARGET
            for (tName in veAnalyze!!.lambdaTargetTables) {
                if (tName in tables) {
                    assigned[tName] = TableRole.AFR_TARGET
                }
            }
        }

        // WUE – the analyze config references a *curve*, but the target table
        // (if it exists in the tables map) can be tagged.
        if (wueAnalyze != null) {
            val target = wueAnalyze!!.targetTableName
            if (target.isNotEmpty() && target in tables && target !in assigned) {
                // Target table of WUE analysis exists but we don't assign a special
                // role here – it will get one from name heuristics if applicable.
            }
            for (tName in wueAnalyze!!.lambdaTargetTables) {
                if (tName in tables && tName !in assigned) {
                    assigned[tName] = TableRole.AFR_TARGET
                }
            }
        }

        // GammaE referenced table
        if (gammaE != null) {
            val gTable = gammaE!!.table
            if (gTable.isNotEmpty() && gTable in tables && gTable !in assigned) {
                // GammaE overlay is typically VE-related; leave for name heuristic
            }
        }

        // ---- 2. Name-based heuristics for unassigned tables ----
        for ((tName, _) in tables) {
            if (tName in assigned) continue
            val ln = tName.lowercase()
            when {
                ln.contains("ign") || ln.contains("spark") || ln.contains("advance") ->
                    assigned[tName] = TableRole.IGNITION
                ln.contains("afrtarget") || ln.contains("afr_target") || ln.contains("tgt") ->
                    assigned[tName] = TableRole.AFR_TARGET
                ln.contains("wue") || ln.contains("warmup") || ln.contains("enrich") ->
                    assigned[tName] = TableRole.WARMUP_ENRICHMENT
                ln.contains("ve") && (ln.contains("table") || ln.contains("map")) ->
                    assigned[tName] = TableRole.VE
            }
        }

        // ---- 3. Digit-sibling propagation ----
        // For every table that just received a role, propagate to siblings.
        val snapshot = assigned.toMap() // iterate over a stable copy
        for ((labeled, role) in snapshot) {
            for (candidate in tables.keys) {
                if (candidate == labeled) continue
                if (candidate in assigned) continue
                if (isDigitSibling(labeled, candidate)) {
                    assigned[candidate] = role
                }
            }
        }

        // ---- Apply ----
        for ((tName, tDef) in tables) {
            tDef.role = assigned[tName] ?: TableRole.OTHER
        }
    }

    // ------------------------------------------------------------------
    // 12. Standard panel synthesis
    // ------------------------------------------------------------------

    /**
     * Synthesize a built-in TunerStudio-standard dialog panel.
     *
     * Supported names:
     * - `"std_injection"` – Injection settings (algorithm, reqFuel, nCylinders,
     *   twoStroke, injTiming, injPwm).
     * - `"std_ms3Rtc"` – MS3 real-time clock (rtc_trim).
     *
     * Returns `null` for any unrecognised panel name.
     */
    fun stdPanelDefinition(name: String): DialogDefinition? = when (name) {
        "std_injection" -> DialogDefinition(
            name = "std_injection",
            title = "Injection Settings",
            components = listOf(
                DialogComponent(
                    type = DialogComponentType.FIELD,
                    label = "Algorithm",
                    name = "algorithm"
                ),
                DialogComponent(
                    type = DialogComponentType.FIELD,
                    label = "Required Fuel (cc/min)",
                    name = "reqFuel"
                ),
                DialogComponent(
                    type = DialogComponentType.FIELD,
                    label = "Number of Cylinders",
                    name = "nCylinders"
                ),
                DialogComponent(
                    type = DialogComponentType.FIELD,
                    label = "Two Stroke",
                    name = "twoStroke"
                ),
                DialogComponent(
                    type = DialogComponentType.FIELD,
                    label = "Injection Timing",
                    name = "injTiming"
                ),
                DialogComponent(
                    type = DialogComponentType.FIELD,
                    label = "Injection PWM",
                    name = "injPwm"
                )
            )
        )
        "std_ms3Rtc" -> DialogDefinition(
            name = "std_ms3Rtc",
            title = "MS3 Real-Time Clock",
            components = listOf(
                DialogComponent(
                    type = DialogComponentType.FIELD,
                    label = "RTC Trim",
                    name = "rtc_trim"
                )
            )
        )
        else -> null
    }

    // ------------------------------------------------------------------
    // Companion object – factory
    // ------------------------------------------------------------------

    companion object {
        /**
         * Return an [EcuDefinition] with every field at its default value,
         * matching the Rust `Default` implementation.
         */
        fun default(): EcuDefinition = EcuDefinition()
    }
}
