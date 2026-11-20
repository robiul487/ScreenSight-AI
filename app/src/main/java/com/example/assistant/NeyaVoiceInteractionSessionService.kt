package com.example.assistant

import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService

/**
 * Factory service required by Android's VoiceInteraction framework to instantiate
 * [NeyaVoiceInteractionSession] whenever the user invokes Neya via system assistant triggers.
 */
class NeyaVoiceInteractionSessionService : VoiceInteractionSessionService() {

    override fun onNewSession(args: Bundle?): VoiceInteractionSession {
        return NeyaVoiceInteractionSession(this)
    }
}
