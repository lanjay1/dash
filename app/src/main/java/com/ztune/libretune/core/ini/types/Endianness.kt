package com.ztune.libretune.core.ini.types

/**
 * Byte order for multi-byte values read from / written to the ECU.
 */
enum class Endianness {
    BIG_ENDIAN,
    LITTLE_ENDIAN;

    companion object {
        val DEFAULT = LITTLE_ENDIAN
    }
}
