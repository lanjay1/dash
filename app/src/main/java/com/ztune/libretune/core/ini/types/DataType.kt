package com.ztune.libretune.core.ini.types

/**
 * Low-level data type of a constant / output channel / table cell.
 * [byteSize] is the on-wire size in bytes (0 for variable-length STRING).
 *
 * [rawMin] / [rawMax] define the integer range that can be represented
 * by this data type. Used for validation before writing to the ECU:
 * if a display value encodes to a raw value outside this range, the
 * write is rejected. [F32] has no effective limit (returns ±Infinity).
 */
enum class DataType(val byteSize: Int, val rawMin: Double, val rawMax: Double) {
    U08(1, 0.0, 255.0),
    S08(1, -128.0, 127.0),
    U16(2, 0.0, 65535.0),
    S16(2, -32768.0, 32767.0),
    U32(4, 0.0, 4294967295.0),
    S32(4, -2147483648.0, 2147483647.0),
    F32(4, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY),
    BITS(1, 0.0, 255.0),
    STRING(0, 0.0, 0.0);

    /**
     * Check whether [rawValue] fits within this data type's representable range.
     * Returns true if the value is safe to encode.
     */
    fun isInRange(rawValue: Double): Boolean {
        if (this == STRING) return false
        if (this == F32) return true
        return rawValue in rawMin..rawMax
    }
}
