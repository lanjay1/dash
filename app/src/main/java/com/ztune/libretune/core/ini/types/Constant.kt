package com.ztune.libretune.core.ini.types

/**
 * A single editable parameter / constant from the INI `[Constants]` section.
 * Matches LibreTune's Rust `Constant` struct.
 */
data class Constant(
    val name: String,
    val page: Int,
    val offset: Int,
    val dataType: DataType = DataType.U08,
    var scale: Double = 1.0,
    var translate: Double = 0.0,
    var min: Double = 0.0,
    var max: Double = 255.0,
    val units: String = "",
    val shape: Shape = Shape.Scalar,
    /** Human-readable option labels for BITS-typed constants (one per bit). */
    var bitOptions: List<String> = emptyList(),
    /** True when this constant lives only on the PC side (not in ECU memory). */
    val isPcVariable: Boolean = false,
    // ---------- expression-based fields (deferred until tune values available) ----------
    val scaleExpr: String? = null,
    val translateExpr: String? = null,
    val minExpr: String? = null,
    val maxExpr: String? = null,
    /** Whether min / max were resolved from expressions (false ⇒ raw byte fallback). */
    var rangeResolved: Boolean = true,
    // ---------- 2D-array axis links ----------
    /** Linked table / curve name that provides X-axis bins. */
    val xBinsName: String? = null,
    /** Linked table / curve name that provides Y-axis bins. */
    val yBinsName: String? = null,
    // ---------- dialog / category grouping ----------
    val category: String = "",
    val groupName: String = "",
    // ---------- help text ----------
    val helpText: String = ""
) {
    companion object {
        /** Convenience factory with positional args matching the Rust constructor. */
        fun new(name: String, page: Int, offset: Int, dataType: DataType) =
            Constant(name = name, page = page, offset = offset, dataType = dataType)
    }
}
