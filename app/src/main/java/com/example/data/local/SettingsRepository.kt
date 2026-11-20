package com.example.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.agent.AgentMode
import com.example.domain.model.AppSettings
import com.example.domain.model.AutonomyLevel
import com.example.domain.model.GeminiLiveModel
import com.example.domain.model.GeminiVoice
import com.example.domain.model.ThemePreference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "screensight_settings")

class SettingsRepository(private val dataStore: DataStore<Preferences>) {

    constructor(context: Context) : this(context.applicationContext.dataStore)

    private suspend fun editPrefs(transform: (MutablePreferences) -> Unit) {
        withContext(Dispatchers.IO) {
            dataStore.edit { prefs -> transform(prefs) }
        }
    }

    val settingsFlow: Flow<AppSettings> = dataStore.data.map { prefs ->
        val themeStr = prefs[KEY_THEME] ?: ThemePreference.DARK.name
        val themePref = runCatching { ThemePreference.valueOf(themeStr) }.getOrDefault(ThemePreference.DARK)
        val rawStoredModel = prefs[KEY_SELECTED_MODEL]
        val activeModelId = if (rawStoredModel.isNullOrBlank() || GeminiLiveModel.isLegacyShutDownModel(rawStoredModel)) {
            GeminiLiveModel.DEFAULT_MODEL.id
        } else {
            GeminiLiveModel.normalizeModelId(rawStoredModel)
        }
        val rawFallbackModel = prefs[KEY_FALLBACK_MODEL]
        val fallbackModelId = if (rawFallbackModel.isNullOrBlank() || GeminiLiveModel.isLegacyShutDownModel(rawFallbackModel)) {
            GeminiLiveModel.FALLBACK_MODEL.id
        } else {
            GeminiLiveModel.normalizeModelId(rawFallbackModel)
        }
        val selectedModel = GeminiLiveModel.findByIdOrCustom(activeModelId)
        // Native audio BidiGenerateContent models require a single response modality ("AUDIO" or "TEXT")
        val modalities = if (!selectedModel.supportsNativeAudio) {
            listOf("TEXT")
        } else {
            listOf("AUDIO")
        }

        val modeStr = prefs[KEY_AGENT_MODE] ?: AgentMode.STANDARD_AGENT.name
        val agentMode = runCatching { AgentMode.valueOf(modeStr) }.getOrDefault(AgentMode.STANDARD_AGENT)
        val autonomyStr = prefs[KEY_AUTONOMY_LEVEL] ?: AutonomyLevel.BALANCED.name
        val autonomyLevel = runCatching { AutonomyLevel.valueOf(autonomyStr) }.getOrDefault(AutonomyLevel.BALANCED)

        AppSettings(
            selectedModelId = activeModelId,
            fallbackModelId = fallbackModelId,
            selectedVoiceName = prefs[KEY_SELECTED_VOICE] ?: GeminiVoice.AOEDE.voiceName,
            responseModalities = modalities,
            preferredLanguage = prefs[KEY_PREFERRED_LANGUAGE] ?: "Auto-detect (Match user language)",
            customSystemInstruction = prefs[KEY_SYSTEM_INSTRUCTION] ?: AppSettings.DEFAULT_SYSTEM_INSTRUCTION,
            frameRateFps = (prefs[KEY_FRAME_RATE_FPS] ?: 1).coerceIn(1, 2),
            maxFrameDimensionPx = (prefs[KEY_MAX_DIMENSION_PX] ?: 1280).coerceIn(640, 1920),
            jpegQuality = (prefs[KEY_JPEG_QUALITY] ?: 70).coerceIn(40, 90),
            themePreference = themePref,
            dynamicColorEnabled = prefs[KEY_DYNAMIC_COLOR] ?: false,
            hapticFeedbackEnabled = prefs[KEY_HAPTIC_FEEDBACK] ?: true,
            soundEffectsEnabled = prefs[KEY_SOUND_EFFECTS] ?: true,
            animationsEnabled = prefs[KEY_ANIMATIONS_ENABLED] ?: true,
            defaultLandingScreen = prefs[KEY_DEFAULT_LANDING_SCREEN] ?: "home",
            hasAcceptedPrivacyNotice = prefs[KEY_PRIVACY_ACCEPTED] ?: false,
            lastSessionSummary = prefs[KEY_LAST_SESSION_SUMMARY] ?: "No session recorded yet",
            agentMode = agentMode,
            autonomyLevel = autonomyLevel,
            agentToolsEnabled = prefs[KEY_AGENT_TOOLS_ENABLED] ?: true,
            maxTaskSteps = (prefs[KEY_MAX_TASK_STEPS] ?: 12).coerceIn(4, 30),
            maxActionRetries = (prefs[KEY_MAX_ACTION_RETRIES] ?: 2).coerceIn(0, 5),
            agentToolTimeoutSec = (prefs[KEY_AGENT_TOOL_TIMEOUT_SEC] ?: 12).coerceIn(5, 30),
            autoCheckpointRecoveryEnabled = prefs[KEY_AUTO_CHECKPOINT_RECOVERY] ?: true,
            backgroundAutomationEnabled = prefs[KEY_BACKGROUND_AUTOMATION] ?: true,
            voiceInputEnabled = prefs[KEY_VOICE_INPUT_ENABLED] ?: true,
            voiceOutputEnabled = prefs[KEY_VOICE_OUTPUT_ENABLED] ?: true,
            ttsSpeechRate = (prefs[KEY_TTS_SPEECH_RATE] ?: 0.95f).coerceIn(0.6f, 1.5f),
            voiceOutputVolume = (prefs[KEY_VOICE_OUTPUT_VOLUME] ?: 1.0f).coerceIn(0.1f, 1.0f),
            bargeInInterruptEnabled = prefs[KEY_BARGE_IN_INTERRUPT] ?: true,
            lastAgentTaskSummary = prefs[KEY_LAST_AGENT_TASK_SUMMARY] ?: "No agent task recorded yet",
            memoryEnabled = prefs[KEY_MEMORY_ENABLED] ?: true,
            defaultMemoryRetention = prefs[KEY_DEFAULT_MEMORY_RETENTION] ?: "NEVER",
            autoMemoryProposalsEnabled = prefs[KEY_AUTO_MEMORY_PROPOSALS] ?: true,
            autoSkillProposalsEnabled = prefs[KEY_AUTO_SKILL_PROPOSALS] ?: true,
            proactiveSuggestionsEnabled = prefs[KEY_PROACTIVE_SUGGESTIONS_ENABLED] ?: true,
            taskNotificationsEnabled = prefs[KEY_TASK_NOTIFICATIONS] ?: true,
            completionNotificationsEnabled = prefs[KEY_COMPLETION_NOTIFICATIONS] ?: true,
            failureNotificationsEnabled = prefs[KEY_FAILURE_NOTIFICATIONS] ?: true,
            scheduledTaskNotificationsEnabled = prefs[KEY_SCHEDULED_NOTIFICATIONS] ?: true,
            notificationVibrationEnabled = prefs[KEY_NOTIFICATION_VIBRATION] ?: true,
            reasoningModelId = prefs[KEY_REASONING_MODEL]
                ?.takeIf { it.isNotBlank() }
                ?.let { com.example.data.ai.VerifiedReasoningModels.normalizeReasoningModelId(it) }
                ?: com.example.data.ai.VerifiedReasoningModels.DEFAULT_PRIMARY.id,
            reasoningFallbackModelId = prefs[KEY_REASONING_FALLBACK_MODEL]
                ?.takeIf { it.isNotBlank() }
                ?.let { com.example.data.ai.VerifiedReasoningModels.normalizeReasoningModelId(it) }
                ?: com.example.data.ai.VerifiedReasoningModels.DEFAULT_FALLBACK.id,
            gemmaReasoningEnabled = prefs[KEY_GEMMA_REASONING_ENABLED] ?: true,
            reasoningRoutingMode = prefs[KEY_REASONING_ROUTING_MODE]
                ?.let { runCatching { com.example.data.ai.ReasoningRoutingMode.valueOf(it) }.getOrNull() }
                ?: com.example.data.ai.ReasoningRoutingMode.AUTO_COMPLEX,
            reasoningTimeoutSec = (prefs[KEY_REASONING_TIMEOUT_SEC] ?: 25).coerceIn(10, 90),
            heyNeyaEnabled = prefs[KEY_HEY_NEYA_ENABLED] ?: false,
            wakeDetectionMode = prefs[KEY_WAKE_DETECTION_MODE]
                ?.let { runCatching { com.example.assistant.WakeDetectionMode.valueOf(it) }.getOrNull() }
                ?: com.example.assistant.WakeDetectionMode.MANUAL_ONLY,
            assistantOnboardingDismissed = prefs[KEY_ASSISTANT_ONBOARDING_DISMISSED] ?: false,
            wakeFallbackPreference = prefs[KEY_WAKE_FALLBACK_PREFERENCE] ?: "manual"
        )
    }

    suspend fun updateSelectedModel(modelId: String) {
        val clean = modelId.trim()
        if (clean.isNotEmpty()) {
            val normalized = GeminiLiveModel.normalizeModelId(clean)
            editPrefs { it[KEY_SELECTED_MODEL] = normalized }
        }
    }

    suspend fun updateFallbackModel(modelId: String) {
        val clean = modelId.trim()
        if (clean.isNotEmpty()) {
            val normalized = GeminiLiveModel.normalizeModelId(clean)
            editPrefs { it[KEY_FALLBACK_MODEL] = normalized }
        }
    }

    suspend fun updateSelectedVoice(voiceName: String) {
        editPrefs { it[KEY_SELECTED_VOICE] = voiceName }
    }

    suspend fun updatePreferredLanguage(language: String) {
        editPrefs { it[KEY_PREFERRED_LANGUAGE] = language }
    }

    suspend fun updateSystemInstruction(instruction: String) {
        editPrefs {
            it[KEY_SYSTEM_INSTRUCTION] = instruction.ifBlank { AppSettings.DEFAULT_SYSTEM_INSTRUCTION }
        }
    }

    suspend fun updateFrameCaptureConfig(fps: Int, maxDimPx: Int, jpegQuality: Int) {
        editPrefs {
            it[KEY_FRAME_RATE_FPS] = fps.coerceIn(1, 2)
            it[KEY_MAX_DIMENSION_PX] = maxDimPx.coerceIn(640, 1920)
            it[KEY_JPEG_QUALITY] = jpegQuality.coerceIn(40, 90)
        }
    }

    suspend fun updateThemePreference(theme: ThemePreference) {
        editPrefs { it[KEY_THEME] = theme.name }
    }

    suspend fun updateDynamicColor(enabled: Boolean) {
        editPrefs { it[KEY_DYNAMIC_COLOR] = enabled }
    }

    suspend fun updateHapticFeedback(enabled: Boolean) {
        editPrefs { it[KEY_HAPTIC_FEEDBACK] = enabled }
    }

    suspend fun updateSoundEffects(enabled: Boolean) {
        editPrefs { it[KEY_SOUND_EFFECTS] = enabled }
    }

    suspend fun updateAnimationsEnabled(enabled: Boolean) {
        editPrefs { it[KEY_ANIMATIONS_ENABLED] = enabled }
    }

    suspend fun updateDefaultLandingScreen(route: String) {
        val safe = if (route in setOf("home", "live_assistant", "tasks", "library")) route else "home"
        editPrefs { it[KEY_DEFAULT_LANDING_SCREEN] = safe }
    }

    suspend fun resetUiPreferences() {
        editPrefs {
            it[KEY_THEME] = ThemePreference.DARK.name
            it[KEY_DYNAMIC_COLOR] = false
            it[KEY_HAPTIC_FEEDBACK] = true
            it[KEY_SOUND_EFFECTS] = true
            it[KEY_ANIMATIONS_ENABLED] = true
            it[KEY_DEFAULT_LANDING_SCREEN] = "home"
        }
    }

    suspend fun setPrivacyNoticeAccepted(accepted: Boolean) {
        editPrefs { it[KEY_PRIVACY_ACCEPTED] = accepted }
    }

    suspend fun updateLastSessionSummary(summary: String) {
        editPrefs { it[KEY_LAST_SESSION_SUMMARY] = summary }
    }

    suspend fun updateAgentMode(mode: AgentMode) {
        editPrefs {
            it[KEY_AGENT_MODE] = mode.name
            if (mode == AgentMode.FAMILY_EASY_MODE) {
                it[KEY_AUTONOMY_LEVEL] = AutonomyLevel.FAMILY_EASY.name
            } else if (it[KEY_AUTONOMY_LEVEL] == AutonomyLevel.FAMILY_EASY.name) {
                it[KEY_AUTONOMY_LEVEL] = AutonomyLevel.BALANCED.name
            }
        }
    }

    suspend fun updateAutonomyLevel(level: AutonomyLevel) {
        editPrefs {
            it[KEY_AUTONOMY_LEVEL] = level.name
            it[KEY_AGENT_MODE] = if (level == AutonomyLevel.FAMILY_EASY) {
                AgentMode.FAMILY_EASY_MODE.name
            } else {
                AgentMode.STANDARD_AGENT.name
            }
        }
    }

    suspend fun updateAgentToolsEnabled(enabled: Boolean) {
        editPrefs { it[KEY_AGENT_TOOLS_ENABLED] = enabled }
    }

    suspend fun updateAgentExecutionLimits(maxSteps: Int, maxRetries: Int, timeoutSec: Int) {
        editPrefs {
            it[KEY_MAX_TASK_STEPS] = maxSteps.coerceIn(4, 30)
            it[KEY_MAX_ACTION_RETRIES] = maxRetries.coerceIn(0, 5)
            it[KEY_AGENT_TOOL_TIMEOUT_SEC] = timeoutSec.coerceIn(5, 30)
        }
    }

    suspend fun updateAutoCheckpointRecovery(enabled: Boolean) {
        editPrefs { it[KEY_AUTO_CHECKPOINT_RECOVERY] = enabled }
    }

    suspend fun updateBackgroundAutomationEnabled(enabled: Boolean) {
        editPrefs { it[KEY_BACKGROUND_AUTOMATION] = enabled }
    }

    suspend fun updateVoiceAudioPreferences(
        voiceInputEnabled: Boolean? = null,
        voiceOutputEnabled: Boolean? = null,
        ttsSpeechRate: Float? = null,
        voiceOutputVolume: Float? = null,
        bargeInInterruptEnabled: Boolean? = null
    ) {
        editPrefs { prefs ->
            voiceInputEnabled?.let { prefs[KEY_VOICE_INPUT_ENABLED] = it }
            voiceOutputEnabled?.let { prefs[KEY_VOICE_OUTPUT_ENABLED] = it }
            ttsSpeechRate?.let { prefs[KEY_TTS_SPEECH_RATE] = it.coerceIn(0.6f, 1.5f) }
            voiceOutputVolume?.let { prefs[KEY_VOICE_OUTPUT_VOLUME] = it.coerceIn(0.1f, 1.0f) }
            bargeInInterruptEnabled?.let { prefs[KEY_BARGE_IN_INTERRUPT] = it }
        }
    }

    suspend fun updateLastAgentTaskSummary(summary: String) {
        editPrefs { it[KEY_LAST_AGENT_TASK_SUMMARY] = summary }
    }

    suspend fun updateMemoryEnabled(enabled: Boolean) {
        editPrefs { it[KEY_MEMORY_ENABLED] = enabled }
    }

    suspend fun updateDefaultMemoryRetention(policyName: String) {
        editPrefs { it[KEY_DEFAULT_MEMORY_RETENTION] = policyName }
    }

    suspend fun updateAutoMemoryProposals(enabled: Boolean) {
        editPrefs { it[KEY_AUTO_MEMORY_PROPOSALS] = enabled }
    }

    suspend fun updateAutoSkillProposals(enabled: Boolean) {
        editPrefs { it[KEY_AUTO_SKILL_PROPOSALS] = enabled }
    }

    suspend fun updateProactiveSuggestionsEnabled(enabled: Boolean) {
        editPrefs { it[KEY_PROACTIVE_SUGGESTIONS_ENABLED] = enabled }
    }

    suspend fun updateNotificationPreferences(
        taskNotifications: Boolean? = null,
        completionNotifications: Boolean? = null,
        failureNotifications: Boolean? = null,
        scheduledNotifications: Boolean? = null,
        vibration: Boolean? = null
    ) {
        editPrefs { prefs ->
            taskNotifications?.let { prefs[KEY_TASK_NOTIFICATIONS] = it }
            completionNotifications?.let { prefs[KEY_COMPLETION_NOTIFICATIONS] = it }
            failureNotifications?.let { prefs[KEY_FAILURE_NOTIFICATIONS] = it }
            scheduledNotifications?.let { prefs[KEY_SCHEDULED_NOTIFICATIONS] = it }
            vibration?.let { prefs[KEY_NOTIFICATION_VIBRATION] = it }
        }
    }

    suspend fun updateReasoningModel(modelId: String) {
        val clean = modelId.trim()
        if (clean.isNotEmpty()) {
            val normalized = com.example.data.ai.VerifiedReasoningModels.normalizeReasoningModelId(clean)
            editPrefs { it[KEY_REASONING_MODEL] = normalized }
        }
    }

    suspend fun updateReasoningFallbackModel(modelId: String) {
        val clean = modelId.trim()
        if (clean.isNotEmpty()) {
            val normalized = com.example.data.ai.VerifiedReasoningModels.normalizeReasoningModelId(clean)
            editPrefs { it[KEY_REASONING_FALLBACK_MODEL] = normalized }
        }
    }

    suspend fun updateGemmaReasoningEnabled(enabled: Boolean) {
        editPrefs { it[KEY_GEMMA_REASONING_ENABLED] = enabled }
    }

    suspend fun updateReasoningRoutingMode(mode: com.example.data.ai.ReasoningRoutingMode) {
        editPrefs { it[KEY_REASONING_ROUTING_MODE] = mode.name }
    }

    suspend fun updateReasoningTimeoutSec(timeoutSec: Int) {
        editPrefs { it[KEY_REASONING_TIMEOUT_SEC] = timeoutSec.coerceIn(10, 90) }
    }

    suspend fun updateHeyNeyaEnabled(enabled: Boolean) {
        editPrefs { it[KEY_HEY_NEYA_ENABLED] = enabled }
    }

    suspend fun updateWakeDetectionMode(mode: com.example.assistant.WakeDetectionMode) {
        editPrefs {
            it[KEY_WAKE_DETECTION_MODE] = mode.name
            it[KEY_WAKE_FALLBACK_PREFERENCE] = when (mode) {
                com.example.assistant.WakeDetectionMode.MANUAL_ONLY -> "manual"
                com.example.assistant.WakeDetectionMode.DSP_HARDWARE -> "dsp"
                com.example.assistant.WakeDetectionMode.SOFTWARE_FALLBACK -> "software"
            }
        }
    }

    suspend fun updateAssistantOnboardingDismissed(dismissed: Boolean) {
        editPrefs { it[KEY_ASSISTANT_ONBOARDING_DISMISSED] = dismissed }
    }

    companion object {
        private val KEY_SELECTED_MODEL = stringPreferencesKey("selected_model_id")
        private val KEY_FALLBACK_MODEL = stringPreferencesKey("fallback_model_id")
        private val KEY_SELECTED_VOICE = stringPreferencesKey("selected_voice_name")
        private val KEY_PREFERRED_LANGUAGE = stringPreferencesKey("preferred_language")
        private val KEY_SYSTEM_INSTRUCTION = stringPreferencesKey("system_instruction")
        private val KEY_FRAME_RATE_FPS = intPreferencesKey("frame_rate_fps")
        private val KEY_MAX_DIMENSION_PX = intPreferencesKey("max_dimension_px")
        private val KEY_JPEG_QUALITY = intPreferencesKey("jpeg_quality")
        private val KEY_THEME = stringPreferencesKey("theme_preference")
        private val KEY_DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color_enabled")
        private val KEY_HAPTIC_FEEDBACK = booleanPreferencesKey("haptic_feedback_enabled")
        private val KEY_SOUND_EFFECTS = booleanPreferencesKey("sound_effects_enabled")
        private val KEY_ANIMATIONS_ENABLED = booleanPreferencesKey("animations_enabled")
        private val KEY_DEFAULT_LANDING_SCREEN = stringPreferencesKey("default_landing_screen")
        private val KEY_PRIVACY_ACCEPTED = booleanPreferencesKey("privacy_notice_accepted")
        private val KEY_LAST_SESSION_SUMMARY = stringPreferencesKey("last_session_summary")
        private val KEY_AGENT_MODE = stringPreferencesKey("agent_mode")
        private val KEY_AUTONOMY_LEVEL = stringPreferencesKey("autonomy_level")
        private val KEY_AGENT_TOOLS_ENABLED = booleanPreferencesKey("agent_tools_enabled")
        private val KEY_MAX_TASK_STEPS = intPreferencesKey("max_task_steps")
        private val KEY_MAX_ACTION_RETRIES = intPreferencesKey("max_action_retries")
        private val KEY_AGENT_TOOL_TIMEOUT_SEC = intPreferencesKey("agent_tool_timeout_sec")
        private val KEY_AUTO_CHECKPOINT_RECOVERY = booleanPreferencesKey("auto_checkpoint_recovery")
        private val KEY_BACKGROUND_AUTOMATION = booleanPreferencesKey("background_automation_enabled")
        private val KEY_VOICE_INPUT_ENABLED = booleanPreferencesKey("voice_input_enabled")
        private val KEY_VOICE_OUTPUT_ENABLED = booleanPreferencesKey("voice_output_enabled")
        private val KEY_TTS_SPEECH_RATE = floatPreferencesKey("tts_speech_rate")
        private val KEY_VOICE_OUTPUT_VOLUME = floatPreferencesKey("voice_output_volume")
        private val KEY_BARGE_IN_INTERRUPT = booleanPreferencesKey("barge_in_interrupt_enabled")
        private val KEY_LAST_AGENT_TASK_SUMMARY = stringPreferencesKey("last_agent_task_summary")
        private val KEY_MEMORY_ENABLED = booleanPreferencesKey("memory_enabled")
        private val KEY_DEFAULT_MEMORY_RETENTION = stringPreferencesKey("default_memory_retention")
        private val KEY_AUTO_MEMORY_PROPOSALS = booleanPreferencesKey("auto_memory_proposals_enabled")
        private val KEY_AUTO_SKILL_PROPOSALS = booleanPreferencesKey("auto_skill_proposals_enabled")
        private val KEY_PROACTIVE_SUGGESTIONS_ENABLED = booleanPreferencesKey("proactive_suggestions_enabled")
        private val KEY_TASK_NOTIFICATIONS = booleanPreferencesKey("task_notifications_enabled")
        private val KEY_COMPLETION_NOTIFICATIONS = booleanPreferencesKey("completion_notifications_enabled")
        private val KEY_FAILURE_NOTIFICATIONS = booleanPreferencesKey("failure_notifications_enabled")
        private val KEY_SCHEDULED_NOTIFICATIONS = booleanPreferencesKey("scheduled_notifications_enabled")
        private val KEY_NOTIFICATION_VIBRATION = booleanPreferencesKey("notification_vibration_enabled")
        private val KEY_REASONING_MODEL = stringPreferencesKey("reasoning_model_id")
        private val KEY_REASONING_FALLBACK_MODEL = stringPreferencesKey("reasoning_fallback_model_id")
        private val KEY_GEMMA_REASONING_ENABLED = booleanPreferencesKey("gemma_reasoning_enabled")
        private val KEY_REASONING_ROUTING_MODE = stringPreferencesKey("reasoning_routing_mode")
        private val KEY_REASONING_TIMEOUT_SEC = intPreferencesKey("reasoning_timeout_sec")
        private val KEY_HEY_NEYA_ENABLED = booleanPreferencesKey("hey_neya_enabled")
        private val KEY_WAKE_DETECTION_MODE = stringPreferencesKey("wake_detection_mode")
        private val KEY_ASSISTANT_ONBOARDING_DISMISSED = booleanPreferencesKey("assistant_onboarding_dismissed")
        private val KEY_WAKE_FALLBACK_PREFERENCE = stringPreferencesKey("wake_fallback_preference")
    }
}
