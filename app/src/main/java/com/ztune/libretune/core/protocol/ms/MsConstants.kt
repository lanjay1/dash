package com.ztune.libretune.core.protocol.ms

/**
 * MegaSquirt protocol constants.
 * Central place for all magic numbers used in the MS serial protocol.
 *
 * IMPORTANT: The command bytes below use the CORRECT MS serial protocol
 * conventions:
 *   - `Q` (uppercase) for signature query
 *   - `r` (LOWERCASE) for block read — MS2/MS3/Speeduino standard
 *   - `w` (LOWERCASE) for block write — MS2/MS3/Speeduino standard
 *   - `A` (uppercase) for realtime data burst
 *   - `B` (uppercase) for burn to flash
 *   - `c` (lowercase) for comm reset
 *
 * The previous version of this file used uppercase `R`/`W` for block
 * read/write, which is incorrect for real MS firmware. Real MegaSquirt
 * MCUs (MC9S12, big-endian) expect lowercase `r`/`w`.
 */
object MsConstants {
    // ------------------------------------------------------------------
    // Framing (FRAMED mode only — TS-BP / rusEFI / FOME)
    // ------------------------------------------------------------------
    const val HEADER: Byte = 0x5A
    const val ESCAPE: Byte = 0x7D

    // ------------------------------------------------------------------
    // Standard command bytes
    // ------------------------------------------------------------------
    const val CMD_QUERY_SIGNATURE: Byte = 'Q'.code.toByte()   // 0x51 — signature query
    const val CMD_BLOCK_READ: Byte = 'r'.code.toByte()        // 0x72 — block read (LOWERCASE, MS2/MS3/Speeduino)
    const val CMD_BLOCK_WRITE: Byte = 'w'.code.toByte()       // 0x77 — block write (LOWERCASE, MS2/MS3/Speeduino)
    const val CMD_REALTIME: Byte = 'A'.code.toByte()          // 0x41 — realtime data burst
    const val CMD_BURN: Byte = 'B'.code.toByte()             // 0x42 — burn to flash
    const val CMD_SINGLE_READ: Byte = '#'.code.toByte()      // 0x23 — single read (legacy)
    const val CMD_COMM_RESET: Byte = 'c'.code.toByte()       // 0x63 — comm reset
    const val CMD_VERSION: Byte = 'V'.code.toByte()          // 0x56 — version query

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
    // CRC-16/CCITT (FRAMED mode — TS-BP / rusEFI / FOME)
    // ------------------------------------------------------------------
    const val CRC_POLY: Int = 0x1021
    const val CRC_INIT: Int = 0xFFFF

    // ------------------------------------------------------------------
    // Response codes
    // ------------------------------------------------------------------
    const val RESPONSE_SUCCESS: Byte = 0x30  // '0' — ACK
    const val RESPONSE_FAILURE: Byte = 0x21  // '!' — NAK

    // ------------------------------------------------------------------
    // MS signature response length
    // ------------------------------------------------------------------
    /** MS firmware pads the signature response to this many bytes (NUL-padded). */
    const val SIGNATURE_RESPONSE_LENGTH: Int = 64
}
