package com.ztune.libretune.core

import android.content.Context
import android.net.Uri
import com.ztune.libretune.core.ecu.EcuInterface
import com.ztune.libretune.core.ini.EcuDefinition
import com.ztune.libretune.core.ini.types.Constant
import com.ztune.libretune.core.ini.types.CurveDefinition
import com.ztune.libretune.core.ini.types.DataType
import com.ztune.libretune.core.ini.types.TableDefinition
import com.ztune.libretune.core.realtime.RealtimeDecoder
import com.ztune.libretune.core.tune.ByteOrderWriter
import com.ztune.libretune.core.tune.ConstantManifestEntry
import com.ztune.libretune.core.tune.IniMetadata
import com.ztune.libretune.core.tune.Tune
import com.ztune.libretune.core.tune.TuneSerializer

/**
 * Manages the current tune: loading from ECU memory or files, applying edits,
 * tracking dirty state, and saving back to files.
 *
 * All mutations (updateConstant, updateTableCell, updateCurveValue) encode the
 * display value back into raw bytes in the page data buffer and mark the tune
 * as dirty. The [RealtimeDecoder] handles the encode/decode transform.
 */
class TuneManager(
    private val context: Context,
    private val decoder: RealtimeDecoder
) {
    // ----------------------------------------------------------------------
    // State
    // ----------------------------------------------------------------------

    /** The currently loaded tune (null until a tune is read). */
    var currentTune: Tune? = null
        private set

    /** The ECU definition that governs the current tune's layout. */
    var activeDefinition: EcuDefinition? = null
        private set

    /** Whether the tune has unsaved modifications since last load/save. */
    var isDirty: Boolean = false
        private set

    // ----------------------------------------------------------------------
    // Load from raw memory image
    // ----------------------------------------------------------------------

    /**
     * Parse a full ECU memory image into a [Tune] using the given [definition].
     *
     * The memory bytes are split into per-page buffers according to
     * [EcuDefinition.pageSizes], then every constant, table, and curve defined
     * in the INI is decoded via [RealtimeDecoder].
     */
    fun loadFromMemory(memoryBytes: ByteArray, definition: EcuDefinition) {
        activeDefinition = definition
        isDirty = false

        val pageData = splitIntoPages(memoryBytes, definition)
        val constantValues = mutableMapOf<String, Double>()
        val tableValues = mutableMapOf<String, List<List<Double>>>()
        val curveValues = mutableMapOf<String, List<Double>>()

        // Decode constants
        for ((name, constant) in definition.constants) {
            if (constant.isPcVariable) continue
            val pageBytes = pageData[constant.page] ?: continue
            constantValues[name] = decoder.decodeConstant(name, pageBytes, constant)
        }

        // Decode tables
        for ((name, table) in definition.tables) {
            val pageBytes = pageData[table.valuesPage] ?: pageData[table.page] ?: continue
            tableValues[name] = decoder.decodeTable(pageBytes, table)
        }

        // Decode curves
        for ((name, curve) in definition.curves) {
            val pageBytes = pageData[curve.valuesPage] ?: continue
            curveValues[name] = decoder.decodeCurve(pageBytes, curve)
        }

        // Build constant manifest for later validation
        val manifest = buildConstantManifest(definition)

        currentTune = Tune(
            iniMetadata = IniMetadata(
                signature = definition.signature,
                name = definition.versionInfo,
                hash = definition.computeStructuralHash(),
                specVersion = definition.iniSpecVersion
            ),
            constantManifest = manifest,
            pageData = pageData,
            constantValues = constantValues,
            tableValues = tableValues,
            curveValues = curveValues
        )
    }

    // ----------------------------------------------------------------------
    // Load from ECU (reads all pages, then delegates to loadFromMemory)
    // ----------------------------------------------------------------------

    /**
     * Read every page from the ECU via [ecu], then parse into a [Tune].
     *
     * @param ecu        The connected ECU interface.
     * @param definition The INI definition describing memory layout.
     * @param onProgress Callback receiving (currentPage, totalPages) as each
     *   page is read, suitable for progress-bar updates.
     */
    suspend fun loadFromEcu(
        ecu: EcuInterface,
        definition: EcuDefinition,
        onProgress: (Int, Int) -> Unit = { _, _ -> }
    ): Result<Unit> = runCatching {
        activeDefinition = definition
        val totalPages = definition.nPages.toInt() and 0xFF
        require(totalPages > 0) { "Definition has zero pages" }

        // Read all pages and concatenate into a single memory image
        val pageChunks = mutableListOf<ByteArray>()
        for (page in 0 until totalPages) {
            val pageSize = definition.pageSizes.getOrElse(page) { 0 }.toInt() and 0xFFFF
            require(pageSize > 0) { "Page $page has zero size" }

            val result = ecu.readBlock(page, 0, pageSize)
                .getOrElse { throw IllegalStateException(
                    "Failed to read page $page: ${it.message}", it
                ) }
            pageChunks.add(result)
            onProgress(page + 1, totalPages)
        }

        val memoryBytes = ByteArray(pageChunks.sumOf { it.size })
        var offset = 0
        for (chunk in pageChunks) {
            System.arraycopy(chunk, 0, memoryBytes, offset, chunk.size)
            offset += chunk.size
        }

        loadFromMemory(memoryBytes, definition)
    }

    // ----------------------------------------------------------------------
    // File I/O
    // ----------------------------------------------------------------------

    /**
     * Save the current tune to the file at [uri] via [TuneSerializer].
     *
     * After a successful save the dirty flag is cleared.
     *
     * @return [Result.success] or a descriptive failure.
     */
    fun saveToFile(uri: Uri): Result<Unit> {
        val tune = currentTune
            ?: return Result.failure(IllegalStateException("No tune loaded"))

        val result = TuneSerializer.save(context, uri, tune)
        if (result.isSuccess) isDirty = false
        return result
    }

    /**
     * Load a tune from the file at [uri] via [TuneSerializer].
     *
     * @return [Result.success] or a descriptive failure.
     */
    fun loadFromFile(uri: Uri): Result<Unit> {
        val result = TuneSerializer.load(context, uri)
            .getOrElse { return Result.failure(it) }

        currentTune = result
        isDirty = false
        activeDefinition = null // file loads don't carry a live definition
        return Result.success(Unit)
    }

    // ----------------------------------------------------------------------
    // Value accessors (delegate to the Tune object)
    // ----------------------------------------------------------------------

    /** Get a decoded constant value, or null if not present. */
    fun getConstantValue(name: String): Double? = currentTune?.getConstantValue(name)

    /** Get a single table cell value, or null if out of range. */
    fun getTableCell(tableName: String, row: Int, col: Int): Double? =
        currentTune?.getTableCell(tableName, row, col)

    /** Get a single curve value by index, or null if out of range. */
    fun getCurveValue(curveName: String, index: Int): Double? =
        currentTune?.getCurveValue(curveName, index)

    // ----------------------------------------------------------------------
    // Mutation: constants
    // ----------------------------------------------------------------------

    /**
     * Update a constant's display value, encode it back into page data.
     *
     * @param name  Constant name (must exist in the active definition).
     * @param value New display (scaled) value.
     * @throws IllegalStateException if no tune is loaded or the constant is
     *   unknown.
     */
    fun updateConstant(name: String, value: Double) {
        val tune = currentTune
            ?: throw IllegalStateException("No tune loaded")
        val def = activeDefinition
            ?: throw IllegalStateException("No active definition")
        val constant = def.constants[name]
            ?: throw IllegalArgumentException("Unknown constant: $name")

        val page = constant.page
        val pageBytes = tune.pageData[page]?.toMutableByteArray()
            ?: throw IllegalStateException("Page $page data not available")

        val encoded = decoder.encodeConstant(value, constant)
        require(encoded.size <= pageBytes.size - constant.offset) {
            "Encoded value overflows page bounds at offset ${constant.offset}"
        }
        System.arraycopy(encoded, 0, pageBytes, constant.offset, encoded.size)

        setPageData(page, pageBytes)
        tune.constantValues[name] = value
        isDirty = true
    }

    // ----------------------------------------------------------------------
    // Mutation: table cells
    // ----------------------------------------------------------------------

    /**
     * Update a single table cell, encode it back into page data.
     *
     * @param tableName Table name (must exist in the active definition).
     * @param row       Zero-based row index.
     * @param col       Zero-based column index.
     * @param value     New display (scaled) value.
     */
    fun updateTableCell(tableName: String, row: Int, col: Int, value: Double) {
        val tune = currentTune
            ?: throw IllegalStateException("No tune loaded")
        val def = activeDefinition
            ?: throw IllegalStateException("No active definition")
        val table = def.getTableByNameOrMap(tableName)
            ?: throw IllegalArgumentException("Unknown table: $tableName")

        require(row in 0 until table.rows) { "Row $row out of range [0, ${table.rows})" }
        require(col in 0 until table.cols) { "Col $col out of range [0, ${table.cols})" }

        val page = table.valuesPage
        val pageBytes = tune.pageData[page]?.toMutableByteArray()
            ?: throw IllegalStateException("Page $page data not available")

        val cellOffset = table.valuesOffset + (row * table.cols + col) * table.dataType.byteSize
        val raw = (value - table.translate) / table.scale
        val writer = ByteOrderWriter(table.dataType.byteSize, def.endianness)
        writer.writeValue(table.dataType, raw)
        val encoded = writer.toByteArray()

        require(cellOffset + encoded.size <= pageBytes.size) {
            "Encoded cell overflows page bounds at offset $cellOffset"
        }
        System.arraycopy(encoded, 0, pageBytes, cellOffset, encoded.size)

        setPageData(page, pageBytes)
        tune.setTableCell(tableName, row, col, value)
        isDirty = true
    }

    // ----------------------------------------------------------------------
    // Mutation: curves
    // ----------------------------------------------------------------------

    /**
     * Update a single curve value, encode it back into page data.
     *
     * @param curveName Curve name (must exist in the active definition).
     * @param index     Zero-based bin index.
     * @param value     New display (scaled) value.
     */
    fun updateCurveValue(curveName: String, index: Int, value: Double) {
        val tune = currentTune
            ?: throw IllegalStateException("No tune loaded")
        val def = activeDefinition
            ?: throw IllegalStateException("No active definition")
        val curve = def.getCurveByNameOrMap(curveName)
            ?: throw IllegalArgumentException("Unknown curve: $curveName")

        require(index in 0 until curve.size) {
            "Index $index out of range [0, ${curve.size})"
        }

        val page = curve.valuesPage
        val pageBytes = tune.pageData[page]?.toMutableByteArray()
            ?: throw IllegalStateException("Page $page data not available")

        val binOffset = curve.valuesOffset + index * curve.dataType.byteSize
        val raw = (value - curve.translate) / curve.scale
        val writer = ByteOrderWriter(curve.dataType.byteSize, def.endianness)
        writer.writeValue(curve.dataType, raw)
        val encoded = writer.toByteArray()

        require(binOffset + encoded.size <= pageBytes.size) {
            "Encoded curve value overflows page bounds at offset $binOffset"
        }
        System.arraycopy(encoded, 0, pageBytes, binOffset, encoded.size)

        setPageData(page, pageBytes)
        tune.setCurveValue(curveName, index, value)
        isDirty = true
    }

    // ----------------------------------------------------------------------
    // Page data helpers
    // ----------------------------------------------------------------------

    /** Get the raw byte array for a page, or null. */
    fun getPageData(page: Int): ByteArray? = currentTune?.getPageData(page)

    /** Replace the raw byte array for a page (immutable-map update). */
    private fun setPageData(page: Int, bytes: ByteArray) {
        val tune = currentTune ?: return
        val newData = tune.pageData.toMutableMap()
        newData[page] = bytes
        currentTune = tune.copy(pageData = newData)
    }

    // ----------------------------------------------------------------------
    // Deep copy
    // ----------------------------------------------------------------------

    /**
     * Return an independent deep copy of the current tune.
     *
     * The copy has its own page data byte arrays and value maps; mutations
     * to the copy do not affect the original.
     *
     * @return The cloned tune, or null if no tune is loaded.
     */
    fun deepCopy(): Tune? = currentTune?.deepCopy()

    // ----------------------------------------------------------------------
    // Reset
    // ----------------------------------------------------------------------

    /** Discard the current tune, clear dirty state, and release the definition. */
    fun reset() {
        currentTune = null
        activeDefinition = null
        isDirty = false
    }

    // ----------------------------------------------------------------------
    // Constant manifest
    // ----------------------------------------------------------------------

    /**
     * Build a [ConstantManifestEntry] list from the active definition's constants.
     *
     * The manifest captures each constant's name, data type, page, offset,
     * scale, and translate so that a saved tune can be validated against a
     * potentially different INI file when re-opened.
     */
    fun buildConstantManifest(definition: EcuDefinition): List<ConstantManifestEntry> {
        return definition.constants.values
            .filter { !it.isPcVariable }
            .map { c ->
                ConstantManifestEntry(
                    name = c.name,
                    dataType = c.dataType.name,
                    page = c.page,
                    offset = c.offset,
                    scale = c.scale,
                    translate = c.translate
                )
            }
    }

    /**
     * Validate the current tune's constant manifest against a definition.
     *
     * Returns a list of mismatch descriptions. An empty list means the tune
     * is fully compatible with the given definition.
     */
    fun validateManifest(definition: EcuDefinition): List<String> {
        val tune = currentTune ?: return emptyList()
        val issues = mutableListOf<String>()
        val tuneManifest = tune.constantManifest.associateBy { it.name }

        for ((name, constant) in definition.constants) {
            if (constant.isPcVariable) continue
            val entry = tuneManifest[name]
            if (entry == null) {
                issues.add("Constant '$name' exists in INI but not in tune manifest")
                continue
            }
            if (entry.dataType != constant.dataType.name) {
                issues.add("$name: data type mismatch (tune=${entry.dataType}, ini=${constant.dataType.name})")
            }
            if (entry.page != constant.page) {
                issues.add("$name: page mismatch (tune=${entry.page}, ini=${constant.page})")
            }
            if (entry.offset != constant.offset) {
                issues.add("$name: offset mismatch (tune=${entry.offset}, ini=${constant.offset})")
            }
        }

        // Check for constants in tune that no longer exist in the INI
        for (name in tuneManifest.keys) {
            if (name !in definition.constants) {
                issues.add("Constant '$name' in tune manifest but missing from INI")
            }
        }

        return issues
    }

    // ----------------------------------------------------------------------
    // Private helpers
    // ----------------------------------------------------------------------

    /**
     * Split a contiguous memory image into per-page byte arrays.
     */
    private fun splitIntoPages(
        memoryBytes: ByteArray,
        definition: EcuDefinition
    ): Map<Int, ByteArray> {
        val totalPages = definition.nPages.toInt() and 0xFF
        val pages = mutableMapOf<Int, ByteArray>()
        var offset = 0

        for (page in 0 until totalPages) {
            val pageSize = definition.pageSizes.getOrElse(page) { 0 }.toInt() and 0xFFFF
            if (pageSize <= 0) continue
            val end = (offset + pageSize).coerceAtMost(memoryBytes.size)
            if (offset >= memoryBytes.size) break
            pages[page] = memoryBytes.copyOfRange(offset, end)
            offset += pageSize
        }

        return pages
    }

    /** Shorthand to create a mutable copy of a byte array. */
    private fun ByteArray.toMutableByteArray(): ByteArray = this.copyOf()
}
