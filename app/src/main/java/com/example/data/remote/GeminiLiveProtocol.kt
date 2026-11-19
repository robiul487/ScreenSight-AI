package com.example.data.remote

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Official Gemini Live API (BidiGenerateContent) JSON wire schema.
 * Docs: https://ai.google.dev/api/live
 */
object GeminiLiveJson {
    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }
}

@Serializable
data class BidiClientMessage(
    val setup: BidiSetup? = null,
    val realtimeInput: BidiRealtimeInput? = null,
    val clientContent: BidiClientContent? = null,
    val toolResponse: BidiToolResponse? = null
)

@Serializable
data class BidiSetup(
    val model: String,
    val generationConfig: BidiGenerationConfig? = null,
    val systemInstruction: BidiContent? = null,
    val tools: List<BidiTool>? = null,
    val inputAudioTranscription: BidiAudioTranscriptionConfig? = null,
    val outputAudioTranscription: BidiAudioTranscriptionConfig? = null
)

@Serializable
data class BidiTool(
    val functionDeclarations: List<BidiFunctionDeclaration>? = null
)

@Serializable
data class BidiFunctionDeclaration(
    val name: String,
    val description: String,
    val parameters: BidiSchema? = null
)

@Serializable
data class BidiSchema(
    val type: String,
    val description: String? = null,
    val properties: Map<String, BidiSchema>? = null,
    val required: List<String>? = null,
    val enum: List<String>? = null
)

@Serializable
data class BidiToolResponse(
    val functionResponses: List<BidiFunctionResponse>
)

@Serializable
data class BidiFunctionResponse(
    val id: String? = null,
    val name: String,
    val response: JsonObject
)

@Serializable
class BidiAudioTranscriptionConfig

@Serializable
data class BidiGenerationConfig(
    val responseModalities: List<String> = listOf("AUDIO"),
    val speechConfig: BidiSpeechConfig? = null
)

@Serializable
data class BidiSpeechConfig(
    val voiceConfig: BidiVoiceConfig? = null
)

@Serializable
data class BidiVoiceConfig(
    val prebuiltVoiceConfig: BidiPrebuiltVoiceConfig? = null
)

@Serializable
data class BidiPrebuiltVoiceConfig(
    val voiceName: String
)

/**
 * Official BidiGenerateContentRealtimeInput schema.
 * Uses `audio`, `video`, `text`, and `audioStreamEnd` fields directly instead of deprecated `mediaChunks`.
 */
@Serializable
data class BidiRealtimeInput(
    val audio: BidiMediaChunk? = null,
    val video: BidiMediaChunk? = null,
    val text: String? = null,
    val audioStreamEnd: Boolean? = null
)

@Serializable
data class BidiMediaChunk(
    val mimeType: String,
    val data: String
)

@Serializable
data class BidiClientContent(
    val turns: List<BidiContent>,
    val turnComplete: Boolean = true
)

@Serializable
data class BidiContent(
    val role: String? = null,
    val parts: List<BidiPart>
)

@Serializable
data class BidiPart(
    val text: String? = null,
    val thought: Boolean? = null,
    val inlineData: BidiInlineData? = null
)

@Serializable
data class BidiInlineData(
    val mimeType: String,
    val data: String
)

// Server-to-Client Message Schema

@Serializable
data class BidiServerMessage(
    val setupComplete: JsonElement? = null,
    val serverContent: BidiServerContent? = null,
    val toolCall: BidiToolCall? = null,
    val toolCallCancellation: BidiToolCallCancellation? = null,
    val goAway: BidiGoAway? = null,
    val error: BidiErrorPayload? = null
)

@Serializable
data class BidiToolCall(
    val functionCalls: List<BidiFunctionCall> = emptyList()
)

@Serializable
data class BidiFunctionCall(
    val id: String? = null,
    val name: String,
    val args: JsonObject? = null
)

@Serializable
data class BidiToolCallCancellation(
    val ids: List<String> = emptyList()
)

@Serializable
data class BidiServerContent(
    val modelTurn: BidiContent? = null,
    val turnComplete: Boolean? = null,
    val generationComplete: Boolean? = null,
    val interrupted: Boolean? = null,
    val inputTranscription: BidiTranscription? = null,
    val outputTranscription: BidiTranscription? = null
)

@Serializable
data class BidiTranscription(
    val text: String? = null
)

@Serializable
data class BidiGoAway(
    val timeLeft: String? = null
)

@Serializable
data class BidiErrorPayload(
    val code: Int? = null,
    val message: String? = null,
    val status: String? = null
)

/**
 * Dedicated configuration builder for Gemini Live sessions (Section 5.3).
 * Uses the modern BidiGenerateContentRealtimeInput schema (`audio`, `video`, `text`, `audioStreamEnd`).
 */
object GeminiLiveConfigBuilder {

    const val GEMINI_LIVE_WS_BASE_URL =
        "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"

    const val GEMINI_MODELS_REST_BASE_URL =
        "https://generativelanguage.googleapis.com/v1beta"

    fun buildWebSocketUrl(apiKey: String): String {
        return "$GEMINI_LIVE_WS_BASE_URL?key=${apiKey.trim()}"
    }

    fun buildSetupMessageJson(
        modelId: String,
        voiceName: String,
        responseModalities: List<String>,
        systemInstructionText: String,
        preferredLanguage: String,
        tools: List<BidiTool>? = null
    ): String {
        val normalizedModel = if (modelId.startsWith("models/")) modelId else "models/$modelId"
        val languageAppendix = if (preferredLanguage.isNotBlank() && !preferredLanguage.startsWith("Auto")) {
            "\n\nPreferred response language: $preferredLanguage."
        } else {
            ""
        }
        val fullInstruction = (systemInstructionText.trim() + languageAppendix).trim()

        // Native audio Live models require a single primary response modality ("AUDIO" or "TEXT");
        // text transcripts of spoken output are requested via `outputAudioTranscription`.
        val wantsAudio = responseModalities.isEmpty() ||
            responseModalities.any { it.equals("AUDIO", ignoreCase = true) }
        val singleModality = if (wantsAudio) listOf("AUDIO") else listOf("TEXT")

        val setup = BidiSetup(
            model = normalizedModel,
            generationConfig = BidiGenerationConfig(
                responseModalities = singleModality,
                speechConfig = if (wantsAudio) {
                    BidiSpeechConfig(
                        voiceConfig = BidiVoiceConfig(
                            prebuiltVoiceConfig = BidiPrebuiltVoiceConfig(
                                voiceName = voiceName.ifBlank { "Aoede" }
                            )
                        )
                    )
                } else {
                    null
                }
            ),
            systemInstruction = BidiContent(
                parts = listOf(BidiPart(text = fullInstruction))
            ),
            tools = tools?.takeIf { it.isNotEmpty() },
            inputAudioTranscription = BidiAudioTranscriptionConfig(),
            outputAudioTranscription = if (wantsAudio) BidiAudioTranscriptionConfig() else null
        )
        return GeminiLiveJson.json.encodeToString(BidiClientMessage(setup = setup))
    }

    fun buildToolResponseMessageJson(responses: List<BidiFunctionResponse>): String {
        val msg = BidiClientMessage(
            toolResponse = BidiToolResponse(functionResponses = responses)
        )
        return GeminiLiveJson.json.encodeToString(msg)
    }

    fun buildAudioChunkMessageJson(base64Pcm16k: String, sampleRateHz: Int = 16000): String {
        val msg = BidiClientMessage(
            realtimeInput = BidiRealtimeInput(
                audio = BidiMediaChunk(
                    mimeType = "audio/pcm;rate=$sampleRateHz",
                    data = base64Pcm16k
                )
            )
        )
        return GeminiLiveJson.json.encodeToString(msg)
    }

    fun buildAudioStreamEndMessageJson(): String {
        val msg = BidiClientMessage(
            realtimeInput = BidiRealtimeInput(
                audioStreamEnd = true
            )
        )
        return GeminiLiveJson.json.encodeToString(msg)
    }

    fun buildVideoFrameMessageJson(base64Jpeg: String): String {
        val msg = BidiClientMessage(
            realtimeInput = BidiRealtimeInput(
                video = BidiMediaChunk(
                    mimeType = "image/jpeg",
                    data = base64Jpeg
                )
            )
        )
        return GeminiLiveJson.json.encodeToString(msg)
    }

    fun buildTextMessageJson(userText: String): String {
        val msg = BidiClientMessage(
            realtimeInput = BidiRealtimeInput(
                text = userText.trim()
            )
        )
        return GeminiLiveJson.json.encodeToString(msg)
    }
}
