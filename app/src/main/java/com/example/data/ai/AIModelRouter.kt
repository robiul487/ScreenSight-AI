package com.example.data.ai

import com.example.data.local.ApiKeyStorage
import com.example.domain.model.AppSettings
import com.example.domain.model.ScreenSightError
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import java.util.concurrent.ConcurrentHashMap

/**
 * Central AI Model Router coordinating real-time Gemini Live alongside Gemma 4 text reasoning.
 *
 * Routing Invariants (Part 5):
 * 1. Live voice & screen sharing ALWAYS stay on Gemini Live (`SessionManager` / `GeminiLiveRepository`).
 * 2. Trivial deterministic local commands (e.g., check time, open app, read screen) use the
 *    local fast-path so no unnecessary API calls are made.
 * 3. Complex multi-step text tasks, planning, decomposition, recovery, memory & skill reasoning
 *    route to the configured Gemma reasoning model (`models/gemma-4-31b-it` by default).
 * 4. Single-request discipline: NEVER sends the same request simultaneously to both Gemma & Gemini.
 * 5. Graceful fallback: if the primary reasoning model is unavailable, rate-limited, or errors,
 *    falls back cleanly to `reasoningFallbackModelId` (if distinct) and records non-sensitive usage telemetry.
 */
class AIModelRouter(
    private val apiKeyStorage: ApiKeyStorage,
    private val gemmaProvider: AIModelProvider,
    private val geminiTextProvider: AIModelProvider,
    private val settingsProvider: () -> AppSettings = { AppSettings() }
) {
    private val _activeModelIndicator = MutableStateFlow(ActiveModelIndicator())
    val activeModelIndicator: StateFlow<ActiveModelIndicator> = _activeModelIndicator.asStateFlow()

    private val _recentUsageHistory = MutableStateFlow<List<ModelUsageRecord>>(emptyList())
    val recentUsageHistory: StateFlow<List<ModelUsageRecord>> = _recentUsageHistory.asStateFlow()

    private val taskUsageById = ConcurrentHashMap<String, ModelUsageRecord>()

    private val _taskModelUsageMap = MutableStateFlow<Map<String, ModelUsageRecord>>(emptyMap())
    val taskModelUsageMap: StateFlow<Map<String, ModelUsageRecord>> = _taskModelUsageMap.asStateFlow()

    fun syncIndicatorWithSettings(settings: AppSettings, isLiveSessionActive: Boolean = false) {
        if (isLiveSessionActive) {
            _activeModelIndicator.update { prev ->
                prev.copy(
                    activeLabel = com.example.domain.model.GeminiLiveModel.findByIdOrCustom(settings.selectedModelId).displayName,
                    modeBadge = AIProviderId.GOOGLE_GEMINI_LIVE.badgeLabel,
                    providerId = AIProviderId.GOOGLE_GEMINI_LIVE,
                    modelId = settings.selectedModelId,
                    isUsingFallback = false
                )
            }
            return
        }
        if (!settings.gemmaReasoningEnabled || settings.reasoningRoutingMode == ReasoningRoutingMode.LOCAL_ONLY) {
            _activeModelIndicator.update { prev ->
                prev.copy(
                    activeLabel = "Local Planner",
                    modeBadge = AIProviderId.LOCAL_FAST_PATH.badgeLabel,
                    providerId = AIProviderId.LOCAL_FAST_PATH,
                    modelId = "local",
                    isUsingFallback = false
                )
            }
            return
        }
        val descriptor = VerifiedReasoningModels.findByIdOrCustom(settings.reasoningModelId)
        _activeModelIndicator.update { prev ->
            prev.copy(
                activeLabel = descriptor.displayName,
                modeBadge = descriptor.providerId.badgeLabel,
                providerId = descriptor.providerId,
                modelId = descriptor.fullResourceName,
                isUsingFallback = false
            )
        }
    }

    /**
     * Decides whether a text task should call the cloud reasoning model or stay on the local fast-path.
     * Prevents wasting API quota on trivial deterministic local actions.
     */
    fun shouldUseCloudReasoning(
        userGoal: String,
        stage: AIReasoningTaskStage,
        settings: AppSettings = settingsProvider()
    ): Boolean {
        if (!settings.gemmaReasoningEnabled) return false
        if (settings.reasoningRoutingMode == ReasoningRoutingMode.LOCAL_ONLY) return false
        if (userGoal.isBlank()) return false

        if (settings.reasoningRoutingMode == ReasoningRoutingMode.ALWAYS_GEMMA_TEXT) {
            return true
        }

        // Stages that inherently represent complex reasoning or recovery always benefit from Gemma
        if (stage == AIReasoningTaskStage.ERROR_RECOVERY ||
            stage == AIReasoningTaskStage.VERIFICATION_ASSIST ||
            stage == AIReasoningTaskStage.MEMORY_INTELLIGENCE ||
            stage == AIReasoningTaskStage.SKILL_INTELLIGENCE
        ) {
            return true
        }

        // AUTO_COMPLEX: keep trivial 1-step deterministic local commands off the network
        return !isTrivialDeterministicLocalCommand(userGoal)
    }

    /**
     * Routes a reasoning request to the primary reasoning model (Gemma 4 by default),
     * falling back cleanly to `reasoningFallbackModelId` only if the primary call fails.
     * Never dispatches duplicate parallel requests for the same prompt.
     */
    suspend fun reason(
        request: AIReasoningRequest,
        taskIdForTelemetry: String? = null
    ): AIReasoningResponse {
        val settings = settingsProvider()
        if (!settings.gemmaReasoningEnabled || settings.reasoningRoutingMode == ReasoningRoutingMode.LOCAL_ONLY) {
            return AIReasoningResponse(
                success = false,
                modelId = "local",
                providerId = AIProviderId.LOCAL_FAST_PATH,
                taskStage = request.taskStage,
                error = ScreenSightError.WebSocketFailure("Cloud reasoning is disabled in settings; using local planner.")
            )
        }

        val apiKey = apiKeyStorage.getApiKey().trim()
        if (apiKey.isEmpty()) {
            return AIReasoningResponse(
                success = false,
                modelId = settings.reasoningModelId,
                providerId = AIProviderId.GOOGLE_GEMMA_REST,
                taskStage = request.taskStage,
                error = ScreenSightError.MissingApiKey
            )
        }

        val effectiveRequest = request.copy(
            timeoutMs = (settings.reasoningTimeoutSec * 1_000L).coerceIn(5_000L, 90_000L)
        )

        val primaryModelId = VerifiedReasoningModels.normalizeReasoningModelId(settings.reasoningModelId)
        val primaryProvider = resolveProviderForModel(primaryModelId)
        val primaryResponse = primaryProvider.generate(apiKey, primaryModelId, effectiveRequest)

        if (primaryResponse.success) {
            recordTelemetry(primaryResponse, taskIdForTelemetry)
            return primaryResponse
        }

        // Never fallback on invalid/missing API key (same key would fail on fallback too)
        val primaryErr = primaryResponse.error
        if (primaryErr is ScreenSightError.InvalidApiKey || primaryErr is ScreenSightError.MissingApiKey) {
            recordTelemetry(primaryResponse, taskIdForTelemetry)
            return primaryResponse
        }

        val fallbackModelId = VerifiedReasoningModels.normalizeReasoningModelId(settings.reasoningFallbackModelId)
        if (fallbackModelId.equals(primaryModelId, ignoreCase = true)) {
            recordTelemetry(primaryResponse, taskIdForTelemetry)
            return primaryResponse
        }

        val fallbackProvider = resolveProviderForModel(fallbackModelId)
        val fallbackRaw = fallbackProvider.generate(apiKey, fallbackModelId, effectiveRequest)
        val fallbackReason = "${VerifiedReasoningModels.findByIdOrCustom(primaryModelId).displayName} unavailable (${primaryErr?.title ?: "Error"})"
        val finalResponse = fallbackRaw.copy(
            usedFallback = true,
            fallbackReason = fallbackReason
        )
        recordTelemetry(finalResponse, taskIdForTelemetry)
        return finalResponse
    }

    /**
     * Streams a reasoning response from the configured primary reasoning model.
     */
    suspend fun streamReason(request: AIReasoningRequest): Flow<AIStreamChunk> {
        val settings = settingsProvider()
        if (!settings.gemmaReasoningEnabled || settings.reasoningRoutingMode == ReasoningRoutingMode.LOCAL_ONLY) {
            return flowOf(
                AIStreamChunk(
                    isDone = true,
                    error = ScreenSightError.WebSocketFailure("Cloud reasoning disabled; using local engine.")
                )
            )
        }
        val apiKey = apiKeyStorage.getApiKey().trim()
        if (apiKey.isEmpty()) {
            return flowOf(AIStreamChunk(isDone = true, error = ScreenSightError.MissingApiKey))
        }
        val primaryModelId = VerifiedReasoningModels.normalizeReasoningModelId(settings.reasoningModelId)
        val provider = resolveProviderForModel(primaryModelId)
        return provider.streamGenerate(
            apiKey = apiKey,
            modelId = primaryModelId,
            request = request.copy(timeoutMs = (settings.reasoningTimeoutSec * 1_000L).coerceIn(5_000L, 90_000L))
        )
    }

    /**
     * Real model availability test reusing the single encrypted API key (or a candidate key entered in UI).
     */
    suspend fun testModelAvailability(
        candidateKeyInput: String = "",
        modelId: String = settingsProvider().reasoningModelId
    ): ModelAvailabilityReport {
        val keyToTest = candidateKeyInput.trim().ifEmpty { apiKeyStorage.getApiKey().trim() }
        val normalizedId = VerifiedReasoningModels.normalizeReasoningModelId(modelId)
        val provider = resolveProviderForModel(normalizedId)
        if (keyToTest.isEmpty()) {
            return ModelAvailabilityReport(
                modelId = normalizedId,
                providerId = provider.providerId,
                isAvailable = false,
                statusMessage = "Please save or enter your Google Gemini API key first.",
                error = ScreenSightError.MissingApiKey
            )
        }
        return provider.checkModelAvailability(keyToTest, normalizedId)
    }

    fun attachModelUsageToTask(taskId: String, record: ModelUsageRecord) {
        taskUsageById[taskId] = record
        _taskModelUsageMap.value = HashMap(taskUsageById)
    }

    fun getModelUsageForTask(taskId: String): ModelUsageRecord? = taskUsageById[taskId]

    private fun resolveProviderForModel(modelId: String): AIModelProvider {
        return if (gemmaProvider.supportsModel(modelId)) gemmaProvider else geminiTextProvider
    }

    private fun recordTelemetry(response: AIReasoningResponse, taskId: String?) {
        val descriptor = VerifiedReasoningModels.findByIdOrCustom(response.modelId)
        val record = ModelUsageRecord(
            modelId = response.modelId,
            modelDisplayName = descriptor.displayName,
            providerId = response.providerId,
            taskStage = response.taskStage,
            succeeded = response.success,
            usedFallback = response.usedFallback,
            fallbackNote = response.fallbackReason,
            promptTokens = response.tokenUsage.promptTokens,
            outputTokens = response.tokenUsage.candidatesTokens,
            latencyMs = response.latencyMs
        )
        _recentUsageHistory.update { list -> (listOf(record) + list).take(MAX_USAGE_HISTORY) }
        if (!taskId.isNullOrBlank()) {
            attachModelUsageToTask(taskId, record)
        }
        val modeBadge = when {
            response.usedFallback -> "Fallback Model"
            descriptor.providerId == AIProviderId.GOOGLE_GEMMA_REST -> "Gemma Reasoning"
            else -> descriptor.providerId.badgeLabel
        }
        _activeModelIndicator.value = ActiveModelIndicator(
            activeLabel = descriptor.displayName,
            modeBadge = modeBadge,
            providerId = response.providerId,
            modelId = descriptor.fullResourceName,
            isUsingFallback = response.usedFallback,
            lastUsage = record
        )
    }

    companion object {
        private const val MAX_USAGE_HISTORY = 30

        /**
         * Identifies simple deterministic single-step local device commands that do NOT
         * need a cloud LLM round-trip in AUTO_COMPLEX mode.
         */
        fun isTrivialDeterministicLocalCommand(goal: String): Boolean {
            val clean = goal.trim().lowercase()
            if (clean.isEmpty()) return true
            val words = clean.split(Regex("\\s+")).filter { it.isNotBlank() }
            val hasMultiStepConnector = clean.contains(" and ") ||
                clean.contains(" then ") ||
                clean.contains(" after ") ||
                clean.contains(" এবং ") ||
                clean.contains(" তারপর ") ||
                clean.contains(" করে ")

            if (hasMultiStepConnector || words.size >= 8) return false

            return when {
                clean in setOf(
                    "check time", "check date and time", "what time is it", "tell time",
                    "কয়টা বাজে", "সময় বলো", "সময় বলো", "তারিখ বলো"
                ) -> true
                clean in setOf("read screen", "read current screen", "scan screen", "স্ক্রিন পড়ো", "স্ক্রিন পড়ো") -> true
                (clean.startsWith("open ") && words.size <= 3) -> true
                clean in setOf("volume up", "volume down", "mute volume", "ভলিউম বাড়াও", "ভলিউম কমাও") -> true
                else -> false
            }
        }
    }
}
