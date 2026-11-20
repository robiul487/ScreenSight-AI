package com.example.data.ai

import com.example.data.repository.GeminiLiveRepository
import kotlinx.coroutines.flow.Flow

/**
 * Provider implementation for verified Google Gemma models (`models/gemma-4-31b-it`,
 * `models/gemma-4-26b-a4b-it`, and discovered `models/gemma-*` models) served directly
 * through Google's Gemini Developer API using the user's existing encrypted API key.
 */
class GemmaReasoningProvider(
    private val restClient: GoogleGenAiRestClient
) : AIModelProvider {
    override val providerId: AIProviderId = AIProviderId.GOOGLE_GEMMA_REST
    override val displayName: String = "Google Gemini API — Gemma 4"

    override fun supportsModel(modelId: String): Boolean {
        return VerifiedReasoningModels.isGemmaModelId(modelId)
    }

    override fun describeModel(modelId: String): AIModelDescriptor {
        return VerifiedReasoningModels.findByIdOrCustom(modelId)
    }

    override suspend fun generate(
        apiKey: String,
        modelId: String,
        request: AIReasoningRequest
    ): AIReasoningResponse {
        val descriptor = describeModel(modelId)
        return restClient.generateContent(apiKey, descriptor, request)
    }

    override fun streamGenerate(
        apiKey: String,
        modelId: String,
        request: AIReasoningRequest
    ): Flow<AIStreamChunk> {
        val descriptor = describeModel(modelId)
        return restClient.streamGenerateContent(apiKey, descriptor, request)
    }

    override suspend fun checkModelAvailability(
        apiKey: String,
        modelId: String
    ): ModelAvailabilityReport {
        val descriptor = describeModel(modelId)
        return restClient.checkModelAvailability(apiKey, descriptor)
    }
}

/**
 * Provider implementation for Google Gemini REST text models (e.g., `models/gemini-2.5-flash`)
 * serving as an automatic fallback reasoning provider using the exact same API key.
 */
class GeminiTextProvider(
    private val restClient: GoogleGenAiRestClient
) : AIModelProvider {
    override val providerId: AIProviderId = AIProviderId.GOOGLE_GEMINI_REST
    override val displayName: String = "Google Gemini API — Text Reasoning"

    override fun supportsModel(modelId: String): Boolean {
        val clean = modelId.trim().lowercase().removePrefix("models/")
        return clean.startsWith("gemini-")
    }

    override fun describeModel(modelId: String): AIModelDescriptor {
        return VerifiedReasoningModels.findByIdOrCustom(modelId)
    }

    override suspend fun generate(
        apiKey: String,
        modelId: String,
        request: AIReasoningRequest
    ): AIReasoningResponse {
        val descriptor = describeModel(modelId)
        return restClient.generateContent(apiKey, descriptor, request)
    }

    override fun streamGenerate(
        apiKey: String,
        modelId: String,
        request: AIReasoningRequest
    ): Flow<AIStreamChunk> {
        val descriptor = describeModel(modelId)
        return restClient.streamGenerateContent(apiKey, descriptor, request)
    }

    override suspend fun checkModelAvailability(
        apiKey: String,
        modelId: String
    ): ModelAvailabilityReport {
        val descriptor = describeModel(modelId)
        return restClient.checkModelAvailability(apiKey, descriptor)
    }
}

/**
 * Lightweight descriptor adapter exposing the existing Gemini Live WebSocket engine status
 * to the unified provider layer without modifying or wrapping its WebSocket lifecycle.
 */
class GeminiLiveProviderAdapter(
    private val geminiLiveRepository: GeminiLiveRepository
) {
    val providerId: AIProviderId = AIProviderId.GOOGLE_GEMINI_LIVE
    val displayName: String = "Google Gemini Live (BidiGenerateContent)"

    fun isLiveSessionActive(): Boolean = geminiLiveRepository.isSessionActive()
}
