package com.ztune.libretune.core.tune

import android.content.Context
import android.net.Uri
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.IOException
import java.util.Base64

/**
 * JSON serialization for [Tune] using `kotlinx.serialization`.
 *
 * Page data is Base64-encoded to keep the JSON valid UTF-8 text.
 * The wire format is a single JSON object:
 * ```json
 * {
 *   "iniMetadata": { "signature": "...", "name": "...", ... },
 *   "constantManifest": [...],
 *   "pages": { "0": "<base64>", "1": "<base64>" },
 *   "constantValues": { "reqFuel": 6.5, "nCylinders": 4.0 },
 *   "tableValues": { "veTable": [[1.0,2.0],[3.0,4.0]] },
 *   "curveValues": { "dwellCurve": [3.0,3.5,4.0] },
 *   "projectInfo": { "name": "My Tune", ... }
 * }
 * ```
 *
 * Usage:
 * ```kotlin
 * val json = TuneSerializer.serialize(tune)
 * val tune = TuneSerializer.deserialize(json)
 * ```
 */
object TuneSerializer {

    private val json = Json { prettyPrint = false; encodeDefaults = true; ignoreUnknownKeys = true }

    // ======================================================================
    // Public API
    // ======================================================================

    /** Serialize a [Tune] to a JSON string. */
    fun serialize(tune: Tune): String {
        val dto = TuneDto.fromDomain(tune)
        return json.encodeToString(dto)
    }

    /** Deserialize a JSON string back to a [Tune]. */
    fun deserialize(jsonString: String): Tune {
        val dto = json.decodeFromString<TuneDto>(jsonString)
        return dto.toDomain()
    }

    // ------------------------------------------------------------------
    // Android file I/O helpers (Uri-based)
    // ------------------------------------------------------------------

    /**
     * Serialize [tune] and write it to the document/blob at [uri] via the
     * [Context.getContentResolver] SAF stream.
     *
     * @return [Result.success] on completion, or a failure wrapping the I/O
     *   / serialization exception.
     */
    fun save(context: Context, uri: Uri, tune: Tune): Result<Unit> = runCatching {
        val jsonString = serialize(tune)
        val resolver = context.contentResolver
        val out = resolver.openOutputStream(uri, "wt")
            ?: throw IOException("Cannot open output stream for URI: $uri")
        out.use { it.write(jsonString.toByteArray(Charsets.UTF_8)) }
    }

    /**
     * Read the document/blob at [uri] and deserialize it back to a [Tune].
     *
     * @return [Result.success] with the parsed [Tune], or a failure wrapping
     *   the I/O / deserialization exception.
     */
    fun load(context: Context, uri: Uri): Result<Tune> = runCatching {
        val resolver = context.contentResolver
        val inp = resolver.openInputStream(uri)
            ?: throw IOException("Cannot open input stream for URI: $uri")
        val jsonString = inp.use { it.readBytes().toString(Charsets.UTF_8) }
        deserialize(jsonString)
    }

    // ======================================================================
    // DTOs
    // ======================================================================

    @Serializable
    data class IniMetadataDto(
        val signature: String = "",
        val name: String = "",
        val hash: String = "",
        val specVersion: String = "",
        val savedAt: String = ""
    ) {
        fun toDomain() = IniMetadata(signature, name, hash, specVersion, savedAt)
        companion object {
            fun fromDomain(m: IniMetadata) = IniMetadataDto(m.signature, m.name, m.hash, m.specVersion, m.savedAt)
        }
    }

    @Serializable
    data class ConstantManifestEntryDto(
        val name: String = "",
        val dataType: String = "",
        val page: Int = 0,
        val offset: Int = 0,
        val scale: Double = 1.0,
        val translate: Double = 0.0
    ) {
        fun toDomain() = ConstantManifestEntry(name, dataType, page, offset, scale, translate)
        companion object {
            fun fromDomain(e: ConstantManifestEntry) = ConstantManifestEntryDto(
                e.name, e.dataType, e.page, e.offset, e.scale, e.translate
            )
        }
    }

    @Serializable
    data class ProjectInfoDto(
        val name: String = "",
        val description: String = "",
        val createdAt: Long = 0L,
        val modifiedAt: Long = 0L,
        val notes: String = ""
    ) {
        fun toDomain() = ProjectInfo(name, description, createdAt, modifiedAt, notes)
        companion object {
            fun fromDomain(p: ProjectInfo?) = p?.let {
                ProjectInfoDto(it.name, it.description, it.createdAt, it.modifiedAt, it.notes)
            }
        }
    }

    @Serializable
    data class TuneDto(
        val iniMetadata: IniMetadataDto = IniMetadataDto(),
        val constantManifest: List<ConstantManifestEntryDto> = emptyList(),
        /** Page number (as string key) -> Base64-encoded page data. */
        val pages: Map<String, String> = emptyMap(),
        val constantValues: Map<String, Double> = emptyMap(),
        /** Table name -> 2D array of doubles. */
        val tableValues: Map<String, List<List<Double>>> = emptyMap(),
        val curveValues: Map<String, List<Double>> = emptyMap(),
        val projectInfo: ProjectInfoDto? = null
    ) {
        fun toDomain(): Tune {
            val pageData = mutableMapOf<Int, ByteArray>()
            for ((key, b64) in pages) {
                pageData[key.toIntOrNull() ?: continue] = decodeBase64(b64)
            }
            return Tune(
                iniMetadata = iniMetadata.toDomain(),
                constantManifest = constantManifest.map { it.toDomain() },
                pageData = pageData,
                constantValues = constantValues.toMutableMap(),
                tableValues = tableValues.mapValues { (_, v) -> v.map { it.toList() } }.toMutableMap(),
                curveValues = curveValues.toMutableMap(),
                projectInfo = projectInfo?.toDomain()
            )
        }

        companion object {
            fun fromDomain(tune: Tune): TuneDto {
                val pagesEncoded = mutableMapOf<String, String>()
                for ((pageNum, data) in tune.pageData) {
                    pagesEncoded[pageNum.toString()] = encodeBase64(data)
                }
                return TuneDto(
                    iniMetadata = IniMetadataDto.fromDomain(tune.iniMetadata),
                    constantManifest = tune.constantManifest.map { ConstantManifestEntryDto.fromDomain(it) },
                    pages = pagesEncoded,
                    constantValues = tune.constantValues,
                    tableValues = tune.tableValues.mapValues { (_, v) -> v.map { it.toList() } },
                    curveValues = tune.curveValues.mapValues { (_, v) -> v.toList() },
                    projectInfo = ProjectInfoDto.fromDomain(tune.projectInfo)
                )
            }
        }
    }

    // ======================================================================
    // Base64 helpers
    // ======================================================================

    /** Encode raw bytes to a standard Base64 string (no line breaks). */
    private fun encodeBase64(data: ByteArray): String =
        Base64.getEncoder().encodeToString(data)

    /** Decode a Base64 string to raw bytes. Supports standard and URL-safe. */
    private fun decodeBase64(encoded: String): ByteArray {
        val trimmed = encoded.trim()
        return try {
            Base64.getDecoder().decode(trimmed)
        } catch (_: IllegalArgumentException) {
            Base64.getUrlDecoder().decode(trimmed)
        }
    }
}
