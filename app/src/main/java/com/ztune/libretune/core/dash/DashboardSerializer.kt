@file:Suppress("unused")

package com.ztune.libretune.core.dash

import android.content.Context
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.io.IOException

/**
 * Serializes and deserializes [DashboardConfig] objects to/from JSON files.
 *
 * Dashboards are stored in the app's internal files directory under `dashboards/`.
 * Each dashboard is a single `.json` file named `{id}.json`.
 *
 * File format:
 * ```json
 * {
 *   "version": 1,
 *   "id": "track_day",
 *   "name": "Track Day",
 *   "columns": 3,
 *   "rows": 4,
 *   "widgets": [ ... ],
 *   "showIndicators": true,
 *   "indicatorChannels": ["fan", "acClutch"]
 * }
 * ```
 */
object DashboardSerializer {

    /** Current file format version. Bumped when breaking schema changes occur. */
    const val FORMAT_VERSION = 1

    /** Directory name under `filesDir` where dashboard JSON files live. */
    private const val DIR_NAME = "dashboards"

    /** JSON configuration: pretty-printed for debugging, lenient on read. */
    private val json: Json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
        isLenient = true
        coerceInputValues = true
    }

    // ------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------

    /**
     * Save a dashboard configuration to a JSON file.
     *
     * @param context Android context.
     * @param config  The dashboard configuration to persist.
     * @return [Result.success] on write, [Result.failure] with the exception on error.
     */
    fun saveDashboard(context: Context, config: DashboardConfig): Result<Unit> =
        runCatching {
            val dir = ensureDir(context)
            val file = File(dir, "${config.id.sanitizeFileName()}.json")
            val wrapper = DashboardFileWrapper(
                version = FORMAT_VERSION,
                config = config
            )
            val serialized = json.encodeToString(wrapper)
            file.writeText(serialized, Charsets.UTF_8)
        }

    /**
     * Load a dashboard configuration from a JSON file by its id.
     *
     * @param context Android context.
     * @param id      The dashboard id (matches the filename without extension).
     * @return [Result.success] with the parsed [DashboardConfig],
     *         or [Result.failure] if the file doesn't exist or can't be parsed.
     */
    fun loadDashboard(context: Context, id: String): Result<DashboardConfig> =
        runCatching {
            val dir = ensureDir(context)
            val file = File(dir, "${id.sanitizeFileName()}.json")
            if (!file.exists()) {
                throw IOException("Dashboard file not found: ${file.absolutePath}")
            }
            val raw = file.readText(Charsets.UTF_8)
            val wrapper = json.decodeFromString<DashboardFileWrapper>(raw)
            if (wrapper.version > FORMAT_VERSION) {
                throw IOException(
                    "Dashboard file version ${wrapper.version} is newer than supported version $FORMAT_VERSION"
                )
            }
            wrapper.config
        }

    /**
     * List all saved dashboard ids (without `.json` extension).
     *
     * @param context Android context.
     * @return [Result.success] with the list of ids (may be empty),
     *         or [Result.failure] if the directory can't be read.
     */
    fun listDashboards(context: Context): Result<List<String>> =
        runCatching {
            val dir = File(context.filesDir, DIR_NAME)
            if (!dir.exists() || !dir.isDirectory) {
                return@runCatching emptyList()
            }
            dir.listFiles()
                ?.filter { it.isFile && it.extension == "json" }
                ?.mapNotNull { file ->
                    // Read just enough to extract the id from the JSON
                    extractIdFromFile(file)
                }
                ?: emptyList()
        }

    /**
     * Delete a dashboard configuration file.
     *
     * @param context Android context.
     * @param id      The dashboard id to delete.
     * @return [Result.success] on delete (even if the file didn't exist),
     *         or [Result.failure] on I/O error.
     */
    fun deleteDashboard(context: Context, id: String): Result<Unit> =
        runCatching {
            val dir = File(context.filesDir, DIR_NAME)
            val file = File(dir, "${id.sanitizeFileName()}.json")
            if (file.exists()) {
                if (!file.delete()) {
                    throw IOException("Failed to delete dashboard file: ${file.absolutePath}")
                }
            }
        }

    /**
     * Export a dashboard config to a portable JSON string (not written to disk).
     * Useful for sharing / backup.
     */
    fun exportToString(config: DashboardConfig): String {
        val wrapper = DashboardFileWrapper(
            version = FORMAT_VERSION,
            config = config
        )
        return json.encodeToString(wrapper)
    }

    /**
     * Import a dashboard config from a JSON string.
     */
    fun importFromString(jsonString: String): Result<DashboardConfig> =
        runCatching {
            val wrapper = json.decodeFromString<DashboardFileWrapper>(jsonString)
            if (wrapper.version > FORMAT_VERSION) {
                throw IOException(
                    "Dashboard data version ${wrapper.version} is newer than supported version $FORMAT_VERSION"
                )
            }
            wrapper.config
        }

    // ------------------------------------------------------------------
    // Internal helpers
    // ------------------------------------------------------------------

    /**
     * Ensure the dashboard storage directory exists.
     */
    private fun ensureDir(context: Context): File {
        val dir = File(context.filesDir, DIR_NAME)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    /**
     * Extract the dashboard id from a JSON file without fully deserializing.
     * Falls back to the filename stem if parsing fails.
     */
    private fun extractIdFromFile(file: File): String? {
        return try {
            val raw = file.readText(Charsets.UTF_8)
            val element = json.parseToJsonElement(raw)
            // Try to read the "config"."id" field
            val configObj = element.jsonObject["config"]?.jsonObject ?: element.jsonObject
            configObj["id"]?.jsonPrimitive?.contentOrNull
                ?: file.nameWithoutExtension
        } catch (_: Exception) {
            // If we can't parse the JSON, use the filename
            file.nameWithoutExtension
        }
    }

    /**
     * Sanitize a string so it's safe to use as a filename component.
     * Strips or replaces characters that are illegal on common filesystems.
     */
    private fun String.sanitizeFileName(): String {
        return this
            .replace(Regex("[^a-zA-Z0-9_\\-]"), "_")
            .take(64) // reasonable filename length limit
    }

    // ------------------------------------------------------------------
    // Serialization wrapper
    // ------------------------------------------------------------------

    /**
     * Wrapper that adds a version header around the dashboard config.
     * This allows future format migrations without breaking existing files.
     */
    @kotlinx.serialization.Serializable
    private data class DashboardFileWrapper(
        val version: Int = FORMAT_VERSION,
        val config: DashboardConfig
    )
}
