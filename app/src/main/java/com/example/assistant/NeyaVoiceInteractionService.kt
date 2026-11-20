package com.example.assistant

import android.os.Bundle
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import com.example.ScreenSightApp

/**
 * Native Android Digital Assistant service for Neya (ScreenSight AI).
 *
 * Runs in the main app process so it shares the single existing [com.example.AppContainer]
 * (SessionManager, AgentCoordinator, AgentToolRegistry, MultiAgentOrchestrator, and
 * LongTermTaskOrchestrator) without duplicating microphone, WebSocket, or task state.
 *
 * Note on "Hey Neya" hardware DSP hotword:
 * Standard third-party Android apps do not hold privileged system permissions
 * (`CAPTURE_AUDIO_HOTWORD`, `MANAGE_VOICE_KEYPHRASES`), so `createAlwaysOnHotwordDetector`
 * is intentionally NOT invoked here to avoid guaranteed SecurityExceptions or fake state.
 */
class NeyaVoiceInteractionService : VoiceInteractionService() {

    private val controller: NeyaAssistantController?
        get() = (applicationContext as? ScreenSightApp)?.container?.neyaAssistantController

    override fun onReady() {
        super.onReady()
        controller?.onVoiceInteractionServiceReady()
    }

    override fun onLaunchVoiceAssistFromKeyguard() {
        val args = Bundle().apply {
            putBoolean(KEY_FROM_KEYGUARD, true)
        }
        runCatching {
            showSession(args, VoiceInteractionSession.SHOW_WITH_ASSIST)
        }
    }

    override fun onShutdown() {
        controller?.onVoiceInteractionServiceShutdown()
        super.onShutdown()
    }

    companion object {
        const val KEY_FROM_KEYGUARD = "neya_from_keyguard"
    }
}
