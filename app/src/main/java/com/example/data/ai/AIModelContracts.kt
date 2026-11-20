package com.example.data.ai

import com.example.data.remote.BidiFunctionDeclaration
import com.example.domain.model.ScreenSightError
import kotlinx.coroutines.flow.Flow

/**
 * Identifies the inference provider handling an AI operation.
 */
enum class AIProviderId(val displayName: String, val badgeLabel: String) {
    GOOGLE_GEMINI_LIVE("Google Gemini Live (WebSocket)", "Gemini Live"),
    GOOGLE_GEMMA_REST("Google Gemini API — Gemma", "Gemma Reasoning"),
    GOOGLE_GEMINI_REST("Google Gemini API — Text", "Fallback Model"),
    LOCAL_FAST_PATH("On-Device Deterministic Engine", "Local Engine")
}

/**
 * Controls when ScreenSight AI routes non-live text tasks to cloud reasoning models.
 */
enum class ReasoningRoutingMode(
    val shortLabel: String,
    val description: String
) {
    AUTO_COMPLEX(
        shortLabel = "Auto Complex",
        description = "Uses Gemma for multi-step planning, decomposition, recovery & complex questions; runs trivial commands locally."
    ),
    ALWAYS_GEMMA_TEXT(
        shortLabel = "Always Gemma",
        description = "Routes all non-live text requests through Gemma when API key is configured."
    ),
    LOCAL_ONLY(
        shortLabel = "Local Only",
        description = "Disables cloud text reasoning; uses on-device planner only."
    )
}

/**
 * Logical stage of the autonomous agent pipeline requesting model reasoning.
 */
enum class AIReasoningTaskStage(val displayLabel: String) {
    TASK_PLANNING("Task Planning"),
    TASK_DECOMPOSITION("Goal Decomposition"),
    TOOL_SELECTION("Tool Selection"),
    VERIFICATION_ASSIST("Outcome Verification"),
    ERROR_RECOVERY("Error Recovery"),
    MEMORY_INTELLIGENCE("Memory Relevance"),
    SKILL_INTELLIGENCE("Skill Proposal"),
    MULTI_AGENT_PLANNER("Multi-Agent Planner"),
    MULTI_AGENT_RESEARCH("Multi-Agent Research"),
    MULTI_AGENT_VERIFIER("Multi-Agent Verifier"),
    GENERAL_TEXT_REASONING("Text Reasoning")
}

/**
 * Explicit verified capabilities for a model descriptor.
 * Never assume unsupported capabilities (e.g., Gemma does NOT support Gemini Live BidiGenerateContent audio/screen).
 */
data class AIModelCapabilities(
    val supportsLiveAudio: Boolean = false,
    val supportsScreenFrames: Boolean = false,
    val supportsStreaming: Boolean = true,
    val supportsNativeToolCalling: Boolean = true,
    val supportsSystemInstruction: Boolean = true,
    val supportsStructuredJson: Boolean = true,
    val supportsThinking: Boolean = false,
    val maxInputTokens: Int = 262_144,
    val maxOutputTokens: Int = 32_768
)

/**
 * Describes a verified or discovered AI model accessible via the configured provider.
 */
data class AIModelDescriptor(
    val id: String,
    val displayName: String,
    val providerId: AIProviderId,
    val familyBadge: String,
    val description: String,
    val capabilities: AIModelCapabilities,
    val isVerifiedCatalogModel: Boolean = true,
    val requiresSameGeminiApiKey: Boolean = true
) {
    val fullResourceName: String
        get() = if (id.startsWith("models/")) id else "models/$id"

    val shortId: String
        get() = fullResourceName.removePrefix("models/")
}

/**
 * Authoritative catalog of verified Gemma & Gemini REST reasoning models exposed by
 * Google's Gemini Developer API (`https://generativelanguage.googleapis.com/v1beta`)
 * using the SAME existing Google AI Studio API key.
 *
 * Verification note:
 * - "Gemma 3 31B" does NOT exist. The official 31B Gemma model is Gemma 4 31B IT (`models/gemma-4-31b-it`).
 * - Gemma 3's largest instruction-tuned variant is `models/gemma-3-27b-it` (27B), which does not
 *   expose native model-level function calling or native `systemInstruction` on the Gemini API and
 *   therefore uses prompt-level JSON fallback when selected.
 */
object VerifiedReasoningModels {
    val GEMMA_4_31B_IT = AIModelDescriptor(
        id = "models/gemma-4-31b-it",
        displayName = "Gemma 4 31B IT",
        providerId = AIProviderId.GOOGLE_GEMMA_REST,
        familyBadge = "Primary Reasoning • Dense 31B • Native Tool Calling",
        description = "Official Google Gemma 4 31B dense reasoning model on Gemini Developer API. Supports native function calling, system instructions, structured JSON, thinking, and 256K context using your existing Gemini API key.",
        capabilities = AIModelCapabilities(
            supportsLiveAudio = false,
            supportsScreenFrames = false,
            supportsStreaming = true,
            supportsNativeToolCalling = true,
            supportsSystemInstruction = true,
            supportsStructuredJson = true,
            supportsThinking = true,
            maxInputTokens = 262_144,
            maxOutputTokens = 32_768
        ),
        isVerifiedCatalogModel = true
    )

    val GEMMA_4_26B_A4B_IT = AIModelDescriptor(
        id = "models/gemma-4-26b-a4b-it",
        displayName = "Gemma 4 26B-A4B MoE IT",
        providerId = AIProviderId.GOOGLE_GEMMA_REST,
        familyBadge = "Fast Reasoning • 26B MoE (4B Active) • Native Tool Calling",
        description = "High-throughput Mixture-of-Experts Gemma 4 model on Gemini Developer API. Low latency for agent planning and native tool calling with 256K context.",
        capabilities = AIModelCapabilities(
            supportsLiveAudio = false,
            supportsScreenFrames = false,
            supportsStreaming = true,
            supportsNativeToolCalling = true,
            supportsSystemInstruction = true,
            supportsStructuredJson = true,
            supportsThinking = true,
            maxInputTokens = 262_144,
            maxOutputTokens = 32_768
        ),
        isVerifiedCatalogModel = true
    )

    val GEMINI_2_5_FLASH = AIModelDescriptor(
        id = "models/gemini-2.5-flash",
        displayName = "Gemini 2.5 Flash (Text Fallback)",
        providerId = AIProviderId.GOOGLE_GEMINI_REST,
        familyBadge = "Verified Fallback • Fast Structured Reasoning",
        description = "Google Gemini 2.5 Flash REST model used as a reliable fallback when Gemma 4 is rate-limited or unavailable on your API tier.",
        capabilities = AIModelCapabilities(
            supportsLiveAudio = false,
            supportsScreenFrames = false,
            supportsStreaming = true,
            supportsNativeToolCalling = true,
            supportsSystemInstruction = true,
            supportsStructuredJson = true,
            supportsThinking = true,
            maxInputTokens = 1_048_576,
            maxOutputTokens = 65_536
        ),
        isVerifiedCatalogModel = true
    )

    val GEMMA_3_27B_IT = AIModelDescriptor(
        id = "models/gemma-3-27b-it",
        displayName = "Gemma 3 27B IT (Legacy JSON Fallback)",
        providerId = AIProviderId.GOOGLE_GEMMA_REST,
        familyBadge = "Gemma 3 27B • Prompt JSON Fallback Only",
        description = "Previous-generation Gemma 3 27B open model. Does not support native tool declarations or systemInstruction on Gemini API; ScreenSight automatically uses constrained JSON fallback.",
        capabilities = AIModelCapabilities(
            supportsLiveAudio = false,
            supportsScreenFrames = false,
            supportsStreaming = true,
            supportsNativeToolCalling = false,
            supportsSystemInstruction = false,
            supportsStructuredJson = false,
            supportsThinking = false,
            maxInputTokens = 131_072,
            maxOutputTokens = 8_192
        ),
        isVerifiedCatalogModel = true
    )

    val DEFAULT_PRIMARY = GEMMA_4_31B_IT
    val DEFAULT_FALLBACK = GEMMA_4_26B_A4B_IT

    val ALL_VERIFIED_REASONING_MODELS: List<AIModelDescriptor> = listOf(
        GEMMA_4_31B_IT,
        GEMMA_4_26B_A4B_IT,
        GEMINI_2_5_FLASH,
        GEMMA_3_27B_IT
    )

    fun isGemmaModelId(modelId: String): Boolean {
        val clean = modelId.trim().lowercase().removePrefix("models/")
        return clean.startsWith("gemma-")
    }

    /**
     * Normalizes known official aliases cleanly without ever inventing non-existent model IDs.
     * Note: Rejects/remaps the non-existent "gemma-3-31b" misconception to the real verified `models/gemma-4-31b-it`.
     */
    fun normalizeReasoningModelId(rawInput: String): String {
        val trimmed = rawInput.trim()
        if (trimmed.isEmpty()) return DEFAULT_PRIMARY.id
        val lower = trimmed.lowercase().removePrefix("models/")
        return when (lower) {
            "gemma 4 31b", "gemma-4-31b", "gemma-4-31b-it", "gemma 4 31b it" -> GEMMA_4_31B_IT.id
            "gemma 4 26b", "gemma-4-26b-a4b", "gemma-4-26b-a4b-it", "gemma 4 26b-a4b" -> GEMMA_4_26B_A4B_IT.id
            "gemini 2.5 flash", "gemini-2.5-flash" -> GEMINI_2_5_FLASH.id
            "gemma 3 27b", "gemma-3-27b", "gemma-3-27b-it" -> GEMMA_3_27B_IT.id
            else -> if (trimmed.startsWith("models/")) trimmed else "models/$trimmed"
        }
    }

    fun findByIdOrCustom(modelId: String): AIModelDescriptor {
        val normalized = normalizeReasoningModelId(modelId)
        return ALL_VERIFIED_REASONING_MODELS.firstOrNull {
            it.id.equals(normalized, ignoreCase = true)
        } ?: run {
            val isGemma = isGemmaModelId(normalized)
            val isGemma3 = normalized.lowercase().contains("gemma-3-")
            AIModelDescriptor(
                id = normalized,
                displayName = normalized.removePrefix("models/"),
                providerId = if (isGemma) AIProviderId.GOOGLE_GEMMA_REST else AIProviderId.GOOGLE_GEMINI_REST,
                familyBadge = if (isGemma) "Discovered / Custom Gemma Model" else "Discovered / Custom Gemini Text Model",
                description = "Model configured on Google Gemini Developer API (v1beta generateContent).",
                capabilities = AIModelCapabilities(
                    supportsLiveAudio = false,
                    supportsScreenFrames = false,
                    supportsStreaming = true,
                    supportsNativeToolCalling = !isGemma3,
                    supportsSystemInstruction = !isGemma3,
                    supportsStructuredJson = !isGemma3,
                    supportsThinking = normalized.lowercase().contains("gemma-4") || normalized.lowercase().contains("2.5")
                ),
                isVerifiedCatalogModel = false
            )
        }
    }
}

data class AIReasoningRequest(
    val taskStage: AIReasoningTaskStage,
    val userPrompt: String,
    val systemInstruction: String = "",
    val tools: List<BidiFunctionDeclaration> = emptyList(),
    val expectJsonOutput: Boolean = false,
    val temperature: Double = 0.2,
    val maxOutputTokens: Int = 2048,
    val timeoutMs: Long = 25_000L
)

data class AIProposedToolCall(
    val callId: String,
    val toolName: String,
    val arguments: Map<String, String>,
    val fromNativeFunctionCall: Boolean = true
)

data class AITokenUsage(
    val promptTokens: Int = 0,
    val candidatesTokens: Int = 0,
    val thoughtTokens: Int = 0,
    val totalTokens: Int = 0
)

data class AIReasoningResponse(
    val success: Boolean,
    val modelId: String,
    val providerId: AIProviderId,
    val taskStage: AIReasoningTaskStage,
    val text: String = "",
    val proposedToolCalls: List<AIProposedToolCall> = emptyList(),
    val tokenUsage: AITokenUsage = AITokenUsage(),
    val latencyMs: Long = 0L,
    val usedFallback: Boolean = false,
    val fallbackReason: String? = null,
    val error: ScreenSightError? = null,
    val httpStatusCode: Int? = null
)

data class AIStreamChunk(
    val deltaText: String = "",
    val proposedToolCalls: List<AIProposedToolCall> = emptyList(),
    val tokenUsage: AITokenUsage? = null,
    val isDone: Boolean = false,
    val error: ScreenSightError? = null
)

data class ModelAvailabilityReport(
    val modelId: String,
    val providerId: AIProviderId,
    val isAvailable: Boolean,
    val supportedMethods: List<String> = emptyList(),
    val inputTokenLimit: Int? = null,
    val outputTokenLimit: Int? = null,
    val statusMessage: String,
    val error: ScreenSightError? = null
)

/**
 * Strictly non-sensitive usage telemetry shown in Task Details and Model Status UI.
 * NEVER stores user prompts, private screen contents, or API credentials.
 */
data class ModelUsageRecord(
    val modelId: String,
    val modelDisplayName: String,
    val providerId: AIProviderId,
    val taskStage: AIReasoningTaskStage,
    val succeeded: Boolean,
    val usedFallback: Boolean = false,
    val fallbackNote: String? = null,
    val promptTokens: Int = 0,
    val outputTokens: Int = 0,
    val latencyMs: Long = 0L,
    val timestampMillis: Long = System.currentTimeMillis()
) {
    fun toCompactCheckpointTag(): String {
        val status = if (succeeded) "OK" else "FALLBACK"
        val fb = if (usedFallback) " [Fallback]" else ""
        return "Model: $modelDisplayName (${providerId.badgeLabel}$fb • ${taskStage.displayLabel} • $status)"
    }
}

/**
 * Active model badge displayed cleanly in Agent Console and Home screens.
 */
data class ActiveModelIndicator(
    val activeLabel: String = "Gemma 4 31B IT",
    val modeBadge: String = "Gemma Reasoning",
    val providerId: AIProviderId = AIProviderId.GOOGLE_GEMMA_REST,
    val modelId: String = VerifiedReasoningModels.DEFAULT_PRIMARY.id,
    val isUsingFallback: Boolean = false,
    val lastUsage: ModelUsageRecord? = null
)

/**
 * Clean abstraction for text/reasoning AI providers alongside Gemini Live.
 */
interface AIModelProvider {
    val providerId: AIProviderId
    val displayName: String

    fun supportsModel(modelId: String): Boolean
    fun describeModel(modelId: String): AIModelDescriptor

    suspend fun generate(
        apiKey: String,
        modelId: String,
        request: AIReasoningRequest
    ): AIReasoningResponse

    fun streamGenerate(
        apiKey: String,
        modelId: String,
        request: AIReasoningRequest
    ): Flow<AIStreamChunk>

    suspend fun checkModelAvailability(
        apiKey: String,
        modelId: String
    ): ModelAvailabilityReport
}
