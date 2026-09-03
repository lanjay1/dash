package com.ztune.libretune.core.tune

/**
 * A saved tune file containing all calibration data.
 *
 * Represents the complete state of an ECU tune loaded from an `.msq` file
 * or received from the ECU. Holds raw page bytes plus decoded constant,
 * table, and curve values for UI consumption.
 */
data class Tune(
    /** Metadata about the INI definition that was used to create/validate this tune. */
    val iniMetadata: IniMetadata = IniMetadata(),
    /** Manifest of constants for validating tune/INI compatibility. */
    val constantManifest: List<ConstantManifestEntry> = emptyList(),
    /** Raw page data, keyed by page number. */
    val pageData: Map<Int, ByteArray> = emptyMap(),
    /** Decoded constant values (name -> display value). */
    val constantValues: MutableMap<String, Double> = mutableMapOf(),
    /** Table cell values (tableName -> 2D double array, rows x columns). */
    val tableValues: MutableMap<String, List<List<Double>>> = mutableMapOf(),
    /** Curve values (curveName -> list of doubles). */
    val curveValues: MutableMap<String, List<Double>> = mutableMapOf(),
    /** Optional project metadata (user-defined name, notes, timestamps). */
    val projectInfo: ProjectInfo? = null
) {
    /** Get a decoded constant value by name, or null if not present. */
    fun getConstantValue(name: String): Double? = constantValues[name]

    /** Set a decoded constant value. */
    fun setConstantValue(name: String, value: Double) {
        constantValues[name] = value
    }

    /** Get a single table cell value. Returns null if the table or cell is out of range. */
    fun getTableCell(tableName: String, row: Int, col: Int): Double? {
        val table = tableValues[tableName] ?: return null
        if (row !in table.indices) return null
        val rowData = table[row]
        if (col !in rowData.indices) return null
        return rowData[col]
    }

    /** Set a single table cell value, auto-growing the table as needed. */
    fun setTableCell(tableName: String, row: Int, col: Int, value: Double) {
        @Suppress("UNCHECKED_CAST")
        val table = tableValues.getOrPut(tableName) { mutableListOf() } as MutableList
        while (table.size <= row) table.add(mutableListOf())
        @Suppress("UNCHECKED_CAST")
        val rowList = table[row] as MutableList
        while (rowList.size <= col) rowList.add(0.0)
        rowList[col] = value
    }

    /** Get a single curve value by index, or null if out of range. */
    fun getCurveValue(curveName: String, index: Int): Double? {
        val curve = curveValues[curveName] ?: return null
        if (index !in curve.indices) return null
        return curve[index]
    }

    /** Set a single curve value, auto-growing the curve as needed. */
    fun setCurveValue(curveName: String, index: Int, value: Double) {
        @Suppress("UNCHECKED_CAST")
        val curve = curveValues.getOrPut(curveName) { mutableListOf() } as MutableList
        while (curve.size <= index) curve.add(0.0)
        curve[index] = value
    }

    /** Get the raw bytes for a page, or null if the page is not present. */
    fun getPageData(page: Int): ByteArray? = pageData[page]

    /**
     * Create a deep copy of this tune.
     * All page data byte arrays are copied, and all mutable value maps
     * are cloned so mutations to the copy do not affect the original.
     */
    fun deepCopy(): Tune = copy(
        pageData = pageData.mapValues { it.value.copyOf() },
        constantValues = constantValues.toMutableMap(),
        tableValues = tableValues.mapValues { (_, v) -> v.map { it.toList() } }.toMutableMap(),
        curveValues = curveValues.toMutableMap()
    )

    /**
     * Returns true if this tune has no data at all (no pages, no constants, no tables, no curves).
     */
    fun isEmpty(): Boolean =
        pageData.isEmpty() && constantValues.isEmpty() && tableValues.isEmpty() && curveValues.isEmpty()
}

/**
 * Metadata about the INI file that was used to create this tune.
 * Stored inside the .msq so that the correct INI can be loaded when reopening.
 */
data class IniMetadata(
    /** ECU signature string (e.g. "Speeduino 2024.01"). */
    val signature: String = "",
    /** Human-readable name of the INI / firmware. */
    val name: String = "",
    /** Structural hash of the INI definition for compatibility checking. */
    val hash: String = "",
    /** INI spec version (e.g. "3.64"). */
    val specVersion: String = "",
    /** ISO-8601 timestamp of when the tune was last saved. */
    val savedAt: String = ""
)

/**
 * Entry in the constant manifest used for validating that a saved tune
 * is compatible with a given INI definition.
 *
 * If any constant's data type, page, or offset differs between the
 * manifest and the loaded INI, the tune is considered incompatible.
 */
data class ConstantManifestEntry(
    /** Constant name (must match INI). */
    val name: String = "",
    /** Data type string from the INI (e.g. "U08", "S16"). */
    val dataType: String = "",
    /** Page number where this constant resides in ECU memory. */
    val page: Int = 0,
    /** Byte offset within the page. */
    val offset: Int = 0,
    /** Scale factor applied when decoding. */
    val scale: Double = 1.0,
    /** Translate offset applied when decoding. */
    val translate: Double = 0.0
)

/**
 * User-defined project metadata associated with a tune.
 * This is optional and stored at the end of the .msq file.
 */
data class ProjectInfo(
    /** User-given name for this tune/project. */
    val name: String = "",
    /** Free-form description. */
    val description: String = "",
    /** Unix epoch millis when this project was created. */
    val createdAt: Long = System.currentTimeMillis(),
    /** Unix epoch millis when this project was last modified. */
    val modifiedAt: Long = System.currentTimeMillis(),
    /** Free-form notes. */
    val notes: String = ""
)