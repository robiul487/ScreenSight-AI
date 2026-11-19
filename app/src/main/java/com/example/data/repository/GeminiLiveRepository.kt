package com.example.data.repository

import com.example.data.remote.ApiValidationResult
import com.example.data.remote.GeminiServerEvent
import com.example.data.remote.GeminiWebSocketClient
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Data layer repository coordinating Gemini Live API operations over WebSocket.
 */
class GeminiLiveRepository(
    private val webSocketClient: GeminiWebSocketClient = GeminiWebSocketClient()
) {
    val serverEvents: SharedFlow<GeminiServerEvent> = webSocketClient.events
    val outboundQueueBytes: StateFlow<Long> = webSocketClient.outboundQueueBytes

    suspend fun validateApiKeyAndModel(apiKey: String, selectedModelId: String): ApiValidationResult {
        return webSocketClient.validateApiKeyAndModel(apiKey, selectedModelId)
    }

    fun startLiveConnection(
        apiKey: String,
        modelId: String,
        voiceName: String,
        responseModalities: List<String>,
        systemInstruction: String,
        preferredLanguage: String
    ) {
        webSocketClient.connect(
            apiKey = apiKey,
            modelId = modelId,
            voiceName = voiceName,
            responseModalities = responseModalities,
            systemInstruction = systemInstruction,
            preferredLanguage = preferredLanguage
        )
    }

    fun sendAudioChunk(pcmBytes: ByteArray, sampleRateHz: Int = 16000): Boolean {
        return webSocketClient.sendAudioChunk(pcmBytes, sampleRateHz)
    }

    fun sendAudioStreamEnd(): Boolean {
        return webSocketClient.sendAudioStreamEnd()
    }

    fun sendScreenFrame(jpegBytes: ByteArray): Boolean {
        return webSocketClient.sendVideoFrame(jpegBytes)
    }

    fun sendTextMessage(text: String): Boolean {
        return webSocketClient.sendTextMessage(text)
    }

    fun stopLiveConnection() {
        webSocketClient.disconnect()
    }

    fun isSessionActive(): Boolean = webSocketClient.isSessionActive()
}
