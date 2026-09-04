package com.ztune.libretune.core.ai

import com.ztune.libretune.core.ini.EcuDefinition
import com.ztune.libretune.core.ini.types.TableDefinition
import com.ztune.libretune.core.ini.types.TableRole

/**
 * Phase 27: AI Assistant Architecture.
 *
 * Provides an abstraction for AI-powered ECU tuning assistance.
 * Inspired by LibreTune's AI agent system but adapted for Android.
 *
 * Key principles:
 * - AI can READ tables, realtime data, and datalogs
 * - AI can PROPOSE changes with authority limits
 * - AI can NEVER burn to ECU automatically
 * - All proposals require explicit user approval
 * - Restore point is created before any AI-proposed apply
 *
 * Provider abstraction allows multiple LLM backends
 * (OpenAI, Anthropic, Google, local Ollama, etc.).
 */

// ------------------------------------------------------------------
// Capability tiers — conservative by default
// ------------------------------------------------------------------

enum class CapabilityTier(val displayName: String) {
    READ_ONLY("Read Only"),
    TUNE("Tune (Propose Only)"),
    CONFIG("Config (Full Propose)");

    fun allows(toolName: String): Boolean {
        if (toolName.startsWith("read_") || toolName.startsWith("list_") ||
            toolName.startsWith("summarize_") || toolName.startsWith("get_") ||
            toolName.startsWith("query_") || toolName.startsWith("tune_")) return true
        return when (this) {
            READ_ONLY -> false
            TUNE -> toolName in setOf("propose_table_edit", "propose_bulk_operation")
            CONFIG -> toolName in setOf("propose_table_edit", "propose_bulk_operation", "propose_constant_change")
        }
    }
}

// ------------------------------------------------------------------
// Authority limits — bound AI proposals to safe ranges
// ------------------------------------------------------------------

data class AuthorityLimits(
    val maxCellChangeAbs: Double = 10.0,
    val maxCellChangePct: Double = 20.0,
    val minCellValue: Double = 0.0,
    val maxCellValue: Double = 255.0
) {
    fun clamp(value: Double, original: Double): Double {
        val absDelta = (value - original).coerceIn(-maxCellChangeAbs, maxCellChangeAbs)
        val pctDelta = absDelta.coerceIn(-original * maxCellChangePct / 100, original * maxCellChangePct / 100)
        return (original + pctDelta).coerceIn(minCellValue, maxCellValue)
    }
}

// ------------------------------------------------------------------
// Proposed actions — what the AI wants to do
// ------------------------------------------------------------------

sealed class ProposedAction {
    abstract val reasoning: String

    data class TableEdit(
        val tableName: String,
        val row: Int,
        val col: Int,
        val proposedValue: Double,
        val originalValue: Double,
        override val reasoning: String
    ) : ProposedAction()

    data class ConstantChange(
        val constantName: String,
        val proposedValue: Double,
        val originalValue: Double,
        override val reasoning: String
    ) : ProposedAction()

    data class Analysis(
        val summary: String,
        val issues: List<String>,
        val recommendations: List<String>,
        override val reasoning: String
    ) : ProposedAction()
}

data class Proposal(
    val reply: String,
    val proposedActions: List<ProposedAction>,
    val allValid: Boolean,
    val warnings: List<String>
)

// ------------------------------------------------------------------
// Read-only context that AI can access
// ------------------------------------------------------------------

data class EcuContext(
    val signature: String,
    val tables: Map<String, TableInfo>,
    val realtimeSnapshot: Map<String, Double>,
    val datalogSummary: DatalogSummary? = null
)

data class TableInfo(
    val name: String,
    val title: String,
    val role: TableRole,
    val rows: Int,
    val cols: Int,
    val units: String,
    val values: List<List<Double>>?
)

data class DatalogSummary(
    val duration: Long,
    val sampleCount: Int,
    val channelStats: Map<String, ChannelStats>
)

data class ChannelStats(
    val min: Double,
    val max: Double,
    val avg: Double,
    val last: Double
)

// ------------------------------------------------------------------
// Provider trait — pluggable LLM backend
// ------------------------------------------------------------------

interface AiProvider {
    val name: String

    suspend fun analyze(
        context: EcuContext,
        userMessage: String,
        capabilityTier: CapabilityTier,
        authority: AuthorityLimits
    ): Result<Proposal>

    fun isConfigured(): Boolean
}

// ------------------------------------------------------------------
// Stub provider — for when no API key is configured
// ------------------------------------------------------------------

class StubAiProvider : AiProvider {
    override val name = "Stub (no API key)"

    override suspend fun analyze(
        context: EcuContext,
        userMessage: String,
        capabilityTier: CapabilityTier,
        authority: AuthorityLimits
    ): Result<Proposal> {
        return Result.success(Proposal(
            reply = "AI assistant is not configured. Set an API key in Settings to enable.\n\n" +
                "Available tables: ${context.tables.size}\n" +
                "Realtime channels: ${context.realtimeSnapshot.size}",
            proposedActions = emptyList(),
            allValid = true,
            warnings = listOf("AI provider not configured — running in stub mode")
        ))
    }

    override fun isConfigured(): Boolean = false
}

// ------------------------------------------------------------------
// AI Assistant Manager — orchestrates provider, validation, safety
// ------------------------------------------------------------------

/**
 * Manages AI assistant interactions with safety guarantees.
 *
 * Flow:
 * 1. User asks question
 * 2. Manager builds EcuContext from live state
 * 3. Provider analyzes and returns Proposal
 * 4. Manager validates each ProposedAction against INI definition
 * 5. Manager applies AuthorityLimits to clamp proposed values
 * 6. User reviews and approves/rejects
 * 7. On approve: create restore point, apply changes (NO auto-burn)
 */
class AiAssistantManager(
    private val provider: AiProvider = StubAiProvider()
) {
    var capabilityTier: CapabilityTier = CapabilityTier.READ_ONLY
    var authorityLimits: AuthorityLimits = AuthorityLimits()

    /**
     * Send a user message to the AI and get a validated proposal back.
     */
    suspend fun ask(
        context: EcuContext,
        userMessage: String
    ): Result<Proposal> {
        if (!provider.isConfigured()) {
            return Result.success(Proposal(
                reply = "AI assistant not configured. Please set an API key in Settings.",
                proposedActions = emptyList(),
                allValid = false,
                warnings = listOf("Provider not configured")
            ))
        }

        val result = provider.analyze(context, userMessage, capabilityTier, authorityLimits)
        val proposal = result.getOrElse { e ->
            return Result.failure(e)
        }

        // Validate and clamp proposed actions
        val validatedActions = proposal.proposedActions.map { action ->
            validateAndClamp(action, context)
        }

        val warnings = proposal.warnings.toMutableList()
        val allValid = validatedActions.all { it !is ProposedAction.Analysis || it.reasoning.isNotEmpty() }

        return Result.success(proposal.copy(
            proposedActions = validatedActions,
            allValid = allValid,
            warnings = warnings
        ))
    }

    /**
     * Validate a proposed action against the ECU context and apply authority limits.
     */
    private fun validateAndClamp(action: ProposedAction, context: EcuContext): ProposedAction {
        return when (action) {
            is ProposedAction.TableEdit -> {
                val table = context.tables[action.tableName] ?: return action
                // Check bounds
                if (action.row !in 0 until table.rows || action.col !in 0 until table.cols) {
                    return action.copy(proposedValue = action.originalValue) // reject by setting to original
                }
                // Apply authority limits
                val clamped = authorityLimits.clamp(action.proposedValue, action.originalValue)
                action.copy(proposedValue = clamped)
            }
            is ProposedAction.ConstantChange -> action // constants are validated at apply time
            is ProposedAction.Analysis -> action
        }
    }

    /**
     * Check if the provider is configured and ready.
     */
    fun isAvailable(): Boolean = provider.isConfigured()
}
