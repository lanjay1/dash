package com.ztune.libretune.core.ini.types

/**
 * ECU platform type, matching LibreTune's Rust enum.
 */
enum class EcuType {
    UNKNOWN,
    MEGASQUIRT,
    SPEEDUINO,
    RUSEFI,
    FOME,
    EPICEFI;

    /** Only rusefi supports the interactive console. */
    fun supportsConsole(): Boolean = this == RUSEFI
}
