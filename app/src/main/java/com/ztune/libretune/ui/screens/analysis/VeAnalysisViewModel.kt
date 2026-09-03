package com.ztune.libretune.ui.screens.analysis

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ztune.libretune.core.ini.EcuDefinition
import com.ztune.libretune.core.ini.types.TableDefinition
import com.ztune.libretune.core.ini.types.TableRole
import com.ztune.libretune.core.tune.Tune
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import javax.inject.Inject
import kotlin.math.abs
import kotlin.math.sqrt

// ---------------------------------------------------------------------------
// Enums & data classes
// ---------------------------------------------------------------------------

enum class AnalysisMode(val label: String) {
    VE("VE Table"),
    WUE("WUE Curve"),
    GAMMA_E("GammaE"),
}

enum class WeightingMode(val label: String) {
    EQUAL("Equal"),
    INVERSE_VARIANCE("Inv. Variance"),
    SAMPLE_COUNT("Sample Weight"),
}

enum class RejectionReason(val label: String) {
    CLT_BELOW_THRESHOLD("CLT below threshold"),
    TPS_RATE_EXCEEDED("TPS rate exceeded"),
    STEADY_TOO_SHORT("Steady state too short"),
    AFR_OUT_OF_RANGE("AFR out of range"),
    RPM_OUT_OF_RANGE("RPM out of range"),
    EGO_CORRECTION_HIGH("EGO correction too high"),
    MISSING_CHANNEL("Missing channel data"),
}

data class ChannelMapping(
    val rpm: String = "",
    val mapOrTps: String = "",
    val afr: String = "",
    val clt: String = "",
    val egoCorrection: String = "",
    val tpsRate: String = "",
)

data class AnalysisParams(
    val weightingMode: WeightingMode = WeightingMode.SAMPLE_COUNT,
    val baseWeight: Float = 1.0f,
    val minChangePct: Float = 0.5f,
    val minSteadyMs: Long = 200L,
    val cltThreshold: Int = 60,
    val tpsRateThreshold: Float = 80f,
    val maxChangePct: Float = 15f,
    val smoothingPasses: Int = 2,
    val targetAfr: Float = 14.7f,
    val afrTolerance: Float = 2.0f,
    val holdoutRatio: Float = 0.2f,
)

data class CellResult(
    val x: Int,
    val y: Int,
    val currentVe: Float,
    val proposedVe: Float,
    val delta: Float,
    val hits: Int,
    val weight: Float,
    val confidence: Float,
    val targetAfr: Float,
    val meanAfr: Float,
)

data class SampleRecord(
    val index: Int,
    val rpm: Float,
    val load: Float,
    val afr: Float,
    val clt: Float,
    val egoCorrection: Float,
    val tpsRate: Float,
    val rejectionReason: RejectionReason? = null,
)

data class AnalysisResults(
    val cells: List<CellResult> = emptyList(),
    val cellMap: Map<Pair<Int, Int>, CellResult> = emptyMap(),
    val coverageMap: Map<Pair<Int, Int>, Int> = emptyMap(),
    val totalSamples: Int = 0,
    val acceptedSamples: Int = 0,
    val rejectedSamples: Int = 0,
    val rejectionBreakdown: Map<RejectionReason, Int> = emptyMap(),
    val coveragePct: Float = 0f,
    val crossValidationScore: Float = 0f,
    val rowCount: Int = 0,
    val colCount: Int = 0,
)

data class VeAnalysisUiState(
    val mode: AnalysisMode = AnalysisMode.VE,
    val csvLoaded: Boolean = false,
    val csvFileName: String = "",
    val availableChannels: List<String> = emptyList(),
    val channelMapping: ChannelMapping = ChannelMapping(),
    val params: AnalysisParams = AnalysisParams(),
    val isAnalyzing: Boolean = false,
    val results: AnalysisResults? = null,
    val selectedCell: Pair<Int, Int>? = null,
    val error: String? = null,
    val tableDefinition: TableDefinition? = null,
)

// ---------------------------------------------------------------------------
// Channel alias map: common alternate names -> canonical field
// ---------------------------------------------------------------------------

private val RPM_ALIASES = setOf(
    "rpm", "RPM", "engineRpm", "EngineRPM", "RPMs", "rpmChannel",
    "secRPM", "Rpm", "revolutionsPerMinute",
)

private val MAP_ALIASES = setOf(
    "map", "MAP", "kpa", "KPA", "manifoldPressure", "MAPkPa",
    "MAPValue", "mapKpa", "boostPsi", "BAP", "baroKpa",
)

private val TPS_ALIASES = setOf(
    "tps", "TPS", "throttle", "ThrottlePosition", "TPSMain",
    "tpsADC", "throttlePos", "TPSPct",
)

private val AFR_ALIASES = setOf(
    "afr", "AFR", "lambda", "Lambda", "egoAFR", "o2",
    "wideband", "wbAFR", "afrChannel", "AFR1", "WB1",
    "egoLambda", "O2_1",
)

private val CLT_ALIASES = setOf(
    "clt", "CLT", "coolant", "coolantTemp", "CLTValue",
    "coolantTempC", "engineTemp", "CLT_deg",
)

private val EGO_CORR_ALIASES = setOf(
    "egoCorrection", "EGOCorrection", "ego", "EGO", "closedLoop",
    "egoCorr", "STFT", "stft", "closedLoopFuel",
    "egoPct", "fuelTrim",
)

private val TPS_RATE_ALIASES = setOf(
    "tpsRate", "TPSRate", "tpsDOT", "TPSDot", "tpsRatePct",
    "throttleRate", "accelEnrich", "tpsAccel",
)

// ---------------------------------------------------------------------------
// ViewModel
// ---------------------------------------------------------------------------

@HiltViewModel
class VeAnalysisViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val _uiState = MutableStateFlow(VeAnalysisUiState())
    val uiState = _uiState.asStateFlow()

    private var rawRows: List<Map<String, String>> = emptyList()

    fun setMode(mode: AnalysisMode) {
        _uiState.value = _uiState.value.copy(mode = mode, results = null)
    }

    fun updateChannelMapping(mapping: ChannelMapping) {
        _uiState.value = _uiState.value.copy(channelMapping = mapping)
    }

    fun updateParams(block: (AnalysisParams) -> AnalysisParams) {
        _uiState.value = _uiState.value.copy(params = block(_uiState.value.params))
    }

    // ---- CSV loading -------------------------------------------------------

    fun loadCsv(uri: Uri) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isAnalyzing = true, error = null)
            try {
                val result = withContext(Dispatchers.IO) {
                    parseCsv(uri)
                }
                rawRows = result.second
                _uiState.value = _uiState.value.copy(
                    csvLoaded = true,
                    csvFileName = uri.lastPathSegment ?: "datalog.csv",
                    availableChannels = result.first,
                    channelMapping = autoMapChannels(result.first),
                    isAnalyzing = false,
                    results = null,
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isAnalyzing = false,
                    error = "Failed to load CSV: ${e.localizedMessage}",
                )
            }
        }
    }

    private fun parseCsv(uri: Uri): Pair<List<String>, List<Map<String, String>>> {
        val inputStream = context.contentResolver.openInputStream(uri)
            ?: throw IllegalArgumentException("Cannot open file")
        val reader = BufferedReader(InputStreamReader(inputStream))
        val headerLine = reader.readLine()
            ?: throw IllegalArgumentException("CSV file is empty")
        val headers = parseCsvLine(headerLine)
        val rows = mutableListOf<Map<String, String>>()
        reader.forEachLine { line ->
            if (line.isBlank()) return@forEachLine
            val values = parseCsvLine(line)
            val row = mutableMapOf<String, String>()
            headers.forEachIndexed { idx, h ->
                row[h] = values.getOrNull(idx)?.trim() ?: ""
            }
            rows.add(row)
        }
        reader.close()
        return headers to rows
    }

    private fun parseCsvLine(line: String): List<String> {
        val result = mutableListOf<String>()
        var current = StringBuilder()
        var inQuotes = false
        for (ch in line) {
            when {
                ch == '"' -> inQuotes = !inQuotes
                ch == ',' && !inQuotes -> {
                    result.add(current.toString().trim())
                    current = StringBuilder()
                }
                else -> current.append(ch)
            }
        }
        result.add(current.toString().trim())
        return result
    }

    // ---- Alias resolution ---------------------------------------------------

    private fun autoMapChannels(channels: List<String>): ChannelMapping {
        val chSet = channels.toSet()
        return ChannelMapping(
            rpm = resolveChannel(chSet, RPM_ALIASES) ?: "",
            mapOrTps = resolveChannel(chSet, MAP_ALIASES) ?: resolveChannel(chSet, TPS_ALIASES) ?: "",
            afr = resolveChannel(chSet, AFR_ALIASES) ?: "",
            clt = resolveChannel(chSet, CLT_ALIASES) ?: "",
            egoCorrection = resolveChannel(chSet, EGO_CORR_ALIASES) ?: "",
            tpsRate = resolveChannel(chSet, TPS_RATE_ALIASES) ?: "",
        )
    }

    private fun resolveChannel(
        available: Set<String>,
        aliases: Set<String>,
    ): String? {
        val lowerMap = available.associateBy { it.lowercase() }
        for (alias in aliases) {
            if (lowerMap.containsKey(alias.lowercase())) return lowerMap[alias.lowercase()]
        }
        for (ch in available) {
            for (alias in aliases) {
                if (ch.contains(alias, ignoreCase = true)) return ch
            }
        }
        return null
    }

    // ---- Run analysis -------------------------------------------------------

    fun runAnalysis(tune: Tune?, definition: EcuDefinition?) {
        val state = _uiState.value
        val mapping = state.channelMapping
        val params = state.params
        _uiState.value = state.copy(isAnalyzing = true, error = null)

        viewModelScope.launch {
            try {
                val results = withContext(Dispatchers.Default) {
                    performAnalysis(tune, definition, mapping, params)
                }
                _uiState.value = _uiState.value.copy(isAnalyzing = false, results = results)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isAnalyzing = false,
                    error = "Analysis failed: ${e.localizedMessage}",
                )
            }
        }
    }

    private fun performAnalysis(
        tune: Tune?,
        definition: EcuDefinition?,
        mapping: ChannelMapping,
        params: AnalysisParams,
    ): AnalysisResults {
        val samples = extractAndFilterSamples(mapping, params)
        if (samples.isEmpty()) {
            return AnalysisResults(totalSamples = rawRows.size)
        }

        val tableDef = resolveTable(definition) ?: return AnalysisResults(
            totalSamples = rawRows.size,
            acceptedSamples = samples.size,
            rejectedSamples = rawRows.size - samples.size,
        )
        _uiState.value = _uiState.value.copy(tableDefinition = tableDef)

        val rowCount = tableDef.rows
        val colCount = tableDef.cols
        val xBins = extractAxisBins(tune, definition, tableDef, isXAxis = true)
        val yBins = extractAxisBins(tune, definition, tableDef, isXAxis = false)
        val currentTable = extractCurrentTable(tune, tableDef)

        val shuffled = samples.shuffled()
        val holdoutCount = (shuffled.size * params.holdoutRatio).toInt()
        val trainingSamples = shuffled.drop(holdoutCount)
        val holdoutSamples = shuffled.take(holdoutCount)

        val cellAccum = buildCellAccumulators(
            trainingSamples, xBins, yBins, currentTable, params,
        )
        val smoothed = applySmoothing(cellAccum, params.smoothingPasses, rowCount, colCount)
        val cellResults = buildCellResults(smoothed, currentTable, xBins, yBins, params)

        val cvScore = computeCrossValidation(cellResults, holdoutSamples, xBins, yBins)
        val cellMap = cellResults.associateBy { it.x to it.y }
        val coverageMap = buildCoverageMap(samples, xBins, yBins, rowCount, colCount)
        val totalCells = rowCount * colCount
        val coveredCells = coverageMap.values.count { it > 0 }
        val accepted = samples.size
        val rejected = rawRows.size - samples.size
        val rejectionBreakdown = buildRejectionBreakdown(mapping, params)

        return AnalysisResults(
            cells = cellResults,
            cellMap = cellMap,
            coverageMap = coverageMap,
            totalSamples = rawRows.size,
            acceptedSamples = accepted,
            rejectedSamples = rejected,
            rejectionBreakdown = rejectionBreakdown,
            coveragePct = if (totalCells > 0) coveredCells.toFloat() / totalCells * 100f else 0f,
            crossValidationScore = cvScore,
            rowCount = rowCount,
            colCount = colCount,
        )
    }

    private fun extractAndFilterSamples(
        mapping: ChannelMapping,
        params: AnalysisParams,
    ): List<SampleRecord> {
        val results = mutableListOf<SampleRecord>()
        rawRows.forEachIndexed { idx, row ->
            val rpmVal = row[mapping.rpm]?.toFloatOrNull()
            val loadVal = row[mapping.mapOrTps]?.toFloatOrNull()
            val afrVal = row[mapping.afr]?.toFloatOrNull()
            val cltVal = row[mapping.clt]?.toFloatOrNull()
            val egoVal = row[mapping.egoCorrection]?.toFloatOrNull() ?: 0f
            val tpsRateVal = row[mapping.tpsRate]?.toFloatOrNull() ?: 0f

            if (rpmVal == null || loadVal == null || afrVal == null || cltVal == null) {
                return@forEachIndexed
            }

            val rejection = when {
                cltVal < params.cltThreshold -> RejectionReason.CLT_BELOW_THRESHOLD
                tpsRateVal > params.tpsRateThreshold -> RejectionReason.TPS_RATE_EXCEEDED
                abs(egoVal) > 25f -> RejectionReason.EGO_CORRECTION_HIGH
                afrVal < params.targetAfr - params.afrTolerance ||
                    afrVal > params.targetAfr + params.afrTolerance -> RejectionReason.AFR_OUT_OF_RANGE
                else -> null
            }

            results.add(
                SampleRecord(
                    index = idx,
                    rpm = rpmVal,
                    load = loadVal,
                    afr = afrVal,
                    clt = cltVal,
                    egoCorrection = egoVal,
                    tpsRate = tpsRateVal,
                    rejectionReason = rejection,
                ),
            )
        }
        return results.filter { it.rejectionReason == null }
    }

    private fun resolveTable(definition: EcuDefinition?): TableDefinition? {
        val mode = _uiState.value.mode
        val role = when (mode) {
            AnalysisMode.VE -> TableRole.VE
            AnalysisMode.WUE -> TableRole.WARMUP_ENRICHMENT
            AnalysisMode.GAMMA_E -> TableRole.VE
        }
        return definition?.tables?.values?.firstOrNull { it.role == role }
    }

    private fun extractAxisBins(
        tune: Tune?,
        definition: EcuDefinition?,
        tableDef: TableDefinition,
        isXAxis: Boolean,
    ): List<Double> {
        val axisName = if (isXAxis) tableDef.xAxis?.binsName else tableDef.yAxis?.binsName
        val size = if (isXAxis) tableDef.cols else tableDef.rows
        if (axisName == null || tune == null) return (0 until size).map { it.toDouble() }
        val axisDef = definition?.curves?.values?.firstOrNull { it.name == axisName }
        if (axisDef != null) {
            return tune.curveValues[axisName]?.take(size) ?: (0 until size).map { it.toDouble() }
        }
        return (0 until size).map { it.toDouble() }
    }

    private fun extractCurrentTable(
        tune: Tune?,
        tableDef: TableDefinition,
    ): List<List<Double>> {
        return tune?.tableValues?.get(tableDef.name)
            ?: (0 until tableDef.rows).map { row ->
                (0 until tableDef.cols).map { 100.0 }
            }
    }

    private data class CellAccumulator(
        var sumCorrection: Float = 0f,
        var sumWeight: Float = 0f,
        var sumAfr: Float = 0f,
        var hits: Int = 0,
        var sumAfrSq: Float = 0f,
    )

    private fun buildCellAccumulators(
        samples: List<SampleRecord>,
        xBins: List<Double>,
        yBins: List<Double>,
        currentTable: List<List<Double>>,
        params: AnalysisParams,
    ): Array<Array<CellAccumulator>> {
        val rows = currentTable.size
        val cols = if (rows > 0) currentTable[0].size else 0
        val accum = Array(rows) { Array(cols) { CellAccumulator() } }

        for (sample in samples) {
            val colIdx = findBinIndex(xBins, sample.load.toDouble())
            val rowIdx = findBinIndex(yBins, sample.rpm.toDouble())
            if (rowIdx < 0 || rowIdx >= rows || colIdx < 0 || colIdx >= cols) continue

            val currentVe = currentTable[rowIdx][colIdx].toFloat()
            if (currentVe <= 0f) continue

            val afrError = params.targetAfr - sample.afr
            val correction = afrError / sample.afr
            val weight = computeWeight(sample, params)

            accum[rowIdx][colIdx].let { cell ->
                cell.sumCorrection += correction * weight
                cell.sumWeight += weight
                cell.sumAfr += sample.afr * weight
                cell.sumAfrSq += sample.afr * sample.afr * weight
                cell.hits++
            }
        }
        return accum
    }

    private fun findBinIndex(bins: List<Double>, value: Double): Int {
        if (bins.isEmpty()) return -1
        if (value <= bins.first()) return 0
        for (i in 1 until bins.size) {
            if (value <= bins[i]) {
                val prev = bins[i - 1]
                val curr = bins[i]
                return if (value - prev <= curr - value) i - 1 else i
            }
        }
        return bins.size - 1
    }

    private fun computeWeight(sample: SampleRecord, params: AnalysisParams): Float {
        val base = params.baseWeight
        return when (params.weightingMode) {
            WeightingMode.EQUAL -> base
            WeightingMode.INVERSE_VARIANCE -> {
                val variance = abs(sample.afr - params.targetAfr)
                if (variance < 0.01f) base else base / variance
            }
            WeightingMode.SAMPLE_COUNT -> base
        }
    }

    private fun applySmoothing(
        accum: Array<Array<CellAccumulator>>,
        passes: Int,
        rows: Int,
        cols: Int,
    ): Array<Array<CellAccumulator>> {
        var current = accum.map { row -> row.map { it.copy() }.toTypedArray() }.toTypedArray()
        repeat(passes) {
            val smoothed = Array(rows) { r ->
                Array(cols) { c ->
                    var sumCorr = 0f
                    var sumW = 0f
                    for (dr in -1..1) {
                        for (dc in -1..1) {
                            val nr = r + dr
                            val nc = c + dc
                            if (nr in 0 until rows && nc in 0 until cols && current[nr][nc].hits > 0) {
                                val w = if (dr == 0 && dc == 0) 4f else 1f
                                val cell = current[nr][nc]
                                val cellCorr = if (cell.sumWeight > 0f) cell.sumCorrection / cell.sumWeight else 0f
                                sumCorr += cellCorr * w
                                sumW += w
                            }
                        }
                    }
                    if (sumW > 0f) {
                        val avgCorr = sumCorr / sumW
                        val cell = current[r][c]
                        cell.copy(sumCorrection = avgCorr * cell.sumWeight)
                    } else {
                        current[r][c]
                    }
                }
            }
            current = smoothed
        }
        return current
    }

    private fun buildCellResults(
        accum: Array<Array<CellAccumulator>>,
        currentTable: List<List<Double>>,
        xBins: List<Double>,
        yBins: List<Double>,
        params: AnalysisParams,
    ): List<CellResult> {
        val results = mutableListOf<CellResult>()
        for (r in accum.indices) {
            for (c in accum[r].indices) {
                val cell = accum[r][c]
                if (cell.hits == 0) continue
                val rawCorrection = cell.sumCorrection / cell.sumWeight
                val clampedCorrection = rawCorrection.coerceIn(
                    -params.maxChangePct / 100f,
                    params.maxChangePct / 100f,
                )
                val currentVe = currentTable.getOrNull(r)?.getOrNull(c)?.toFloat() ?: continue
                val proposedVe = currentVe * (1f + clampedCorrection)
                val meanAfr = cell.sumAfr / cell.sumWeight
                val afrVariance = cell.sumAfrSq / cell.sumWeight - meanAfr * meanAfr
                val afrStdDev = sqrt(afrVariance.coerceAtLeast(0f))
                val confidence = (1f - (afrStdDev / meanAfr).coerceIn(0f, 1f))

                results.add(
                    CellResult(
                        x = c,
                        y = r,
                        currentVe = currentVe,
                        proposedVe = proposedVe,
                        delta = proposedVe - currentVe,
                        hits = cell.hits,
                        weight = cell.sumWeight,
                        confidence = confidence,
                        targetAfr = params.targetAfr,
                        meanAfr = meanAfr,
                    ),
                )
            }
        }
        return results
    }

    private fun computeCrossValidation(
        cellResults: List<CellResult>,
        holdoutSamples: List<SampleRecord>,
        xBins: List<Double>,
        yBins: List<Double>,
    ): Float {
        if (holdoutSamples.isEmpty() || cellResults.isEmpty()) return 0f
        val cellMap = cellResults.associateBy { it.x to it.y }
        var totalError = 0f
        var count = 0
        for (sample in holdoutSamples) {
            val colIdx = findBinIndex(xBins, sample.load.toDouble())
            val rowIdx = findBinIndex(yBins, sample.rpm.toDouble())
            val cell = cellMap[colIdx to rowIdx] ?: continue
            val predictedAfr = cell.targetAfr * (cell.currentVe / cell.proposedVe.coerceAtLeast(1f))
            val error = abs(predictedAfr - sample.afr)
            totalError += error
            count++
        }
        return if (count > 0) (1f - (totalError / count) / cellResults.first().targetAfr).coerceIn(0f, 1f) else 0f
    }

    private fun buildCoverageMap(
        samples: List<SampleRecord>,
        xBins: List<Double>,
        yBins: List<Double>,
        rows: Int,
        cols: Int,
    ): Map<Pair<Int, Int>, Int> {
        val coverage = mutableMapOf<Pair<Int, Int>, Int>()
        for (sample in samples) {
            val colIdx = findBinIndex(xBins, sample.load.toDouble())
            val rowIdx = findBinIndex(yBins, sample.rpm.toDouble())
            if (rowIdx in 0 until rows && colIdx in 0 until cols) {
                val key = colIdx to rowIdx
                coverage[key] = (coverage[key] ?: 0) + 1
            }
        }
        return coverage
    }

    private fun buildRejectionBreakdown(
        mapping: ChannelMapping,
        params: AnalysisParams,
    ): Map<RejectionReason, Int> {
        val breakdown = mutableMapOf<RejectionReason, Int>()
        for (row in rawRows) {
            val cltVal = row[mapping.clt]?.toFloatOrNull()
            if (cltVal != null && cltVal < params.cltThreshold) {
                breakdown[RejectionReason.CLT_BELOW_THRESHOLD] =
                    (breakdown[RejectionReason.CLT_BELOW_THRESHOLD] ?: 0) + 1
                continue
            }
            val tpsRateVal = row[mapping.tpsRate]?.toFloatOrNull() ?: 0f
            if (tpsRateVal > params.tpsRateThreshold) {
                breakdown[RejectionReason.TPS_RATE_EXCEEDED] =
                    (breakdown[RejectionReason.TPS_RATE_EXCEEDED] ?: 0) + 1
                continue
            }
            val egoVal = row[mapping.egoCorrection]?.toFloatOrNull() ?: 0f
            if (abs(egoVal) > 25f) {
                breakdown[RejectionReason.EGO_CORRECTION_HIGH] =
                    (breakdown[RejectionReason.EGO_CORRECTION_HIGH] ?: 0) + 1
                continue
            }
            val afrVal = row[mapping.afr]?.toFloatOrNull()
            if (afrVal != null && (afrVal < params.targetAfr - params.afrTolerance ||
                    afrVal > params.targetAfr + params.afrTolerance)) {
                breakdown[RejectionReason.AFR_OUT_OF_RANGE] =
                    (breakdown[RejectionReason.AFR_OUT_OF_RANGE] ?: 0) + 1
                continue
            }
            if (row[mapping.rpm]?.toFloatOrNull() == null ||
                row[mapping.mapOrTps]?.toFloatOrNull() == null ||
                afrVal == null ||
                cltVal == null
            ) {
                breakdown[RejectionReason.MISSING_CHANNEL] =
                    (breakdown[RejectionReason.MISSING_CHANNEL] ?: 0) + 1
            }
        }
        return breakdown
    }

    // ---- Apply recommendations ---------------------------------------------

    fun applyRecommendations(tune: Tune?, onResult: (Boolean) -> Unit) {
        val results = _uiState.value.results ?: return onResult(false)
        val tableDef = _uiState.value.tableDefinition ?: return onResult(false)
        if (tune == null) return onResult(false)

        for (cell in results.cells) {
            if (cell.confidence < 0.2f || abs(cell.delta) < _uiState.value.params.minChangePct) continue
            tune.setTableCell(tableDef.name, cell.y, cell.x, cell.proposedVe.toDouble())
        }
        onResult(true)
    }

    fun selectCell(x: Int, y: Int) {
        _uiState.value = _uiState.value.copy(selectedCell = x to y)
    }

    fun clearSelection() {
        _uiState.value = _uiState.value.copy(selectedCell = null)
    }

    fun clearResults() {
        _uiState.value = _uiState.value.copy(results = null, selectedCell = null)
    }
}
