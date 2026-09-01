package com.ztune.libretune.core.protocol.ms

/**
 * MegaSquirt protocol constants.
 * Central place for all magic numbers used in the MS serial protocol.
 */
object MsConstants {
    // ------------------------------------------------------------------
    // Framing
    // ------------------------------------------------------------------
    const val HEADER: Byte = 0x5A
    const val ESCAPE: Byte = 0x7D

    // ------------------------------------------------------------------
    // Standard command bytes
    // ------------------------------------------------------------------
    const val CMD_QUERY_SIGNATURE: Byte = 'Q'.code.toByte()   // 0x51
    const val CMD_BLOCK_READ: Byte = 'R'.code.toByte()       // 0x52
    const val CMD_BLOCK_WRITE: Byte = 'W'.code.toByte()      // 0x57
    const val CMD_BURN: Byte = 'B'.code.toByte()             // 0x42
    const val CMD_SINGLE_READ: Byte = '#'.code.toByte()      // 0x23
    const val CMD_COMM_RESET: Byte = 'c'.code.toByte()       // 0x63
    const val CMD_VERSION: Byte = 'V'.code.toByte()          // 0x56

    // ------------------------------------------------------------------
    // Timeouts (milliseconds)
    // ------------------------------------------------------------------
    const val DEFAULT_TIMEOUT_MS: Long = 1_000L
    const val BURN_TIMEOUT_MS: Long = 5_000L

    // ------------------------------------------------------------------
    // Block transfer limits
    // ------------------------------------------------------------------
    const val MAX_BLOCK_SIZE: Int = 256
    const val DEFAULT_BLOCK_SIZE: Int = 208

    // ------------------------------------------------------------------
    // CRC-16/CCITT-USB (XModem polynomial)
    // ------------------------------------------------------------------
    const val CRC_POLY: Int = 0x1021
    const val CRC_INIT: Int = 0xFFFF

    // ------------------------------------------------------------------
    // Response codes
    // ------------------------------------------------------------------
    const val RESPONSE_SUCCESS: Byte = 0x30  // '0'
    const val RESPONSE_FAILURE: Byte = 0x21  // '!'
}
