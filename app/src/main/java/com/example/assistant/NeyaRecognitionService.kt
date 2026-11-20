package com.example.assistant

import android.content.Intent
import android.speech.RecognitionService
import android.speech.SpeechRecognizer

/**
 * Minimal [RecognitionService] declared alongside [NeyaVoiceInteractionService].
 *
 * AOSP's `VoiceInteractionServiceInfo` requires `android:recognitionService` in
 * `res/xml/neya_voice_interaction_service.xml` on Android 7–13+ so the app is accepted
 * as a valid Digital Assistant candidate in Android's Default Digital Assistant picker.
 *
 * Neya handles voice natively through its existing Gemini Live / in-app command pipeline
 * rather than acting as a generic third-party continuous STT backend, so external generic
 * recognition requests fail fast without opening a background microphone stream.
 */
class NeyaRecognitionService : RecognitionService() {

    override fun onStartListening(recognizerIntent: Intent?, listener: Callback?) {
        runCatching {
            listener?.error(SpeechRecognizer.ERROR_CLIENT)
        }
    }

    override fun onCancel(listener: Callback?) {
        // No background microphone session to cancel.
    }

    override fun onStopListening(listener: Callback?) {
        // No background microphone session to stop.
    }
}
