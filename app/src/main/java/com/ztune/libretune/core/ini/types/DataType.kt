package com.ztune.libretune.core.ini.types

/**
 * Low-level data type of a constant / output channel / table cell.
 * [byteSize] is the on-wire size in bytes (0 for variable-length STRING).
 */
enum class DataType(val byteSize: Int) {
    U08(1),
    S08(1),
    U16(2),
    S16(2),
    U32(4),
    S32(4),
    F32(4),
    BITS(1),    // bit-field – each bit selects an option from [bitOptions]
    STRING(0);  // variable-length null-terminated
}
