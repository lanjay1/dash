package com.ztune.libretune.core.ini.types

/** Communication protocol parameters.
 *  Matches LibreTune's Rust `ProtocolSettings` struct.
 */
data class ProtocolSettings(
    val blockSize: Int = 256,
    /** Inter-byte / overall timeout in milliseconds. */
    val timeout: Int = 100,
    val burnCommand: String = "B",
    val commandTimeout: Int = 200
)