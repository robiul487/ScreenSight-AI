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

    init {
        runCatching {
            tts = TextToSpeech(context.applicationContext) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    val engine = tts
                    val bnBd = Locale("bn", "BD")
                    val bnIn = Locale("bn", "IN")
                    val res = engine?.setLanguage(bnBd)
                    if (res == TextToSpeech.LANG_MISSING_DATA || res == TextToSpeech.LANG_NOT_SUPPORTED) {
                        val resIn = engine?.setLanguage(bnIn)
                        if (resIn == TextToSpeech.LANG_MISSING_DATA || resIn == TextToSpeech.LANG_NOT_SUPPORTED) {
                            engine?.setLanguage(Locale.getDefault())
                        }
                    }
                    engine?.setSpeechRate(0.95f)
                    isReady = true
                }
            }
        }
    }

    fun speakIfIdle(text: String, isGeminiLiveAudioActive: Boolean) {
        if (isGeminiLiveAudioActive || !isReady || text.isBlank()) return
        runCatching {
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "screensight_local_feedback")
        }
    }

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
