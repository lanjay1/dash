package com.ztune.libretune.core.ini.types

/** A 1-D curve (bins + values) from the INI `[CurveX]` sections.
 *  Matches LibreTune's Rust `CurveDefinition` struct.
 */
data class CurveDefinition(
    val name: String,
    val mapName: String? = null,
    val binsName: String = "",
    val binsOffset: Int = 0,
    val binsPage: Int = 0,
    val valuesOffset: Int = 0,
    val valuesPage: Int = 0,
    val dataType: DataType = DataType.U08,
    val scale: Double = 1.0,
    val translate: Double = 0.0,
    val units: String = "",
    val size: Int = 0,
    val title: String = ""
)
