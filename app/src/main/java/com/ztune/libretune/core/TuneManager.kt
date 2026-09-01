package com.ztune.libretune.core

import com.ztune.libretune.core.ini.EcuDefinition
import com.ztune.libretune.core.tune.Tune

/**
 * Manages the current tune: loading from ECU, applying edits, saving to file,
 * and writing modified pages back to the ECU.
 *
 * Stub — will be expanded with page-buffer management, undo/redo, and
 * file I/O (save/load .msq / .ztune files).
 */
class TuneManager {

    /** The currently loaded tune (null until a tune is read from the ECU or file). */
    var currentTune: Tune? = null
        private set

    /** The ECU definition that governs the current tune's layout. */
    var activeDefinition: EcuDefinition? = null
        private set

    /** Whether the tune has unsaved modifications. */
    val isDirty: Boolean get() = false // stub

    /**
     * Load a tune from raw memory bytes using the given [definition].
     *
     * @param memoryBytes the full ECU memory image.
     * @param definition  the INI definition that describes the memory layout.
     */
    fun loadFromMemory(memoryBytes: ByteArray, definition: EcuDefinition) {
        activeDefinition = definition
        // Stub: real implementation would parse the memory image into a Tune
        currentTune = Tune() // placeholder
    }

    /**
     * Save the current tune to a file.
     *
     * Stub — will use [TuneSerializer][com.ztune.libretune.core.tune.TuneSerializer]
     * and [androidx.documentfile.DocumentFile] for SAF-based export.
     */
    fun saveToFile(): Result<Unit> {
        TODO("TuneManager.saveToFile() — not yet implemented")
    }

    /**
     * Load a tune from a saved file.
     *
     * Stub — will use a file picker and [TuneSerializer].
     */
    fun loadFromFile(): Result<Unit> {
        TODO("TuneManager.loadFromFile() — not yet implemented")
    }

    /** Discard the current tune and reset. */
    fun reset() {
        currentTune = null
        activeDefinition = null
    }
}
