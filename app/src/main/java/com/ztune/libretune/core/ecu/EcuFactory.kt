package com.ztune.libretune.core.ecu

import com.ztune.libretune.core.ini.types.EcuType

/**
 * Factory that creates the correct [EcuInterface] implementation for a
 * given [EcuType].
 *
 * Converted from LibreTune's Rust `ecu/mod.rs` which uses a match on the
 * `EcuType` enum to dispatch to the concrete protocol handler.
 */
object EcuFactory {

    /**
     * Create an [EcuInterface] for the given [ecuType].
     *
     * @param ecuType The ECU platform type.
     * @return A new, unconnected ECU interface instance.
     */
    fun create(ecuType: EcuType): EcuInterface {
        return when (ecuType) {
            EcuType.MEGASQUIRT -> MegaSquirtEcu()
            EcuType.SPEEDUINO -> SpeeduinoEcu()
            EcuType.RUSEFI    -> RusEfiEcu()
            EcuType.FOME      -> FomeEcu()
            EcuType.EPICEFI   -> EpicEfiEcu()
            EcuType.UNKNOWN   -> MegaSquirtEcu() // fallback to MS protocol
        }
    }

    /**
     * Detect the ECU type from a signature string returned by the firmware.
     *
     * This is a best-effort heuristic used during the initial connection
     * handshake when the INI file has not yet been selected.
     *
     * @param signature The raw ASCII signature string from the ECU.
     * @return The most likely [EcuType], or [EcuType.UNKNOWN] if nothing matches.
     */
    fun detectFromSignature(signature: String): EcuType {
        val lower = signature.lowercase()
        return when {
            lower.contains("speeduino")                              -> EcuType.SPEEDUINO
            lower.contains("rusefi") || lower.contains("rus efi") ||
                lower.contains("rus_efi")                            -> EcuType.RUSEFI
            lower.contains("fome")                                   -> EcuType.FOME
            lower.contains("epicefi") || lower.contains("epic efi") -> EcuType.EPICEFI
            lower.contains("megasquirt") || lower.contains("ms3") ||
                lower.contains("ms2") || lower.contains("ms1")      -> EcuType.MEGASQUIRT
            else                                                     -> EcuType.UNKNOWN
        }
    }
}
