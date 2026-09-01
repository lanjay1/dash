package com.ztune.libretune.core

import android.content.Context
import android.content.res.AssetManager
import com.ztune.libretune.core.ini.EcuDefinition
import com.ztune.libretune.core.ini.parser.IniParser
import com.ztune.libretune.core.ini.types.EcuType
import android.util.Log


/**
 * Loads and caches [EcuDefinition] instances from INI files in `assets/definitions/`.
 *
 * The asset directory is expected to contain sub-directories (one per ECU firmware),
 * each holding a `.ini` file.  Example layout:
 *
 * ```
 * assets/definitions/
 *   megasquirt/
 *     ms3_3.1.x.ini
 *   speeduino/
 *     speeduino.ini
 *   rusefi/
 *     rusefi.ini
 * ```
 *
 * Thread-safety: all public methods synchronise on [cache].
 */
class EcuDefinitionRepository(private val context: Context) {

    companion object {
        private const val TAG = "EcuDefRepo"
        private const val DEFINITIONS_DIR = "definitions"
    }

    private val cache = mutableMapOf<String, EcuDefinition>()
    private val parser = IniParser()

    // ------------------------------------------------------------------
    //  Public API
    // ------------------------------------------------------------------

    /**
     * Scan `assets/definitions/` and return metadata for every discovered INI file.
     *
     * Each sub-directory of `definitions/` is treated as an [EcuType] bucket.
     * Only `.ini` files are listed.
     */
    fun listDefinitions(): List<DefinitionInfo> {
        val results = mutableListOf<DefinitionInfo>()
        val assetManager = context.assets

        val ecuTypeMap = mapOf(
            "megasquirt" to EcuType.MEGASQUIRT,
            "speeduino" to EcuType.SPEEDUINO,
            "rusefi" to EcuType.RUSEFI,
            "fome" to EcuType.FOME,
            "epicefi" to EcuType.EPICEFI
        )

        val dirNames = safeList(assetManager, DEFINITIONS_DIR)
        for (dirName in dirNames) {
            val ecuType = ecuTypeMap[dirName.lowercase()] ?: EcuType.UNKNOWN
            val dirPath = "$DEFINITIONS_DIR/$dirName"
            val files = safeList(assetManager, dirPath)
            for (file in files) {
                if (!file.endsWith(".ini", ignoreCase = true)) continue
                val fullPath = "$dirPath/$file"
                // Try to extract a quick signature from the cached definition
                val signature = cache[fullPath]?.signature ?: ""
                results.add(
                    DefinitionInfo(
                        fileName = file,
                        path = fullPath,
                        ecuType = ecuType,
                        signature = signature
                    )
                )
            }
        }
        return results.sortedBy { it.fileName }
    }

    /**
     * Parse and cache an INI definition by its path relative to `assets/`.
     *
     * @param path e.g. `"definitions/megasquirt/ms3_3.1.x.ini"`
     * @return the parsed [EcuDefinition], or a failure with parse diagnostics.
     */
    fun loadDefinition(path: String): Result<EcuDefinition> = synchronized(cache) {
        cache[path]?.let { return Result.success(it) }
        try {
            val stream = context.assets.open(path)
            val text = stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            stream.close()
            val definition = parser.parse(text).getOrThrow()
            definition.inferTableRoles()
            cache[path] = definition
            Log.d(TAG, "Loaded definition: $path (sig=${definition.signature})")
            Result.success(definition)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load definition: $path", e)
            Result.failure(e)
        }
    }

    /**
     * Search all known definitions for one whose [EcuDefinition.signature]
     * matches or starts with [signature].
     *
     * This will lazily load every definition if they haven't been cached yet.
     *
     * @param signature the ECU's signature string (from handshake "Q" command).
     * @return the best-matching definition, or null if none match.
     */
    fun findDefinitionForSignature(signature: String): EcuDefinition? {
        // Fast path: already cached
        cache.values.firstOrNull { it.signature == signature }?.let { return it }

        // Load all definitions and search
        for (info in listDefinitions()) {
            val def = loadDefinition(info.path).getOrNull() ?: continue
            if (def.signature == signature || signature.startsWith(def.signature)) {
                return def
            }
        }
        return null
    }

    /** Return a read-only snapshot of all cached definitions. */
    fun getAllDefinitions(): Map<String, EcuDefinition> = synchronized(cache) { cache.toMap() }

    /** Clear the definition cache. */
    fun clearCache() = synchronized(cache) { cache.clear() }

    // ------------------------------------------------------------------
    //  Helpers
    // ------------------------------------------------------------------

    private fun safeList(assetManager: AssetManager, path: String): Array<String> {
        return try {
            assetManager.list(path) ?: emptyArray()
        } catch (e: Exception) {
            Log.w(TAG, "Could not list assets: $path", e)
            emptyArray()
        }
    }
}

/**
 * Metadata about a single INI definition file on disk / in assets.
 *
 * @property fileName the bare file name, e.g. `"ms3_3.1.x.ini"`
 * @property path     the path relative to `assets/`, e.g. `"definitions/megasquirt/ms3_3.1.x.ini"`
 * @property ecuType  the ECU platform inferred from the parent directory name.
 * @property signature the definition's signature string (populated after [loadDefinition]).
 */
data class DefinitionInfo(
    val fileName: String,
    val path: String,
    val ecuType: EcuType,
    val signature: String = ""
)
