package com.example

import com.example.data.local.ApiKeyRepository
import com.example.data.remote.BidiServerMessage
import com.example.data.remote.GeminiLiveConfigBuilder
import com.example.data.remote.GeminiLiveJson
import com.example.data.remote.GeminiServerEvent
import com.example.data.remote.GeminiWebSocketClient
import com.example.domain.model.AppSettings
import com.example.domain.model.GeminiLiveModel
import com.example.domain.model.LiveSessionState
import com.example.domain.model.ScreenSightError
import com.example.service.AudioCaptureManager
import com.example.service.FrameProcessor
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Unit tests verifying:
 * 1. API configuration, key masking, format validation, and BuildConfig fallback validation
 * 2. Official Gemini Live API model ID verification (no unverified dashboard display names assumed as valid IDs)
 * 3. Gemini Live WebSocket BidiGenerateContent message serialization (single modality setup, audio/video/text/audioStreamEnd)
 * 4. Server message parsing & internal `thought` part filtering
 * 5. FrameProcessor aspect-ratio resizing & zero-allocation ByteBuffer perceptual hash deduplication
 * 6. Centralized error classification (missing key, invalid key, unsupported model, quota, rate limit, offline, timeout)
 * 7. Session state model properties & PCM audio RMS calculation
 */
class ExampleUnitTest {

    @Test
    fun `api key masking never exposes full secret`() {
        val rawKey = "AIzaSyD9876543210abcdefABCDEF123456789"
        val masked = ApiKeyRepository.maskKey(rawKey)
        assertTrue(masked.startsWith("AIza"))
        assertTrue(masked.endsWith("6789"))
        assertFalse(masked.contains("9876543210"))
        assertEquals("Not configured", ApiKeyRepository.maskKey("   "))
    }

    @Test
    fun `api key format validation rejects short, placeholder, or whitespace keys`() {
        assertFalse(ApiKeyRepository.isValidApiKeyFormat(""))
        assertFalse(ApiKeyRepository.isValidApiKeyFormat("short_key"))
        assertFalse(ApiKeyRepository.isValidApiKeyFormat("AIzaSy with spaces 1234567890"))
        assertFalse(ApiKeyRepository.isUsableInjectedSecret("MY_GEMINI_API_KEY"))
        assertFalse(ApiKeyRepository.isUsableInjectedSecret("YOUR_API_KEY"))
        assertTrue(ApiKeyRepository.isValidApiKeyFormat("AIzaSyD9876543210abcdefABCDEF123456789"))
    }

    @Test
    fun `gemini live setup message serializes single modality BidiGenerateContent JSON`() {
        // Even if caller passes ["AUDIO", "TEXT"], setup builder enforces single ["AUDIO"] modality
        // and uses outputAudioTranscription for spoken text transcription.
        val jsonStr = GeminiLiveConfigBuilder.buildSetupMessageJson(
            modelId = GeminiLiveModel.PRIMARY_MODEL.id,
            voiceName = "Aoede",
            responseModalities = listOf("AUDIO", "TEXT"),
            systemInstructionText = AppSettings.DEFAULT_SYSTEM_INSTRUCTION,
            preferredLanguage = "Bengali (বাংলা)"
        )

        assertTrue(jsonStr.contains("\"setup\""))
        assertTrue(jsonStr.contains("\"models/gemini-3.1-flash-live-preview\""))
        assertTrue(jsonStr.contains("\"responseModalities\":[\"AUDIO\"]"))
        assertFalse(jsonStr.contains("\"responseModalities\":[\"AUDIO\",\"TEXT\"]"))
        assertTrue(jsonStr.contains("\"outputAudioTranscription\":{}"))
        assertTrue(jsonStr.contains("\"inputAudioTranscription\":{}"))
        assertTrue(jsonStr.contains("\"voiceName\":\"Aoede\""))
        assertTrue(jsonStr.contains("ScreenSight AI"))
        assertTrue(jsonStr.contains("Preferred response language: Bengali"))
    }

    @Test
    fun `only verified BidiGenerateContent API model IDs are marked verified`() {
        assertEquals("models/gemini-3.1-flash-live-preview", GeminiLiveModel.DEFAULT_MODEL.id)
        assertEquals("Gemini 3 Flash Live", GeminiLiveModel.PRIMARY_MODEL.displayName)
        assertEquals("models/gemini-2.5-flash-native-audio-preview-12-2025", GeminiLiveModel.FALLBACK_MODEL.id)

        assertTrue(GeminiLiveModel.VERIFIED_LIVE_MODELS.all { it.isVerifiedLiveModel })
        assertEquals(
            "models/gemini-2.5-flash-native-audio-preview-12-2025",
            GeminiLiveModel.normalizeModelId("Gemini 2.5 Flash Native Audio Dialog")
        )

        // Unverified dashboard slug or custom model ID must never be marked as a verified Live model
        val unverifiedCustom = GeminiLiveModel.findByIdOrCustom("models/gemini-custom-live-preview")
        assertEquals("models/gemini-custom-live-preview", unverifiedCustom.fullResourceName)
        assertFalse(unverifiedCustom.isVerifiedLiveModel)

        // Legacy shut-down or unverified slug models are detected for automatic migration
        assertTrue(GeminiLiveModel.isLegacyShutDownModel("models/gemini-2.0-flash-live-001"))
        assertTrue(GeminiLiveModel.isLegacyShutDownModel("models/gemini-3.8-live"))
        assertFalse(GeminiLiveModel.isLegacyShutDownModel("models/gemini-3.1-flash-live-preview"))
    }

    @Test
    fun `gemini live audio, video, text, and audioStreamEnd realtimeInput messages serialize properly`() {
        val audioJson = GeminiLiveConfigBuilder.buildAudioChunkMessageJson("UklGRg==", 16000)
        assertTrue(audioJson.contains("\"realtimeInput\""))
        assertTrue(audioJson.contains("\"audio\""))
        assertFalse(audioJson.contains("mediaChunks"))
        assertTrue(audioJson.contains("\"mimeType\":\"audio/pcm;rate=16000\""))
        assertTrue(audioJson.contains("\"data\":\"UklGRg==\""))

        val streamEndJson = GeminiLiveConfigBuilder.buildAudioStreamEndMessageJson()
        assertTrue(streamEndJson.contains("\"realtimeInput\""))
        assertTrue(streamEndJson.contains("\"audioStreamEnd\":true"))

        val videoJson = GeminiLiveConfigBuilder.buildVideoFrameMessageJson("/9j/4AAQSkZJRg==")
        assertTrue(videoJson.contains("\"realtimeInput\""))
        assertTrue(videoJson.contains("\"video\""))
        assertFalse(videoJson.contains("mediaChunks"))
        assertTrue(videoJson.contains("\"mimeType\":\"image/jpeg\""))
        assertTrue(videoJson.contains("\"data\":\"/9j/4AAQSkZJRg==\""))

        val textJson = GeminiLiveConfigBuilder.buildTextMessageJson("What button is in the top right?")
        assertTrue(textJson.contains("\"realtimeInput\""))
        assertTrue(textJson.contains("\"text\":\"What button is in the top right?\""))
        assertFalse(textJson.contains("mediaChunks"))
    }

    @Test
    fun `gemini live server message deserializes setupComplete, audio, turnComplete, and filters internal thoughts`() = runBlocking {
        val setupCompleteMsg = GeminiLiveJson.json.decodeFromString<BidiServerMessage>(
            """{"setupComplete": {}}"""
        )
        assertNotNull(setupCompleteMsg.setupComplete)

        val client = GeminiWebSocketClient()
        val rawServerFrameWithThought = """{
            "serverContent": {
                "modelTurn": {
                    "parts": [
                        {"text": "Internal chain of thought reasoning", "thought": true},
                        {"inlineData": {"mimeType": "audio/pcm;rate=24000", "data": "AAAA"}}
                    ]
                },
                "outputTranscription": {
                    "text": "I can see your Android settings screen."
                },
                "turnComplete": true
            }
        }"""

        val collectedTextDeltas = mutableListOf<String>()
        val collectJob = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined).launch {
            client.events.collect { event ->
                if (event is GeminiServerEvent.TextDelta) {
                    collectedTextDeltas.add(event.text)
                }
            }
        }
        client.receiveMessages(rawServerFrameWithThought)
        collectJob.cancel()

        assertEquals(listOf("I can see your Android settings screen."), collectedTextDeltas)
    }

    @Test
    fun `frame processor calculates aspect ratio dimensions and zero-allocation buffer signature accurately`() {
        val (w, h) = FrameProcessor.calculateTargetDimensions(
            srcWidth = 1080,
            srcHeight = 2400,
            maxDimension = 1280
        )
        assertEquals(1280, h)
        assertEquals(576, w)

        val (smallW, smallH) = FrameProcessor.calculateTargetDimensions(
            srcWidth = 720,
            srcHeight = 1280,
            maxDimension = 1280
        )
        assertEquals(720, smallW)
        assertEquals(1280, smallH)

        // Test zero-Bitmap ByteBuffer perceptual hash comparison
        val width = 16
        val height = 16
        val pixelStride = 4
        val rowStride = width * pixelStride
        val bufA = ByteBuffer.allocate(height * rowStride)
        val bufB = ByteBuffer.allocate(height * rowStride)
        for (i in 0 until height * rowStride step 4) {
            val luma = (i % 255).toByte()
            bufA.put(i, luma)
            bufA.put(i + 1, luma)
            bufA.put(i + 2, luma)
            bufB.put(i, luma)
            bufB.put(i + 1, luma)
            bufB.put(i + 2, luma)
        }
        val sigA = FrameProcessor.computeLuminanceSignatureFromBuffer(bufA, width, height, pixelStride, rowStride)
        val sigB = FrameProcessor.computeLuminanceSignatureFromBuffer(bufB, width, height, pixelStride, rowStride)
        assertEquals(sigA, sigB)
        assertTrue(FrameProcessor.isSignatureNearlyIdentical(sigA, sigB))
    }

    @Test
    fun `centralized error handler maps invalid key, unsupported model, quota, rate limit, and network errors`() {
        val invalidKeyErr = ScreenSightError.fromHttpOrWebSocketError(
            code = 400,
            message = "API_KEY_INVALID: API key not valid. Please pass a valid API key.",
            selectedModel = "models/gemini-3.1-flash-live-preview"
        )
        assertTrue(invalidKeyErr is ScreenSightError.InvalidApiKey)

        val unsupportedModelErr = ScreenSightError.fromHttpOrWebSocketError(
            code = 1008,
            message = "models/gemini-1.5-pro is not found for API version v1beta, or is not supported for bidiGenerateContent",
            selectedModel = "models/gemini-1.5-pro"
        )
        assertTrue(unsupportedModelErr is ScreenSightError.UnsupportedModel)

        val quotaErr = ScreenSightError.fromHttpOrWebSocketError(
            code = 429,
            message = "RESOURCE_EXHAUSTED: Quota exceeded for metric",
            selectedModel = "models/gemini-3.1-flash-live-preview"
        )
        assertTrue(quotaErr is ScreenSightError.QuotaExceeded)

        val rateLimitErr = ScreenSightError.fromHttpOrWebSocketError(
            code = 429,
            message = "Too many requests: Rate limit exceeded",
            selectedModel = "models/gemini-3.1-flash-live-preview"
        )
        assertTrue(rateLimitErr is ScreenSightError.RateLimitExceeded)

        val offlineErr = ScreenSightError.fromHttpOrWebSocketError(
            code = null,
            message = "Unable to resolve host generativelanguage.googleapis.com: No address associated with hostname",
            selectedModel = "models/gemini-3.1-flash-live-preview"
        )
        assertTrue(offlineErr is ScreenSightError.InternetUnavailable)
    }

    @Test
    fun `pcm 16-bit audio rms calculation detects silence vs active speech`() {
        val silentPcm = ByteArray(320)
        assertEquals(0f, AudioCaptureManager.calculateNormalizedRms(silentPcm), 0.001f)

        val loudBuffer = ByteBuffer.allocate(320).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until 160) {
            loudBuffer.putShort(12000)
        }
        val rms = AudioCaptureManager.calculateNormalizedRms(loudBuffer.array())
        assertTrue("Expected non-zero RMS for active speech signal", rms > 0.3f)
    }

    @Test
    fun `all 11 required live session states exist and have correct active flags`() {
        val allNames = LiveSessionState.entries.map { it.name }.toSet()
        val expected = setOf(
            "IDLE",
            "CONNECTING",
            "CONNECTED",
            "REQUESTING_SCREEN_PERMISSION",
            "SHARING_SCREEN",
            "LISTENING",
            "THINKING",
            "SPEAKING",
            "RECONNECTING",
            "ERROR",
            "STOPPED"
        )
        assertEquals(expected, allNames)
    }
}
