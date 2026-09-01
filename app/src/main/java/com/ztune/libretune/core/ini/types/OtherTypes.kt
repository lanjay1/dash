package com.ztune.libretune.core.ini.types

// ============================================================================
// Help topics
// ============================================================================

data class HelpTopic(val title: String = "", val text: String = "")

// ============================================================================
// Datalog
// ============================================================================

data class DatalogEntry(val name: String = "", val channelName: String = "")

data class DatalogView(val name: String = "", val entries: List<String> = emptyList())

// ============================================================================
// Key actions
// ============================================================================

data class KeyAction(val key: String = "", val action: String = "")

// ============================================================================
// Indicator panels
// ============================================================================

data class IndicatorConfig(
    val name: String = "",
    val channelName: String = "",
    val label: String = "",
    val onColor: String = "green",
    val offColor: String = "red"
)

data class IndicatorPanel(val name: String = "", val indicators: List<IndicatorConfig> = emptyList())

// ============================================================================
// Readout panels
// ============================================================================

data class ReadoutConfig(
    val name: String = "",
    val channelName: String = "",
    val label: String = "",
    val decimals: Int = 1,
    val units: String = ""
)

data class ReadoutPanel(val name: String = "", val readouts: List<ReadoutConfig> = emptyList())

// ============================================================================
// Controller commands
// ============================================================================

data class ControllerCommand(
    val name: String = "",
    val command: String = "",
    val description: String = ""
)

// ============================================================================
// Logger definitions
// ============================================================================

data class LoggerDefinition(
    val name: String = "",
    val startCommand: String = "",
    val stopCommand: String = "",
    val readCommand: String = "",
    val logSize: Int = 0
)

// ============================================================================
// Port editors
// ============================================================================

data class PortEditorConfig(
    val name: String = "",
    val title: String = "",
    val mode: String = ""
)

// ============================================================================
// Reference tables (sensor calibration)
// ============================================================================

data class ReferenceTable(
    val name: String = "",
    val xBinsOffset: Int = 0,
    val xBinsPage: Int = 0,
    val xBinsSize: Int = 0,
    val valuesOffset: Int = 0,
    val valuesPage: Int = 0,
    val valuesSize: Int = 0
)

// ============================================================================
// FTP browser
// ============================================================================

data class FTPBrowserConfig(val name: String = "", val title: String = "")

// ============================================================================
// Analysis configs (VE, WUE, GammaE)
// ============================================================================

data class VeAnalyzeConfig(
    val veTableName: String = "",
    val targetTableName: String = "",
    val lambdaTargetTables: List<String> = emptyList()
)

data class WueAnalyzeConfig(
    val wueCurveName: String = "",
    val targetTableName: String = "",
    val lambdaTargetTables: List<String> = emptyList()
)

data class GammaEConfig(
    val table: String = "",
    val channel: String = "",
    val overlayTable: String = ""
)

// ============================================================================
// PC-variable constant maintenance
// ============================================================================

data class MaintainConstantValue(
    val constantName: String = "",
    val expression: String = ""
)

// ============================================================================
// INI capabilities – flags the parser sets to indicate which sections
// were present, so the UI can gate features accordingly.
// Matches LibreTune's Rust `IniCapabilities` struct.
// ============================================================================

data class IniCapabilities(
    val hasConstants: Boolean = false,
    val hasOutputChannels: Boolean = false,
    val hasTables: Boolean = false,
    val hasCurves: Boolean = false,
    val hasGauges: Boolean = false,
    val hasFrontpage: Boolean = false,
    val hasDialogs: Boolean = false,
    val hasHelpTopics: Boolean = false,
    val hasSettingGroups: Boolean = false,
    val hasPcVariables: Boolean = false,
    val hasDefaultValues: Boolean = false,
    val hasDatalogEntries: Boolean = false,
    val hasDatalogViews: Boolean = false,
    val hasLoggerDefinitions: Boolean = false,
    val hasControllerCommands: Boolean = false,
    val hasPortEditors: Boolean = false,
    val hasReferenceTables: Boolean = false,
    val hasKeyActions: Boolean = false,
    val hasVeAnalyze: Boolean = false,
    val hasWueAnalyze: Boolean = false,
    val hasGammaE: Boolean = false,
    val supportsConsole: Boolean = false,
    val dfuCommandName: String? = null,
    val openBltCommandName: String? = null,
    val luaScriptConstant: String? = null
)