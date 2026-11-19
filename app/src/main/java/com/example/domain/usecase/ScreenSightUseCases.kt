package com.example.domain.usecase

import android.graphics.Bitmap
import com.example.service.SessionManager

/**
 * Domain Layer Use Cases mandated by Section 4 of the ScreenSight AI architecture.
 */
class StartSessionUseCase(private val sessionManager: SessionManager) {
    operator fun invoke() {
        sessionManager.startSession()
    }
}

class StopSessionUseCase(private val sessionManager: SessionManager) {
    operator fun invoke(clearTranscript: Boolean = false) {
        sessionManager.stopSession(clearTranscriptOnStop = clearTranscript)
    }
}

class SendScreenFrameUseCase(private val sessionManager: SessionManager) {
    operator fun invoke(bitmap: Bitmap) {
        sessionManager.processAndSendScreenBitmap(bitmap)
    }
}

class StartAudioUseCase(private val sessionManager: SessionManager) {
    operator fun invoke() {
        sessionManager.startAudioCaptureIfPermitted()
    }
}

class SendTextMessageUseCase(private val sessionManager: SessionManager) {
    operator fun invoke(text: String) {
        sessionManager.sendUserTextMessage(text)
    }
}
