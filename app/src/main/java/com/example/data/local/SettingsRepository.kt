package com.example.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.agent.AgentMode
import com.example.domain.model.AppSettings
import com.example.domain.model.GeminiLiveModel
import com.example.domain.model.GeminiVoice
import com.example.domain.model.ThemePreference
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "screensight_settings")

class SettingsRepository(private val dataStore: DataStore<Preferences>) {

    constructor(context: Context) : this(context.applicationContext.dataStore)

    val settingsFlow: Flow<AppSettings> = dataStore.data.map { prefs ->
        val themeStr = prefs[KEY_THEME] ?: ThemePreference.DARK.name
        val themePref = runCatching { ThemePreference.valueOf(themeStr) }.getOrDefault(ThemePreference.DARK)
        val rawStoredModel = prefs[KEY_SELECTED_MODEL]
        val activeModelId = if (rawStoredModel.isNullOrBlank() || GeminiLiveModel.isLegacyShutDownModel(rawStoredModel)) {
            GeminiLiveModel.DEFAULT_MODEL.id
        } else {
            GeminiLiveModel.normalizeModelId(rawStoredModel)
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

        AppSettings(
            selectedModelId = activeModelId,
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
            hasAcceptedPrivacyNotice = prefs[KEY_PRIVACY_ACCEPTED] ?: false,
            lastSessionSummary = prefs[KEY_LAST_SESSION_SUMMARY] ?: "No session recorded yet",
            agentMode = agentMode,
            agentToolsEnabled = prefs[KEY_AGENT_TOOLS_ENABLED] ?: true,
            lastAgentTaskSummary = prefs[KEY_LAST_AGENT_TASK_SUMMARY] ?: "No agent task recorded yet",
            memoryEnabled = prefs[KEY_MEMORY_ENABLED] ?: true,
            proactiveSuggestionsEnabled = prefs[KEY_PROACTIVE_SUGGESTIONS_ENABLED] ?: true
        )
    }

    suspend fun updateSelectedModel(modelId: String) {
        val clean = modelId.trim()
        if (clean.isNotEmpty()) {
            val normalized = GeminiLiveModel.normalizeModelId(clean)
            dataStore.edit { it[KEY_SELECTED_MODEL] = normalized }
        }
    }

    suspend fun updateSelectedVoice(voiceName: String) {
        dataStore.edit { it[KEY_SELECTED_VOICE] = voiceName }
    }

    suspend fun updatePreferredLanguage(language: String) {
        dataStore.edit { it[KEY_PREFERRED_LANGUAGE] = language }
    }

    suspend fun updateSystemInstruction(instruction: String) {
        dataStore.edit {
            it[KEY_SYSTEM_INSTRUCTION] = instruction.ifBlank { AppSettings.DEFAULT_SYSTEM_INSTRUCTION }
        }
    }

    suspend fun updateFrameCaptureConfig(fps: Int, maxDimPx: Int, jpegQuality: Int) {
        dataStore.edit {
            it[KEY_FRAME_RATE_FPS] = fps.coerceIn(1, 2)
            it[KEY_MAX_DIMENSION_PX] = maxDimPx.coerceIn(640, 1920)
            it[KEY_JPEG_QUALITY] = jpegQuality.coerceIn(40, 90)
        }
    }

    suspend fun updateThemePreference(theme: ThemePreference) {
        dataStore.edit { it[KEY_THEME] = theme.name }
    }

    suspend fun updateDynamicColor(enabled: Boolean) {
        dataStore.edit { it[KEY_DYNAMIC_COLOR] = enabled }
    }

    suspend fun updateHapticFeedback(enabled: Boolean) {
        dataStore.edit { it[KEY_HAPTIC_FEEDBACK] = enabled }
    }

    suspend fun setPrivacyNoticeAccepted(accepted: Boolean) {
        dataStore.edit { it[KEY_PRIVACY_ACCEPTED] = accepted }
    }

    suspend fun updateLastSessionSummary(summary: String) {
        dataStore.edit { it[KEY_LAST_SESSION_SUMMARY] = summary }
    }

    suspend fun updateAgentMode(mode: AgentMode) {
        dataStore.edit { it[KEY_AGENT_MODE] = mode.name }
    }

    suspend fun updateAgentToolsEnabled(enabled: Boolean) {
        dataStore.edit { it[KEY_AGENT_TOOLS_ENABLED] = enabled }
    }

    suspend fun updateLastAgentTaskSummary(summary: String) {
        dataStore.edit { it[KEY_LAST_AGENT_TASK_SUMMARY] = summary }
    }

    suspend fun updateMemoryEnabled(enabled: Boolean) {
        dataStore.edit { it[KEY_MEMORY_ENABLED] = enabled }
    }

    suspend fun updateProactiveSuggestionsEnabled(enabled: Boolean) {
        dataStore.edit { it[KEY_PROACTIVE_SUGGESTIONS_ENABLED] = enabled }
    }

    companion object {
        private val KEY_SELECTED_MODEL = stringPreferencesKey("selected_model_id")
        private val KEY_SELECTED_VOICE = stringPreferencesKey("selected_voice_name")
        private val KEY_PREFERRED_LANGUAGE = stringPreferencesKey("preferred_language")
        private val KEY_SYSTEM_INSTRUCTION = stringPreferencesKey("system_instruction")
        private val KEY_FRAME_RATE_FPS = intPreferencesKey("frame_rate_fps")
        private val KEY_MAX_DIMENSION_PX = intPreferencesKey("max_dimension_px")
        private val KEY_JPEG_QUALITY = intPreferencesKey("jpeg_quality")
        private val KEY_THEME = stringPreferencesKey("theme_preference")
        private val KEY_DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color_enabled")
        private val KEY_HAPTIC_FEEDBACK = booleanPreferencesKey("haptic_feedback_enabled")
        private val KEY_PRIVACY_ACCEPTED = booleanPreferencesKey("privacy_notice_accepted")
        private val KEY_LAST_SESSION_SUMMARY = stringPreferencesKey("last_session_summary")
        private val KEY_AGENT_MODE = stringPreferencesKey("agent_mode")
        private val KEY_AGENT_TOOLS_ENABLED = booleanPreferencesKey("agent_tools_enabled")
        private val KEY_LAST_AGENT_TASK_SUMMARY = stringPreferencesKey("last_agent_task_summary")
        private val KEY_MEMORY_ENABLED = booleanPreferencesKey("memory_enabled")
        private val KEY_PROACTIVE_SUGGESTIONS_ENABLED = booleanPreferencesKey("proactive_suggestions_enabled")
    }
}
