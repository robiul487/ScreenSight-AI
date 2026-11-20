package com.example.agent

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

/**
 * Lightweight local Bangla/English TextToSpeech fallback (Section 9).
 *
 * Used ONLY when Gemini Live audio is inactive/offline (for example, quick deterministic
 * Family Easy Mode actions like "সময় বলুন", or local spoken status/error prompts before
 * a Gemini Live WebSocket is connected). Never speaks over active Gemini Live voice output.
 */
class BanglaVoiceFeedbackManager(context: Context) {

    private var tts: TextToSpeech? = null
    @Volatile
    private var isReady: Boolean = false
    @Volatile
    private var speechRate: Float = 0.95f
    @Volatile
    private var voiceOutputEnabled: Boolean = true

    init {
        runCatching {
            tts = TextToSpeech(context.applicationContext) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    val engine = tts
                    val bnBd = Locale.Builder().setLanguage("bn").setRegion("BD").build()
                    val bnIn = Locale.Builder().setLanguage("bn").setRegion("IN").build()
                    val res = engine?.setLanguage(bnBd)
                    if (res == TextToSpeech.LANG_MISSING_DATA || res == TextToSpeech.LANG_NOT_SUPPORTED) {
                        val resIn = engine?.setLanguage(bnIn)
                        if (resIn == TextToSpeech.LANG_MISSING_DATA || resIn == TextToSpeech.LANG_NOT_SUPPORTED) {
                            engine?.setLanguage(Locale.getDefault())
                        }
                    }
                    engine?.setSpeechRate(speechRate)
                    isReady = true
                }
            }
        }
    }

    fun setSpeechRate(rate: Float) {
        speechRate = rate.coerceIn(0.6f, 1.5f)
        runCatching { tts?.setSpeechRate(speechRate) }
    }

    fun setVoiceOutputEnabled(enabled: Boolean) {
        voiceOutputEnabled = enabled
        if (!enabled) stopSpeaking()
    }

    fun speakIfIdle(text: String, isGeminiLiveAudioActive: Boolean) {
        if (!voiceOutputEnabled || isGeminiLiveAudioActive || !isReady || text.isBlank()) return
        runCatching {
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "screensight_local_feedback")
        }
    }

    fun isSpeaking(): Boolean = runCatching { tts?.isSpeaking == true }.getOrDefault(false)

    fun stopSpeaking() {
        runCatching { tts?.stop() }
    }

    fun shutdown() {
        isReady = false
        runCatching {
            tts?.stop()
            tts?.shutdown()
        }
        tts = null
    }
}
