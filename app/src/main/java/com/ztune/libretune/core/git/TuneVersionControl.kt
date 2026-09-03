package com.ztune.libretune.core.git

import android.content.Context
import com.ztune.libretune.core.tune.Tune
import com.ztune.libretune.core.tune.TuneSerializer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Represents a single version-control commit for a [Tune].
 *
 * @property id       SHA-256 hash derived from tune content + metadata (unique per commit).
 * @property message  Human-readable commit message.
 * @property timestamp Unix epoch millis when the commit was created.
 * @property tuneHash SHA-256 hash of the serialized tune data (content-addressable key).
 */
data class TuneCommit(
    val id: String,
    val message: String,
    val timestamp: Long,
    val tuneHash: String,
)

/**
 * A single changed value between two tune snapshots.
 */
data class ChangedValue(
    val oldValue: String,
    val newValue: String,
)

/**
 * Result of diffing two [TuneCommit] instances.
 *
 * Each map is keyed by the constant / table / curve name and contains
 * the old and new serialised values.
 */
data class TuneDiffResult(
    val constants: Map<String, ChangedValue>,
    val tables: Map<String, ChangedValue>,
    val curves: Map<String, ChangedValue>,
) {
    /** True when at least one value differs between the two commits. */
    val hasChanges: Boolean
        get() = constants.isNotEmpty() || tables.isNotEmpty() || curves.isNotEmpty()

    /** Total number of individual changed entries across all categories. */
    val totalChanges: Int
        get() = constants.size + tables.size + curves.size
}

/**
 * Git-inspired version control for tunes using a simple content-addressable store.
 *
 * No actual `git` binary is required.  All storage is managed via JSON files
 * inside the app's private [Context.getFilesDir] under `tune_vc/`.
 *
 * ## Storage layout
 * ```
 * filesDir/tune_vc/
 * ├─ manifest.json          # Ordered list of commit metadata
 * └─ commits/
 *    ├─ <sha256-1>.json     # Full tune snapshot for commit 1
 *    └─ <sha256-2>.json     # Full tune snapshot for commit 2
 * ```
 *
 * @param context     Android context used to locate the private storage directory.
 * @param maxCommits  Maximum number of commits to retain (default 50).
 */
class TuneVersionControl(
    context: Context,
    private val maxCommits: Int = DEFAULT_MAX_COMMITS,
) {
    companion object {
        const val DEFAULT_MAX_COMMITS = 50
        private const val VC_DIR = "tune_vc"
        private const val COMMITS_SUBDIR = "commits"
        private const val MANIFEST_FILE = "manifest.json"
        private const val KEY_COMMITS = "commits"
        private const val KEY_ID = "id"
        private const val KEY_MESSAGE = "message"
        private const val KEY_TIMESTAMP = "timestamp"
        private const val KEY_TUNE_HASH = "tuneHash"
        private const val KEY_TUNE_DATA = "tuneData"
        private const val KEY_CONSTANTS = "constantValues"
        private const val KEY_TABLES = "tableValues"
        private const val KEY_CURVES = "curveValues"
        private const val DATE_FORMAT = "yyyy-MM-dd HH:mm:ss"
    }

    private val vcDir: File = File(context.filesDir, VC_DIR)
    private val commitsDir: File = File(vcDir, COMMITS_SUBDIR)
    private val manifestFile: File = File(vcDir, MANIFEST_FILE)

    init {
        commitsDir.mkdirs()
        if (!manifestFile.exists()) {
            writeManifest(JSONObject().apply { put(KEY_COMMITS, JSONArray()) })
        }
    }

    // ------------------------------------------------------------------ public

    /**
     * Save a snapshot of [tune] with the given [message].
     *
     * @return The newly created [TuneCommit].
     */
    suspend fun commit(tune: Tune, message: String): TuneCommit = withContext(Dispatchers.IO) {
        val tuneJson = TuneSerializer.serialize(tune)
        val tuneHash = sha256(tuneJson)
        val timestamp = System.currentTimeMillis()
        val commitId = sha256("$tuneHash|$message|$timestamp")

        val commitFile = File(commitsDir, "$commitId.json")
        commitFile.writeText(
            JSONObject().apply { put(KEY_TUNE_DATA, JSONObject(tuneJson)) }.toString()
        )

        val manifest = readManifest()
        manifest.getJSONArray(KEY_COMMITS).put(
            JSONObject().apply {
                put(KEY_ID, commitId)
                put(KEY_MESSAGE, message)
                put(KEY_TIMESTAMP, timestamp)
                put(KEY_TUNE_HASH, tuneHash)
            }
        )
        writeManifest(manifest)

        pruneOldCommits()

        TuneCommit(id = commitId, message = message, timestamp = timestamp, tuneHash = tuneHash)
    }

    /**
     * Return all commits sorted by timestamp (newest first).
     */
    suspend fun listCommits(): List<TuneCommit> = withContext(Dispatchers.IO) {
        val arr = readManifest().optJSONArray(KEY_COMMITS) ?: return@withContext emptyList()
        buildList {
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                add(
                    TuneCommit(
                        id = o.getString(KEY_ID),
                        message = o.getString(KEY_MESSAGE),
                        timestamp = o.getLong(KEY_TIMESTAMP),
                        tuneHash = o.getString(KEY_TUNE_HASH),
                    )
                )
            }
        }.sortedByDescending { it.timestamp }
    }

    /**
     * Retrieve a specific commit by its [id].
     */
    suspend fun getCommit(id: String): TuneCommit? = withContext(Dispatchers.IO) {
        listCommits().find { it.id == id }
    }

    /**
     * Retrieve a commit by its position in the newest-first list (0 = newest).
     */
    suspend fun getCommitAt(index: Int): TuneCommit? = withContext(Dispatchers.IO) {
        listCommits().getOrNull(index)
    }

    /**
     * Restore the [Tune] stored in the commit identified by [id].
     *
     * @return The deserialized [Tune], or `null` if the commit file does not exist.
     */
    suspend fun restoreCommit(id: String): Tune? = withContext(Dispatchers.IO) {
        val file = File(commitsDir, "$id.json")
        if (!file.exists()) return@withContext null
        val tuneData = JSONObject(file.readText()).opt(KEY_TUNE_DATA)?.toString() ?: return@withContext null
        TuneSerializer.deserialize(tuneData)
    }

    /**
     * Compute the difference between two commits.
     *
     * @param commitId1 First commit ID (typically the older one).
     * @param commitId2 Second commit ID (typically the newer one).
     * @return A [TuneDiffResult] containing changed constants, tables, and curves.
     */
    suspend fun diff(commitId1: String, commitId2: String): TuneDiffResult =
        withContext(Dispatchers.IO) {
            val empty = TuneDiffResult(emptyMap(), emptyMap(), emptyMap())
            val json1 = loadTuneJson(commitId1) ?: return@withContext empty
            val json2 = loadTuneJson(commitId2) ?: return@withContext empty

            val obj1 = JSONObject(json1)
            val obj2 = JSONObject(json2)

            TuneDiffResult(
                constants = computeSectionDiff(obj1, obj2, KEY_CONSTANTS),
                tables = computeSectionDiff(obj1, obj2, KEY_TABLES),
                curves = computeSectionDiff(obj1, obj2, KEY_CURVES),
            )
        }

    /**
     * Returns the configured maximum number of commits to retain.
     */
    fun getMaxCommits(): Int = maxCommits

    /**
     * Remove oldest commits so that only the most recent [maxCommits] remain.
     * Deleted commit files are removed from disk automatically.
     */
    suspend fun pruneOldCommits() = withContext(Dispatchers.IO) {
        val manifest = readManifest()
        val arr = manifest.optJSONArray(KEY_COMMITS) ?: return@withContext
        if (arr.length() <= maxCommits) return@withContext

        val entries = (0 until arr.length()).map { arr.getJSONObject(it) }
            .sortedByDescending { it.getLong(KEY_TIMESTAMP) }

        entries.drop(maxCommits).forEach { entry ->
            File(commitsDir, "${entry.getString(KEY_ID)}.json").delete()
        }

        val kept = JSONArray().also { arr2 ->
            entries.take(maxCommits).forEach { arr2.put(it) }
        }
        manifest.put(KEY_COMMITS, kept)
        writeManifest(manifest)
    }

    // -------------------------------------------------------------- utilities

    /** Format a Unix-epoch timestamp as a human-readable date/time string. */
    fun formatTimestamp(timestamp: Long): String =
        SimpleDateFormat(DATE_FORMAT, Locale.getDefault()).format(Date(timestamp))

    /** Return the first 8 hex characters of a full SHA-256 hash for display. */
    fun shortHash(fullHash: String): String =
        if (fullHash.length >= 8) fullHash.substring(0, 8) else fullHash

    // --------------------------------------------------------------- private

    private fun readManifest(): JSONObject = try {
        JSONObject(manifestFile.readText())
    } catch (_: Exception) {
        JSONObject().apply { put(KEY_COMMITS, JSONArray()) }
    }

    private fun writeManifest(manifest: JSONObject) {
        manifestFile.writeText(manifest.toString())
    }

    private fun loadTuneJson(commitId: String): String? {
        val file = File(commitsDir, "$commitId.json")
        if (!file.exists()) return null
        return JSONObject(file.readText()).opt(KEY_TUNE_DATA)?.toString()
    }

    /**
     * Compare a named section (constants / tables / curves) between two tune JSON
     * objects and return a map of changed entries.
     */
    private fun computeSectionDiff(
        obj1: JSONObject,
        obj2: JSONObject,
        section: String,
    ): Map<String, ChangedValue> {
        val result = mutableMapOf<String, ChangedValue>()
        val s1 = obj1.optJSONObject(section)
        val s2 = obj2.optJSONObject(section)

        val allKeys = linkedSetOf<String>().apply {
            s1?.keys()?.forEach { add(it) }
            s2?.keys()?.forEach { add(it) }
        }

        for (key in allKeys) {
            val v1 = s1?.opt(key)?.toString()
            val v2 = s2?.opt(key)?.toString()
            if (v1 != v2) {
                result[key] = ChangedValue(oldValue = v1 ?: "—", newValue = v2 ?: "—")
            }
        }
        return result
    }

    private fun sha256(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(input.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}
