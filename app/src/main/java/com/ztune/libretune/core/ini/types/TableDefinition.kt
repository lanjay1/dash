package com.ztune.libretune.core.ini.types

/** Dimensionality of a table. */
enum class TableType {
    TABLE_2D,
    TABLE_3D,
    TABLE_1D
}

/** Semantic role used by the UI to pick default gauges / colours. */
enum class TableRole {
    OTHER,
    VE,
    AFR_TARGET,
    WARMUP_ENRICHMENT,
    IGNITION
}

/** Description of one axis (bins) of a table. */
data class TableAxis(
    val binsName: String,
    val binsOffset: Int = 0,
    val binsPage: Int = 0,
    val dataType: DataType = DataType.U08,
    val scale: Double = 1.0,
    val translate: Double = 0.0,
    val units: String = "",
    val label: String = "",
    val min: Double = 0.0,
    val max: Double = 255.0,
    val expression: String? = null,
    val size: Int = 0,
    val format: String = "0.0"
)

/** Full table definition from the INI `[TableX]` / `[Table3D]` sections.
 *  Matches LibreTune's Rust `TableDefinition` struct.
 */
data class TableDefinition(
    val name: String,
    val mapName: String? = null,
    val tableType: TableType = TableType.TABLE_3D,
    var role: TableRole = TableRole.OTHER,
    val page: Int = 0,
    /** Column axis (X bins). */
    val xAxis: TableAxis? = null,
    /** Row axis (Y bins). */
    val yAxis: TableAxis? = null,
    val valuesOffset: Int = 0,
    val valuesPage: Int = 0,
    val dataType: DataType = DataType.U08,
    val scale: Double = 1.0,
    val translate: Double = 0.0,
    val units: String = "",
    val rows: Int = 0,
    val cols: Int = 0,
    val title: String = "",
    val helpText: String = "",
    val format: String = "0.0",
    val userEditable: Boolean = true
)
