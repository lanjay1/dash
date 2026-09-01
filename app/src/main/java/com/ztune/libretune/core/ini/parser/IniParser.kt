package com.ztune.libretune.core.ini.parser

import android.content.res.AssetManager
import com.ztune.libretune.core.ini.*
import com.ztune.libretune.core.ini.types.*

/**
 * Exception thrown when INI parsing fails.
 */
class IniParseException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Main INI parser that converts a stream of [IniToken]s into an [EcuDefinition].
 *
 * This is a port of LibreTune's Rust `ini/parser.rs` module.
 *
 * The parser maintains a preprocessor stack for `#if`/`#else`/`#endif` blocks,
 * a symbol table for `#define` macros, and processes each section according to
 * the TunerStudio INI specification.
 */
class IniParser {

    // ------------------------------------------------------------------
    // Preprocessor state
    // ------------------------------------------------------------------

    /** Symbols the user has activated before parsing (e.g. from firmware detection). */
    private val activeSymbols = mutableSetOf<String>()

    /** All symbols tested via `#if` — used to populate `EcuDefinition.testedSymbols`. */
    private val testedSymbols = mutableSetOf<String>()

    /** Macro definitions: name -> list of value tokens. */
    private val defines = mutableMapOf<String, List<String>>()

    /**
     * Preprocessor conditional stack.  Each entry is (symbol, branchTaken).
     * When the stack is non-empty and any entry has `!branchTaken`, we are
     * in a "skipping" region.
     */
    private val ppStack = mutableListOf<Pair<String, Boolean>>()

    /** Include nesting depth to prevent infinite recursion. */
    private var includeDepth = 0

    companion object {
        /** Preprocessor symbols that the user can pre-set. */
        var defaultSymbols = setOf<String>()
        private const val MAX_INCLUDE_DEPTH = 10
    }

    // ------------------------------------------------------------------
    // Section-local accumulator state
    // ------------------------------------------------------------------

    /**
 * Mutable accumulator for section-local parsing state.
 * Reused across section transitions (flushed at each section boundary).
 */
    private inner class SectionState {
        var currentSection = ""
        val pendingMenuItems = mutableListOf<Menu>()
        var currentDialogName = ""
        val currentDialogComponents = mutableListOf<DialogComponent>()
        var currentSettingGroupName = ""
        var currentSettingConstants = mutableListOf<String>()
        var currentIndicatorName = ""
        val currentIndicatorConfigs = mutableListOf<IndicatorConfig>()
        var currentReadoutName = ""
        val currentReadoutConfigs = mutableListOf<ReadoutConfig>()
        var currentLoggerName = ""
        val currentLoggerChannels = mutableListOf<LoggerChannel>()
        var currentHelpTopicName = ""
        val currentHelpText = StringBuilder()
        var currentDatalogViewName = ""
        val currentDatalogViewEntries = mutableListOf<String>()

        /** Flush all accumulators into [def]. */
        fun flush(def: EcuDefinition) {
            if (pendingMenuItems.isNotEmpty()) {
                def.menus.addAll(pendingMenuItems)
                pendingMenuItems.clear()
            }
            if (currentDialogName.isNotEmpty() && currentDialogComponents.isNotEmpty()) {
                def.dialogs[currentDialogName] = DialogDefinition(
                    name = currentDialogName,
                    components = currentDialogComponents.toList()
                )
                currentDialogName = ""
                currentDialogComponents.clear()
            }
            if (currentSettingGroupName.isNotEmpty()) {
                def.settingGroups[currentSettingGroupName] = SettingGroup(
                    name = currentSettingGroupName,
                    constants = currentSettingConstants.toList()
                )
                currentSettingGroupName = ""
                currentSettingConstants.clear()
            }
            if (currentIndicatorName.isNotEmpty() && currentIndicatorConfigs.isNotEmpty()) {
                def.indicatorPanels[currentIndicatorName] = IndicatorPanel(
                    name = currentIndicatorName,
                    indicators = currentIndicatorConfigs.toList()
                )
                currentIndicatorName = ""
                currentIndicatorConfigs.clear()
            }
            if (currentReadoutName.isNotEmpty() && currentReadoutConfigs.isNotEmpty()) {
                def.readoutPanels[currentReadoutName] = ReadoutPanel(
                    name = currentReadoutName,
                    readouts = currentReadoutConfigs.toList()
                )
                currentReadoutName = ""
                currentReadoutConfigs.clear()
            }
            if (currentLoggerName.isNotEmpty() && currentLoggerChannels.isNotEmpty()) {
                def.diagnosticLoggers.add(DiagnosticLogger(
                    name = currentLoggerName,
                    channels = currentLoggerChannels.toList()
                ))
                currentLoggerName = ""
                currentLoggerChannels.clear()
            }
            if (currentHelpTopicName.isNotEmpty()) {
                def.helpTopics[currentHelpTopicName] = HelpTopic(text = currentHelpText.toString())
                currentHelpTopicName = ""
                currentHelpText.clear()
            }
            if (currentDatalogViewName.isNotEmpty() && currentDatalogViewEntries.isNotEmpty()) {
                def.datalogViews[currentDatalogViewName] = DatalogView(
                    name = currentDatalogViewName,
                    entries = currentDatalogViewEntries.toList()
                )
                currentDatalogViewName = ""
                currentDatalogViewEntries.clear()
            }
        }

        /** Reset all accumulators for a new section. */
        fun reset() {
            pendingMenuItems.clear()
            currentDialogName = ""
            currentDialogComponents.clear()
            currentSettingGroupName = ""
            currentSettingConstants.clear()
            currentIndicatorName = ""
            currentIndicatorConfigs.clear()
            currentReadoutName = ""
            currentReadoutConfigs.clear()
            currentLoggerName = ""
            currentLoggerChannels.clear()
            currentHelpTopicName = ""
            currentHelpText.clear()
            currentDatalogViewName = ""
            currentDatalogViewEntries.clear()
        }
    }

    // ------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------

    /**
     * Parse the full text of an INI file into an [EcuDefinition].
     *
     * @param content The complete INI file text.
     * @return [Result.success] with the populated definition, or
     *         [Result.failure] with a descriptive exception.
     */
    fun parse(content: String): Result<EcuDefinition> {
        return try {
            val def = EcuDefinition()
            activeSymbols.clear()
            activeSymbols.addAll(defaultSymbols)
            testedSymbols.clear()
            defines.clear()
            ppStack.clear()
            includeDepth = 0

            val tokenizer = IniTokenizer()
            val tokens = tokenizer.tokenize(content)
            processTokens(tokens, def)

            // Copy preprocessor state into the definition
            def.activeSymbols.addAll(activeSymbols)
            def.testedSymbols.addAll(testedSymbols)
            def.defines.putAll(defines)

            // Infer table roles for the UI
            def.inferTableRoles()

            Result.success(def)
        } catch (e: Exception) {
            Result.failure(IniParseException("INI parse error: ${e.message}", e))
        }
    }

    /**
     * Parse an INI file from the Android assets directory.
     *
     * @param assetsPath Path relative to the assets root (e.g. "tuning/myEcu.ini").
     * @param assetManager Android AssetManager for reading the file.
     * @return [Result.success] with the populated definition, or [Result.failure].
     */
    fun parseFromFile(assetsPath: String, assetManager: AssetManager): Result<EcuDefinition> {
        return try {
            val content = assetManager.open(assetsPath).bufferedReader().use { it.readText() }
            parse(content)
        } catch (e: Exception) {
            Result.failure(IniParseException("Failed to read INI file '$assetsPath': ${e.message}", e))
        }
    }

    // ------------------------------------------------------------------
    // Token processing loop
    // ------------------------------------------------------------------

    /**
     * Walk the token list, dispatching to section-specific handlers.
     */
    private fun processTokens(tokens: List<IniToken>, def: EcuDefinition) {
        val st = SectionState()

        for (token in tokens) {
            // ---- Preprocessor directives (always processed regardless of section) ----
            when (token.type) {
                TokenType.DIRECTIVE_IF -> {
                    handleIf(token)
                    continue
                }
                TokenType.DIRECTIVE_ELSE -> {
                    handleElse()
                    continue
                }
                TokenType.DIRECTIVE_ENDIF -> {
                    handleEndif()
                    continue
                }
                TokenType.DIRECTIVE_DEFINE -> {
                    if (isSkipping()) continue
                    handleDefine(token)
                    continue
                }
                TokenType.DIRECTIVE_INCLUDE -> {
                    if (isSkipping()) continue
                    // #include resolution is handled before parsing in production;
                    // the parser itself just records the token.
                    continue
                }
                else -> {}
            }

            // If we're in a skipped preprocessor block, skip everything else
            if (isSkipping()) continue

            when (token.type) {
                TokenType.BLANK, TokenType.COMMENT -> { /* skip */ }

                TokenType.SECTION -> {
                    st.flush(def)
                    st.reset()
                    st.currentSection = token.content.trim()
                        .removePrefix("[").removeSuffix("]").trim()
                }

                TokenType.KEY_VALUE, TokenType.CONTINUATION -> {
                    val key = token.key
                    val value = token.value

                    when (st.currentSection) {
                        // ---- Identity / protocol ----
                        "MegaTune" -> parseMegaTune(key, value, def)

                        // ---- Data sections ----
                        "Constants" -> parseConstant(key, value, def)
                        "OutputChannels" -> parseOutputChannel(key, value, def)
                        "TableEditor", "Table3D", "Table2D" ->
                            parseTableEditor(key, value, st.currentSection, def)
                        "CurveEditor", "Curve2D" -> parseCurveEditor(key, value, def)

                        // ---- UI sections ----
                        "GaugeConfigurations", "GaugeConfig" -> parseGauge(key, value, def)
                        "Menu" -> parseMenu(key, value, st)
                        "Dialog" -> parseDialog(key, value, st)
                        "SettingGroups" -> parseSettingGroup(key, value, st, def)

                        // ---- Help ----
                        "HelpTopic" -> parseHelpTopic(key, value, st)

                        // ---- Datalog ----
                        "Datalog" -> parseDatalog(key, value, def)
                        "DatalogViews" -> parseDatalogView(key, value, st, def)

                        // ---- Panels ----
                        "FrontPage" -> parseFrontPage(key, value, def)
                        "IndicatorPanel" -> parseIndicatorPanel(key, value, st)
                        "readoutPanel" -> parseReadoutPanel(key, value, st)

                        // ---- Commands & loggers ----
                        "ControllerCommands" -> parseControllerCommand(key, value, def)
                        "LoggerDefinitions" -> parseLoggerDefinition(key, value, def)
                        "DiagnosticLoggers" -> parseDiagnosticLogger(key, value, st, def)

                        // ---- Reference tables ----
                        "ReferenceTables" -> parseReferenceTable(key, value, def)

                        // ---- Port editors ----
                        "PortEditor" -> parsePortEditor(key, value, def)

                        // ---- FTP browser ----
                        "FTPBrowser" -> parseFtpBrowser(key, value, def)

                        // ---- Key actions ----
                        "KeyActions" -> parseKeyAction(key, value, def)

                        // ---- Analysis configs ----
                        "VeAnalyze" -> parseVeAnalyze(key, value, def)
                        "WueAnalyze" -> parseWueAnalyze(key, value, def)
                        "GammaE" -> parseGammaE(key, value, def)

                        // ---- Constants extensions ----
                        "ConstantsExtensions" -> parseConstantsExtensions(key, value, def)

                        // ---- Table write helpers ----
                        "TableWriteCommand" -> def.tableWriteCommand = value
                        "tableBlockingFactor" -> def.tableBlockingFactor = FieldParser.parseInt(value)

                        // ---- Default / PC variables ----
                        "PCVariable" -> {
                            val v = FieldParser.parseInt(value)
                            def.pcVariables[key] = v.toByte()
                        }
                        "defaultValue" -> {
                            def.defaultValues[key] = FieldParser.parseDouble(value)
                        }

                        else -> {
                            // Unknown section — ignore silently
                        }
                    }
                }

                else -> { /* CONTINUATION already handled above */ }
            }
        }

        // Flush any remaining accumulators
        st.flush(def)
    }

    // ==================================================================
    // Section parsers
    // ==================================================================

    // ------------------------------------------------------------------
    // [MegaTune]
    // ------------------------------------------------------------------

    private fun parseMegaTune(key: String, value: String, def: EcuDefinition) {
        when (key) {
            "signature" -> def.signature = value.removeSurrounding("\"")
            "signaturePrefix" -> def.signaturePrefix = value.removeSurrounding("\"").ifEmpty { null }
            "queryCommand" -> def.queryCommand = value.removeSurrounding("\"")
            "versionInfo" -> def.versionInfo = value.removeSurrounding("\"")
            "iniSpecVersion" -> def.iniSpecVersion = value.trim()
            "ecuitype", "ecuType" -> {
                def.ecuType = when (value.trim().lowercase()) {
                    "megasquirt" -> EcuType.MEGASQUIRT
                    "speeduino" -> EcuType.SPEEDUINO
                    "rusefi", "rusEFI" -> EcuType.RUSEFI
                    "fome" -> EcuType.FOME
                    "epicefi" -> EcuType.EPICEFI
                    else -> EcuType.UNKNOWN
                }
            }
            "endianness" -> def.endianness = FieldParser.parseEndianness(value)
            "pageSize" -> {
                val parts = splitIniLine(value)
                def.pageSizes = parts.map { FieldParser.parseInt(it).toShort() }
                def.nPages = def.pageSizes.size.toByte()
            }
            "nPages" -> def.nPages = FieldParser.parseInt(value).toByte()
            "blockSize" -> def.protocol = def.protocol.copy(blockSize = FieldParser.parseInt(value))
            "timeout" -> def.protocol = def.protocol.copy(timeout = FieldParser.parseInt(value))
            "burnCommand" -> def.protocol = def.protocol.copy(burnCommand = value.removeSurrounding("\""))
            "commandTimeout" -> def.protocol = def.protocol.copy(commandTimeout = FieldParser.parseInt(value))
        }
    }

    // ------------------------------------------------------------------
    // [Constants]
    // ------------------------------------------------------------------

    private fun parseConstant(key: String, value: String, def: EcuDefinition) {
        val name = key.trim()
        if (name.isEmpty()) return

        // Resolve $define references in the value
        val resolvedValue = FieldParser.resolveDefines(value, defines)

        // Split into field block and description
        val (field, description) = FieldParser.splitFieldAndDescription(resolvedValue)

        val constant = if (field.startsWith("{")) {
            FieldParser.parseConstantField(field, name, defines)
        } else {
            // Basic format: name = description (scalar, u8, page=0, offset=0)
            createBasicConstant(name, field.ifEmpty { description })
        }

        if (constant != null) {
            val withMeta = constant.copy(
                helpText = if (description.isNotEmpty()) description else constant.helpText
            )
            def.constants[name] = withMeta
        }
    }

    /**
     * Create a basic (minimal) constant when no field block is present.
     */
    private fun createBasicConstant(name: String, description: String): Constant {
        return Constant(
            name = name,
            page = 0,
            offset = 0,
            dataType = DataType.U08,
            helpText = description
        )
    }

    // ------------------------------------------------------------------
    // [OutputChannels]
    // ------------------------------------------------------------------

    private fun parseOutputChannel(key: String, value: String, def: EcuDefinition) {
        val name = key.trim()
        if (name.isEmpty()) return

        val resolvedValue = FieldParser.resolveDefines(value, defines)
        val (field, _description) = FieldParser.splitFieldAndDescription(resolvedValue)

        val channel = if (field.startsWith("{")) {
            FieldParser.parseOutputChannelField(field, name)
        } else {
            // Simple offset-only format: channelName = offset
            val offset = FieldParser.parseInt(field)
            OutputChannel(name = name, offset = offset)
        }

        if (channel != null) {
            def.outputChannels[name] = channel
        }
    }

    // ------------------------------------------------------------------
    // [TableEditor] / [Table3D] / [Table2D]
    // ------------------------------------------------------------------

    private fun parseTableEditor(key: String, value: String, section: String, def: EcuDefinition) {
        val name = key.trim()
        if (name.isEmpty()) return

        val resolvedValue = FieldParser.resolveDefines(value, defines)
        val (field, title) = FieldParser.splitFieldAndDescription(resolvedValue)

        val table = if (field.startsWith("{")) {
            FieldParser.parseTableEditorField(field, name)?.copy(title = title)
        } else {
            null
        }

        if (table != null) {
            def.tables[name] = table
        }
    }

    // ------------------------------------------------------------------
    // [CurveEditor] / [Curve2D]
    // ------------------------------------------------------------------

    private fun parseCurveEditor(key: String, value: String, def: EcuDefinition) {
        val name = key.trim()
        if (name.isEmpty()) return

        val resolvedValue = FieldParser.resolveDefines(value, defines)
        val (field, title) = FieldParser.splitFieldAndDescription(resolvedValue)

        val inner = FieldParser.extractExpression(field) ?: field
        val parts = FieldParser.splitFieldBlock(inner)

        val binsName = parts.getOrNull(0)?.trim() ?: ""
        val binsOffset = parts.getOrNull(1)?.let { FieldParser.parseInt(it) } ?: 0
        val binsPage = parts.getOrNull(2)?.let { FieldParser.parseInt(it) } ?: 0
        val valuesOffset = parts.getOrNull(3)?.let { FieldParser.parseInt(it) } ?: 0
        val valuesPage = parts.getOrNull(4)?.let { FieldParser.parseInt(it) } ?: 0
        val size = parts.getOrNull(5)?.let { FieldParser.parseInt(it) } ?: 0
        val scaleStr = parts.getOrNull(6)?.trim() ?: "1"
        val translateStr = parts.getOrNull(7)?.trim() ?: "0"
        val units = parts.getOrNull(8)?.trim()?.removeSurrounding("\"")?.removeSurrounding("'") ?: ""

        def.curves[name] = CurveDefinition(
            name = name,
            binsName = binsName,
            binsOffset = binsOffset,
            binsPage = binsPage,
            valuesOffset = valuesOffset,
            valuesPage = valuesPage,
            scale = FieldParser.parseDouble(scaleStr),
            translate = FieldParser.parseDouble(translateStr),
            units = units,
            size = size,
            title = title
        )
    }

    // ------------------------------------------------------------------
    // [GaugeConfigurations] / [GaugeConfig]
    // ------------------------------------------------------------------

    private fun parseGauge(key: String, value: String, def: EcuDefinition) {
        val line = "$key = $value"
        val gauge = FieldParser.parseGaugeLine(line)
        if (gauge != null) {
            def.gauges[key] = gauge
        }
    }

    // ------------------------------------------------------------------
    // [Menu]
    // ------------------------------------------------------------------

    private fun parseMenu(key: String, value: String, st: SectionState) {
        val lowerKey = key.lowercase()

        when {
            lowerKey == "menu" -> {
                val parts = splitIniLine(value)
                val label = parts.getOrNull(0)?.trim()?.removeSurrounding("\"")?.removeSurrounding("'") ?: ""
                val command = parts.getOrNull(1)?.trim() ?: ""
                val helpTopic = parts.getOrNull(2)?.trim() ?: ""
                val condition = parts.getOrNull(3)?.trim() ?: ""

                val dialogName = if (command.startsWith("dialog,")) {
                    command.removePrefix("dialog,").trim()
                } else null

                val tableName = if (command.startsWith("table,")) {
                    command.removePrefix("table,").trim()
                } else null

                st.pendingMenuItems.add(Menu(
                    label = label,
                    command = if (dialogName == null && tableName == null) command else "",
                    dialogName = dialogName,
                    tableName = tableName,
                    helpTopic = helpTopic.ifEmpty { null },
                    condition = condition.ifEmpty { null }
                ))
            }
            lowerKey == "submenu" -> {
                val parts = splitIniLine(value)
                val label = parts.getOrNull(0)?.trim()?.removeSurrounding("\"")?.removeSurrounding("'") ?: ""
                val command = parts.getOrNull(1)?.trim() ?: ""
                val helpTopic = parts.getOrNull(2)?.trim() ?: ""
                val condition = parts.getOrNull(3)?.trim() ?: ""

                val dialogName = if (command.startsWith("dialog,")) {
                    command.removePrefix("dialog,").trim()
                } else null
                val tableName = if (command.startsWith("table,")) {
                    command.removePrefix("table,").trim()
                } else null

                val subItem = Menu(
                    label = label,
                    command = if (dialogName == null && tableName == null) command else "",
                    dialogName = dialogName,
                    tableName = tableName,
                    helpTopic = helpTopic.ifEmpty { null },
                    condition = condition.ifEmpty { null }
                )

                val lastIdx = st.pendingMenuItems.lastIndex
                if (lastIdx >= 0) {
                    val lastMenu = st.pendingMenuItems[lastIdx]
                    st.pendingMenuItems[lastIdx] = lastMenu.copy(subMenu = lastMenu.subMenu + subItem)
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // [Dialog]
    // ------------------------------------------------------------------

    private fun parseDialog(key: String, value: String, st: SectionState) {
        val lowerKey = key.lowercase()

        when {
            lowerKey == "dialog" || lowerKey == "dialogdef" -> {
                // dialog = name, title
                val parts = splitIniLine(value)
                st.currentDialogName = parts.getOrNull(0)?.trim() ?: ""
                val title = parts.getOrNull(1)?.trim()?.removeSurrounding("\"")?.removeSurrounding("'") ?: ""
                st.currentDialogComponents.clear()
            }
            lowerKey == "field" -> {
                val parts = splitIniLine(value)
                val label = parts.getOrNull(0)?.trim()?.removeSurrounding("\"")?.removeSurrounding("'") ?: ""
                val fieldName = parts.getOrNull(1)?.trim() ?: ""
                val condition = parts.getOrNull(2)?.trim()?.ifEmpty { null }

                st.currentDialogComponents.add(DialogComponent(
                    type = DialogComponentType.FIELD,
                    label = label,
                    name = fieldName,
                    visibilityCondition = condition
                ))
            }
            lowerKey == "spinner" -> {
                val parts = splitIniLine(value)
                val label = parts.getOrNull(0)?.trim()?.removeSurrounding("\"")?.removeSurrounding("'") ?: ""
                val fieldName = parts.getOrNull(1)?.trim() ?: ""

                st.currentDialogComponents.add(DialogComponent(
                    type = DialogComponentType.SPINNER,
                    label = label,
                    name = fieldName
                ))
            }
            lowerKey == "panel" -> {
                st.currentDialogComponents.add(DialogComponent(
                    type = DialogComponentType.PANEL,
                    panelName = value.trim()
                ))
            }
            lowerKey == "separator" -> {
                st.currentDialogComponents.add(DialogComponent(
                    type = DialogComponentType.SEPARATOR
                ))
            }
            lowerKey == "tab" -> {
                st.currentDialogComponents.add(DialogComponent(
                    type = DialogComponentType.TAB,
                    tabName = value.trim().removeSurrounding("\"")
                ))
            }
        }
    }

    // ------------------------------------------------------------------
    // [SettingGroups]
    // ------------------------------------------------------------------

    private fun parseSettingGroup(key: String, value: String, st: SectionState, def: EcuDefinition) {
        val lowerKey = key.lowercase()
        when {
            lowerKey == "settinggroup" || lowerKey == "group" -> {
                // Flush previous group into def
                if (st.currentSettingGroupName.isNotEmpty()) {
                    def.settingGroups[st.currentSettingGroupName] = SettingGroup(
                        name = st.currentSettingGroupName,
                        constants = st.currentSettingConstants.toList()
                    )
                    st.currentSettingConstants.clear()
                }
                val parts = splitIniLine(value)
                val name = parts.getOrNull(0)?.trim() ?: ""
                val title = parts.getOrNull(1)?.trim()?.removeSurrounding("\"")?.removeSurrounding("'") ?: ""
                st.currentSettingGroupName = name
                if (name.isNotEmpty()) {
                    def.settingGroups[name] = SettingGroup(name = name, title = title)
                }
            }
            lowerKey == "constant" || lowerKey == "entry" -> {
                val constantName = value.trim()
                if (constantName.isNotEmpty()) {
                    st.currentSettingConstants.add(constantName)
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // [HelpTopic]
    // ------------------------------------------------------------------

    private fun parseHelpTopic(key: String, value: String, st: SectionState) {
        val lowerKey = key.lowercase()
        when {
            lowerKey == "helptopic" -> {
                // Flush previous topic into the pending state;
                // actual write to EcuDefinition happens at section change.
                st.currentHelpTopicName = ""
                st.currentHelpText.clear()
                val parts = splitIniLine(value)
                st.currentHelpTopicName = parts.getOrNull(0)?.trim() ?: ""
            }
            lowerKey == "helptext" || lowerKey == "text" -> {
                val text = value.trim().removeSurrounding("\"")
                if (st.currentHelpText.isNotEmpty()) {
                    st.currentHelpText.append("\n")
                }
                st.currentHelpText.append(text)
            }
        }
    }

    // ------------------------------------------------------------------
    // [Datalog]
    // ------------------------------------------------------------------

    private fun parseDatalog(key: String, value: String, def: EcuDefinition) {
        val lowerKey = key.lowercase()
        when {
            lowerKey == "datalog" -> {
                val parts = splitIniLine(value)
                val name = parts.getOrNull(0)?.trim() ?: ""
                val channelName = parts.getOrNull(1)?.trim() ?: ""
                def.datalogEntries.add(DatalogEntry(name = name, channelName = channelName))
            }
            lowerKey == "entry" -> {
                def.datalogEntries.add(DatalogEntry(channelName = value.trim()))
            }
        }
    }

    // ------------------------------------------------------------------
    // [DatalogViews]
    // ------------------------------------------------------------------

    private fun parseDatalogView(key: String, value: String, st: SectionState, def: EcuDefinition) {
        val lowerKey = key.lowercase()
        when {
            lowerKey == "datalogview" -> {
                if (st.currentDatalogViewName.isNotEmpty()) {
                    def.datalogViews[st.currentDatalogViewName] = DatalogView(
                        name = st.currentDatalogViewName,
                        entries = st.currentDatalogViewEntries.toList()
                    )
                    st.currentDatalogViewEntries.clear()
                }
                val name = value.trim()
                st.currentDatalogViewName = name
                if (name.isNotEmpty()) {
                    def.datalogViews[name] = DatalogView(name = name)
                }
            }
            lowerKey == "entry" || lowerKey == "channel" -> {
                val channel = value.trim()
                if (channel.isNotEmpty()) {
                    st.currentDatalogViewEntries.add(channel)
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // [FrontPage]
    // ------------------------------------------------------------------

    private fun parseFrontPage(key: String, value: String, def: EcuDefinition) {
        val lowerKey = key.lowercase()
        if (def.frontpage == null) {
            def.frontpage = FrontPageConfig()
        }
        val fp = def.frontpage!!

        when (lowerKey) {
            "gauge" -> def.frontpage = fp.copy(gauges = fp.gauges + value.trim())
            "indicator" -> def.frontpage = fp.copy(indicators = fp.indicators + value.trim())
            "readout" -> def.frontpage = fp.copy(readouts = fp.readouts + value.trim())
            "gauges" -> def.frontpage = fp.copy(gauges = splitIniLine(value).map { it.trim() })
            "indicators" -> def.frontpage = fp.copy(indicators = splitIniLine(value).map { it.trim() })
            "readouts" -> def.frontpage = fp.copy(readouts = splitIniLine(value).map { it.trim() })
        }
    }

    // ------------------------------------------------------------------
    // [IndicatorPanel]
    // ------------------------------------------------------------------

    private fun parseIndicatorPanel(key: String, value: String, st: SectionState) {
        val lowerKey = key.lowercase()
        when {
            lowerKey == "indicatorpanel" -> {
                // Flush previous panel
                if (st.currentIndicatorName.isNotEmpty() && st.currentIndicatorConfigs.isNotEmpty()) {
                    // Will be flushed at section change; just update name
                }
                st.currentIndicatorName = value.trim().removeSurrounding("\"")
                st.currentIndicatorConfigs.clear()
            }
            lowerKey == "indicator" -> {
                val parts = splitIniLine(value)
                st.currentIndicatorConfigs.add(IndicatorConfig(
                    name = parts.getOrNull(0)?.trim() ?: "",
                    channelName = parts.getOrNull(1)?.trim() ?: "",
                    label = parts.getOrNull(2)?.trim()?.removeSurrounding("\"")?.removeSurrounding("'") ?: "",
                    onColor = parts.getOrNull(3)?.trim() ?: "green",
                    offColor = parts.getOrNull(4)?.trim() ?: "red"
                ))
            }
        }
    }

    // ------------------------------------------------------------------
    // [readoutPanel]
    // ------------------------------------------------------------------

    private fun parseReadoutPanel(key: String, value: String, st: SectionState) {
        val lowerKey = key.lowercase()
        when {
            lowerKey == "readoutpanel" -> {
                st.currentReadoutName = value.trim().removeSurrounding("\"")
                st.currentReadoutConfigs.clear()
            }
            lowerKey == "readout" -> {
                val parts = splitIniLine(value)
                st.currentReadoutConfigs.add(ReadoutConfig(
                    name = parts.getOrNull(0)?.trim() ?: "",
                    channelName = parts.getOrNull(1)?.trim() ?: "",
                    label = parts.getOrNull(2)?.trim()?.removeSurrounding("\"")?.removeSurrounding("'") ?: "",
                    decimals = parts.getOrNull(3)?.let { FieldParser.parseInt(it) } ?: 1,
                    units = parts.getOrNull(4)?.trim()?.removeSurrounding("\"")?.removeSurrounding("'") ?: ""
                ))
            }
        }
    }

    // ------------------------------------------------------------------
    // [ControllerCommands]
    // ------------------------------------------------------------------

    private fun parseControllerCommand(key: String, value: String, def: EcuDefinition) {
        val lowerKey = key.lowercase()
        when {
            lowerKey == "controllercommand" || lowerKey == "command" -> {
                val parts = splitIniLine(value)
                val name = parts.getOrNull(0)?.trim() ?: ""
                val command = parts.getOrNull(1)?.trim() ?: ""
                val description = parts.getOrNull(2)?.trim()?.removeSurrounding("\"")?.removeSurrounding("'") ?: ""
                if (name.isNotEmpty()) {
                    def.controllerCommands[name] = ControllerCommand(
                        name = name, command = command, description = description
                    )
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // [LoggerDefinitions]
    // ------------------------------------------------------------------

    private fun parseLoggerDefinition(key: String, value: String, def: EcuDefinition) {
        val lowerKey = key.lowercase()
        when {
            lowerKey == "loggerdefinition" || lowerKey == "logger" -> {
                val parts = splitIniLine(value)
                val name = parts.getOrNull(0)?.trim() ?: ""
                if (name.isNotEmpty()) {
                    def.loggerDefinitions[name] = LoggerDefinition(
                        name = name,
                        startCommand = parts.getOrNull(1)?.trim() ?: "",
                        stopCommand = parts.getOrNull(2)?.trim() ?: "",
                        readCommand = parts.getOrNull(3)?.trim() ?: "",
                        logSize = parts.getOrNull(4)?.let { FieldParser.parseInt(it) } ?: 0
                    )
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // [DiagnosticLoggers]
    // ------------------------------------------------------------------

    private fun parseDiagnosticLogger(key: String, value: String, st: SectionState, def: EcuDefinition) {
        val lowerKey = key.lowercase()
        when {
            lowerKey == "diagnosticlogger" -> {
                val parts = splitIniLine(value)
                val name = parts.getOrNull(0)?.trim() ?: ""
                val title = parts.getOrNull(1)?.trim()?.removeSurrounding("\"")?.removeSurrounding("'") ?: ""
                val startCmd = parts.getOrNull(2)?.trim() ?: ""
                val stopCmd = parts.getOrNull(3)?.trim() ?: ""
                val readCmd = parts.getOrNull(4)?.trim() ?: ""
                val recordSize = parts.getOrNull(5)?.let { FieldParser.parseInt(it) } ?: 0
                val bufferSize = parts.getOrNull(6)?.let { FieldParser.parseInt(it) } ?: 0

                if (name.isNotEmpty()) {
                    // Flush previous logger's channels
                    if (st.currentLoggerName.isNotEmpty() && st.currentLoggerChannels.isNotEmpty()) {
                        // Find the previously-added logger and append channels
                        val prevIdx = def.diagnosticLoggers.indexOfFirst { it.name == st.currentLoggerName }
                        if (prevIdx >= 0) {
                            val prev = def.diagnosticLoggers[prevIdx]
                            def.diagnosticLoggers[prevIdx] = prev.copy(
                                channels = prev.channels + st.currentLoggerChannels
                            )
                        }
                        st.currentLoggerChannels.clear()
                    }

                    def.diagnosticLoggers.add(DiagnosticLogger(
                        name = name, title = title,
                        startCommand = startCmd, stopCommand = stopCmd,
                        readCommand = readCmd, recordSize = recordSize,
                        bufferSize = bufferSize
                    ))
                    st.currentLoggerName = name
                }
            }
            lowerKey == "channel" -> {
                val parts = splitIniLine(value)
                st.currentLoggerChannels.add(LoggerChannel(
                    name = parts.getOrNull(0)?.trim() ?: "",
                    offset = parts.getOrNull(1)?.let { FieldParser.parseInt(it) } ?: 0,
                    bitOffset = parts.getOrNull(2)?.let { FieldParser.parseInt(it) } ?: -1,
                    bitWidth = parts.getOrNull(3)?.let { FieldParser.parseInt(it) } ?: 0,
                    dataType = parts.getOrNull(4)?.let { FieldParser.parseDataType(it) } ?: DataType.U16,
                    scale = parts.getOrNull(5)?.let { FieldParser.parseDouble(it) } ?: 1.0,
                    translate = parts.getOrNull(6)?.let { FieldParser.parseDouble(it) } ?: 0.0,
                    units = parts.getOrNull(7)?.trim()?.removeSurrounding("\"")?.removeSurrounding("'") ?: "",
                    label = parts.getOrNull(8)?.trim()?.removeSurrounding("\"")?.removeSurrounding("'") ?: ""
                ))
            }
        }
    }

    // ------------------------------------------------------------------
    // [ReferenceTables]
    // ------------------------------------------------------------------

    private fun parseReferenceTable(key: String, value: String, def: EcuDefinition) {
        val lowerKey = key.lowercase()
        when {
            lowerKey == "referencetable" -> {
                val parts = splitIniLine(value)
                val name = parts.getOrNull(0)?.trim() ?: ""
                if (name.isNotEmpty()) {
                    def.referenceTables[name] = ReferenceTable(
                        name = name,
                        xBinsOffset = parts.getOrNull(1)?.let { FieldParser.parseInt(it) } ?: 0,
                        xBinsPage = parts.getOrNull(2)?.let { FieldParser.parseInt(it) } ?: 0,
                        xBinsSize = parts.getOrNull(3)?.let { FieldParser.parseInt(it) } ?: 0,
                        valuesOffset = parts.getOrNull(4)?.let { FieldParser.parseInt(it) } ?: 0,
                        valuesPage = parts.getOrNull(5)?.let { FieldParser.parseInt(it) } ?: 0,
                        valuesSize = parts.getOrNull(6)?.let { FieldParser.parseInt(it) } ?: 0
                    )
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // [PortEditor]
    // ------------------------------------------------------------------

    private fun parsePortEditor(key: String, value: String, def: EcuDefinition) {
        val lowerKey = key.lowercase()
        when {
            lowerKey == "porteditor" -> {
                val parts = splitIniLine(value)
                val name = parts.getOrNull(0)?.trim() ?: ""
                if (name.isNotEmpty()) {
                    def.portEditors[name] = PortEditorConfig(
                        name = name,
                        title = parts.getOrNull(1)?.trim()?.removeSurrounding("\"")?.removeSurrounding("'") ?: "",
                        mode = parts.getOrNull(2)?.trim() ?: ""
                    )
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // [FTPBrowser]
    // ------------------------------------------------------------------

    private fun parseFtpBrowser(key: String, value: String, def: EcuDefinition) {
        val lowerKey = key.lowercase()
        when {
            lowerKey == "ftpbrowser" -> {
                val parts = splitIniLine(value)
                val name = parts.getOrNull(0)?.trim() ?: ""
                if (name.isNotEmpty()) {
                    def.ftpBrowsers[name] = FTPBrowserConfig(
                        name = name,
                        title = parts.getOrNull(1)?.trim()?.removeSurrounding("\"")?.removeSurrounding("'") ?: ""
                    )
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // [KeyActions]
    // ------------------------------------------------------------------

    private fun parseKeyAction(key: String, value: String, def: EcuDefinition) {
        val lowerKey = key.lowercase()
        when {
            lowerKey == "keyaction" -> {
                val parts = splitIniLine(value)
                val keyName = parts.getOrNull(0)?.trim() ?: ""
                val action = parts.getOrNull(1)?.trim() ?: ""
                if (keyName.isNotEmpty()) {
                    def.keyActions.add(KeyAction(key = keyName, action = action))
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // [VeAnalyze]
    // ------------------------------------------------------------------

    private fun parseVeAnalyze(key: String, value: String, def: EcuDefinition) {
        when (key.lowercase()) {
            "vetable" -> def.veAnalyze = (def.veAnalyze ?: VeAnalyzeConfig()).copy(veTableName = value.trim())
            "targettable" -> def.veAnalyze = (def.veAnalyze ?: VeAnalyzeConfig()).copy(targetTableName = value.trim())
            "lambdatarget" -> {
                val tables = splitIniLine(value).map { it.trim() }
                def.veAnalyze = (def.veAnalyze ?: VeAnalyzeConfig()).copy(lambdaTargetTables = tables)
            }
        }
    }

    // ------------------------------------------------------------------
    // [WueAnalyze]
    // ------------------------------------------------------------------

    private fun parseWueAnalyze(key: String, value: String, def: EcuDefinition) {
        when (key.lowercase()) {
            "wuecurve" -> def.wueAnalyze = (def.wueAnalyze ?: WueAnalyzeConfig()).copy(wueCurveName = value.trim())
            "targettable" -> def.wueAnalyze = (def.wueAnalyze ?: WueAnalyzeConfig()).copy(targetTableName = value.trim())
            "lambdatarget" -> {
                val tables = splitIniLine(value).map { it.trim() }
                def.wueAnalyze = (def.wueAnalyze ?: WueAnalyzeConfig()).copy(lambdaTargetTables = tables)
            }
        }
    }

    // ------------------------------------------------------------------
    // [GammaE]
    // ------------------------------------------------------------------

    private fun parseGammaE(key: String, value: String, def: EcuDefinition) {
        when (key.lowercase()) {
            "table" -> def.gammaE = (def.gammaE ?: GammaEConfig()).copy(table = value.trim())
            "channel" -> def.gammaE = (def.gammaE ?: GammaEConfig()).copy(channel = value.trim())
            "overlaytable" -> def.gammaE = (def.gammaE ?: GammaEConfig()).copy(overlayTable = value.trim())
        }
    }

    // ------------------------------------------------------------------
    // [ConstantsExtensions]
    // ------------------------------------------------------------------

    private fun parseConstantsExtensions(key: String, value: String, def: EcuDefinition) {
        val lowerKey = key.lowercase()
        when {
            lowerKey == "maintainconstantvalue" -> {
                val parts = splitIniLine(value)
                val constantName = parts.getOrNull(0)?.trim() ?: ""
                val expression = parts.getOrNull(1)?.trim() ?: ""
                if (constantName.isNotEmpty()) {
                    def.maintainConstantValues.add(MaintainConstantValue(
                        constantName = constantName, expression = expression
                    ))
                }
            }
            lowerKey == "maximumelements" || lowerKey == "maximuelements" -> {
                val parts = splitIniLine(value)
                val name = parts.getOrNull(0)?.trim() ?: ""
                val max = parts.getOrNull(1)?.let { FieldParser.parseInt(it) } ?: 0
                if (name.isNotEmpty()) {
                    def.maximumElements[name] = max
                }
            }
            lowerKey == "defaultvalue" -> {
                val parts = splitIniLine(value)
                val name = parts.getOrNull(0)?.trim() ?: ""
                val defaultVal = parts.getOrNull(1)?.let { FieldParser.parseDouble(it) } ?: 0.0
                if (name.isNotEmpty()) {
                    def.defaultValues[name] = defaultVal
                }
            }
            lowerKey == "requirespowercycle" -> {
                def.requiresPowerCycle.add(value.trim())
            }
        }
    }

    // ==================================================================
    // Preprocessor handling
    // ==================================================================

    /**
     * Handle `#if SYMBOL` or `#ifdef SYMBOL`.
     *
     * Supports simple symbol presence checks and compound conditions:
     * - `#if CELSIUS` — single symbol
     * - `#if CELSIUS && STM32` — all must be active (AND)
     * - `#if CELSIUS || FAHRENHEIT` — any must be active (OR)
     */
    private fun handleIf(token: IniToken) {
        val condition = token.value.trim()
        val hasOr = condition.contains("||")

        val symbols = condition
            .replace("&&", " ").replace("||", " ")
            .split(Regex("\\s+"))
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.equals("&&") && !it.equals("||") }

        for (sym in symbols) {
            testedSymbols.add(sym)
        }

        val branchTaken = if (hasOr) {
            symbols.any { it in activeSymbols }
        } else {
            symbols.all { it in activeSymbols }
        }

        ppStack.add(Pair(condition, branchTaken))
    }

    /** Handle `#else` — flip the most recent `#if` result. */
    private fun handleElse() {
        if (ppStack.isNotEmpty()) {
            val last = ppStack.removeAt(ppStack.lastIndex)
            ppStack.add(Pair(last.first, !last.second))
        }
    }

    /** Handle `#endif` — pop the preprocessor stack. */
    private fun handleEndif() {
        if (ppStack.isNotEmpty()) {
            ppStack.removeAt(ppStack.lastIndex)
        }
    }

    /** Handle `#define NAME value1 value2 ...` */
    private fun handleDefine(token: IniToken) {
        val name = token.key.trim()
        val valueStr = token.value.trim()
        if (name.isEmpty()) return

        defines[name] = if (valueStr.isNotEmpty()) {
            valueStr.split(Regex("\\s+"))
        } else {
            emptyList()
        }
    }

    /**
     * Check whether we are currently in a skipped preprocessor block.
     * A line is skipped if ANY entry on the preprocessor stack has
     * `branchTaken == false`.
     */
    private fun isSkipping(): Boolean = ppStack.any { !it.second }
}
