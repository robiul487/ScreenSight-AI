package com.example

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.example.assistant.AssistantCommandResult
import com.example.assistant.AssistantInvocationSource
import com.example.assistant.DefaultAssistantRoleStatus
import com.example.assistant.NeyaAssistantController
import com.example.assistant.NeyaAssistantRuntimeState
import com.example.assistant.NeyaRecognitionService
import com.example.assistant.NeyaVoiceInteractionService
import com.example.assistant.NeyaVoiceInteractionSession
import com.example.assistant.NeyaVoiceInteractionSessionService
import com.example.assistant.SystemAssistantProbe
import com.example.assistant.WakeCapabilityStatus
import com.example.assistant.WakeDetectionMode
import com.example.domain.model.AppSettings
import com.example.ui.components.NeyaAssistantSettingsSection
import com.example.ui.theme.ScreenSightTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class NeyaNativeAssistantTest {

    @get:Rule
    val composeRule = createComposeRule()

    private class FakeSystemAssistantProbe(
        override var sdkInt: Int = 34,
        var roleSupported: Boolean = true,
        var roleAvailable: Boolean = true,
        var roleHeld: Boolean = false,
        var visActive: Boolean = false,
        var micPermission: Boolean = true,
        var micPrivacyBlocked: Boolean = false,
        var privilegedHotword: Boolean = false
    ) : SystemAssistantProbe {
        override fun isRoleManagerSupported(): Boolean = roleSupported
        override fun isAssistantRoleAvailable(): Boolean = roleAvailable
        override fun isAssistantRoleHeld(): Boolean = roleHeld
        override fun isVoiceInteractionServiceActive(): Boolean = visActive
        override fun hasRecordAudioPermission(): Boolean = micPermission
        override fun isMicrophonePrivacyBlocked(): Boolean = micPrivacyBlocked
        override fun hasPrivilegedHotwordPermission(): Boolean = privilegedHotword
        override fun createRoleRequestIntent(): Intent? =
            if (roleSupported && roleAvailable) Intent("android.app.role.action.REQUEST_ROLE") else null

        override fun createVoiceInputSettingsIntent(): Intent =
            Intent(Settings.ACTION_VOICE_INPUT_SETTINGS)
    }

    private fun buildTestController(
        probe: FakeSystemAssistantProbe,
        clock: () -> Long = { System.currentTimeMillis() }
    ): NeyaAssistantController {
        val app = ApplicationProvider.getApplicationContext<ScreenSightApp>()
        val c = app.container
        return NeyaAssistantController(
            context = app,
            sessionManager = c.sessionManager,
            agentCoordinator = c.agentCoordinator,
            multiAgentOrchestrator = c.multiAgentOrchestrator,
            longTermTaskOrchestrator = c.longTermTaskOrchestrator,
            skillRepository = c.skillRepository,
            settingsRepository = c.settingsRepository,
            apiKeyStorage = c.apiKeyRepository,
            aiModelRouter = c.aiModelRouter,
            probe = probe,
            clock = clock
        )
    }

    @Test
    fun manifestDeclaresVoiceInteractionServicesAndAssistWithoutPrivilegedPermissions() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val pm = context.packageManager
        val pkgName = context.packageName

        // 1. VoiceInteractionService declared with BIND_VOICE_INTERACTION & metadata XML
        val visInfo = pm.getServiceInfo(
            ComponentName(pkgName, NeyaVoiceInteractionService::class.java.name),
            PackageManager.GET_META_DATA
        )
        assertTrue("VoiceInteractionService must be exported", visInfo.exported)
        assertEquals("android.permission.BIND_VOICE_INTERACTION", visInfo.permission)
        val xmlResId = visInfo.metaData?.getInt("android.voice_interaction", 0) ?: 0
        assertTrue("android.voice_interaction metadata XML must be valid", xmlResId != 0)
        assertNotNull(context.resources.getXml(xmlResId))

        // 2. VoiceInteractionSessionService declared with BIND_VOICE_INTERACTION
        val sessionInfo = pm.getServiceInfo(
            ComponentName(pkgName, NeyaVoiceInteractionSessionService::class.java.name),
            0
        )
        assertTrue("VoiceInteractionSessionService must be exported", sessionInfo.exported)
        assertEquals("android.permission.BIND_VOICE_INTERACTION", sessionInfo.permission)

        // 3. RecognitionService declared for AOSP VoiceInteractionServiceInfo compatibility
        val recInfo = pm.getServiceInfo(
            ComponentName(pkgName, NeyaRecognitionService::class.java.name),
            0
        )
        assertTrue("RecognitionService must be exported", recInfo.exported)

        // 4. MainActivity handles ACTION_ASSIST
        val assistIntent = Intent(Intent.ACTION_ASSIST).setPackage(pkgName)
        val resolvedAssist = pm.queryIntentActivities(assistIntent, 0)
        assertTrue(
            "MainActivity must resolve ACTION_ASSIST",
            resolvedAssist.any { it.activityInfo.name == MainActivity::class.java.name }
        )

        // 5. No restricted / privileged hotword permissions declared in AndroidManifest.xml
        val pkgInfo = pm.getPackageInfo(pkgName, PackageManager.GET_PERMISSIONS)
        val requested = pkgInfo.requestedPermissions?.toSet().orEmpty()
        assertFalse(requested.contains("android.permission.CAPTURE_AUDIO_HOTWORD"))
        assertFalse(requested.contains("android.permission.MANAGE_VOICE_KEYPHRASES"))
        assertFalse(requested.contains("android.permission.MANAGE_HOTWORD_DETECTION"))
    }

    @Test
    fun roleStatusMachineReflectsActiveCancelledNotSelectedAndUnsupportedAccurately() {
        val probe = FakeSystemAssistantProbe(roleSupported = true, roleAvailable = true, roleHeld = false)
        val controller = buildTestController(probe)

        assertEquals(DefaultAssistantRoleStatus.NOT_SELECTED, controller.state.value.roleStatus)
        assertTrue(controller.isUsingRoleManagerIntent())
        assertEquals("android.app.role.action.REQUEST_ROLE", controller.createDefaultAssistantRequestIntent().action)

        // User cancels chooser -> never falsely marks Active
        controller.refreshSystemStatus(wasCancelledByUser = true)
        assertEquals(DefaultAssistantRoleStatus.SELECTION_CANCELLED, controller.state.value.roleStatus)
        assertFalse(controller.state.value.roleStatus.isActive)

        // System grants ROLE_ASSISTANT -> status becomes ACTIVE on recheck
        probe.roleHeld = true
        controller.refreshSystemStatus(wasCancelledByUser = false)
        assertEquals(DefaultAssistantRoleStatus.ACTIVE, controller.state.value.roleStatus)
        assertTrue(controller.state.value.roleStatus.isActive)

        // Unsupported API / RoleManager unavailable -> falls back to ACTION_VOICE_INPUT_SETTINGS
        probe.roleHeld = false
        probe.visActive = false
        probe.roleSupported = false
        probe.roleAvailable = false
        controller.refreshSystemStatus(wasCancelledByUser = false)
        assertEquals(DefaultAssistantRoleStatus.UNSUPPORTED_DEVICE_OR_API, controller.state.value.roleStatus)
        assertEquals(Settings.ACTION_VOICE_INPUT_SETTINGS, controller.createDefaultAssistantRequestIntent().action)
    }

    @Test
    fun voiceInteractionServiceAndSessionLifecycleWorkWithoutCrashing() {
        val visController = Robolectric.buildService(NeyaVoiceInteractionService::class.java).create()
        val service = visController.get()
        service.onReady()

        val app = ApplicationProvider.getApplicationContext<ScreenSightApp>()
        assertTrue(app.container.neyaAssistantController.state.value.isVoiceInteractionServiceConnected)

        val sessionService = Robolectric.buildService(NeyaVoiceInteractionSessionService::class.java).create().get()
        val session = sessionService.onNewSession(null)
        assertTrue(session is NeyaVoiceInteractionSession)
        val view = (session as NeyaVoiceInteractionSession).onCreateContentView()
        assertNotNull("Assistant session content view must inflate cleanly", view)

        service.onShutdown()
        assertFalse(app.container.neyaAssistantController.state.value.isVoiceInteractionServiceConnected)
    }

    @Test
    fun duplicateSessionInvocationsAreDebouncedAndHideDoesNotCancelUnrelatedTasks() {
        var now = 10_000L
        val probe = FakeSystemAssistantProbe()
        val controller = buildTestController(probe, clock = { now })

        assertTrue(controller.onAssistantSessionShown(AssistantInvocationSource.VOICE_INTERACTION_SESSION))
        assertTrue(controller.state.value.isSessionShowing)

        // Duplicate within 600ms debounce window is ignored
        now += 150L
        assertFalse(controller.onAssistantSessionShown(AssistantInvocationSource.VOICE_INTERACTION_SESSION))

        // After debounce window, re-invocation is accepted cleanly
        now += 900L
        assertTrue(controller.onAssistantSessionShown(AssistantInvocationSource.VOICE_INTERACTION_SESSION))

        // Hiding sheet does not start or leak MediaProjection and leaves sessionShowing=false
        val app = ApplicationProvider.getApplicationContext<ScreenSightApp>()
        controller.onAssistantSessionHidden(stopLiveIfOwnedBySession = false)
        assertFalse(controller.state.value.isSessionShowing)
        assertFalse("Assistant invocation must never auto-start MediaProjection", app.container.sessionManager.isSharingScreen.value)
    }

    @Test
    fun assistantCommandExecutesLocallyWithoutGeminiLiveAndEnforcesSafetyPolicy() = runBlocking {
        val probe = FakeSystemAssistantProbe(micPermission = false)
        val controller = buildTestController(probe)

        // 1. Safe non-Live command works without Gemini Live active
        val timeResult = controller.submitAssistantCommand("what time is it")
        assertTrue(
            "Local time command should succeed without Gemini Live: $timeResult",
            timeResult is AssistantCommandResult.StartedQuickAction
        )

        // 2. High-risk financial command is still blocked by existing AgentSafetyManager
        val unsafeResult = controller.submitAssistantCommand("send money 5000 taka on bKash")
        assertTrue(
            "Financial/high-risk command must be blocked or gated by safety policy: $unsafeResult",
            unsafeResult is AssistantCommandResult.BlockedOrFailed ||
                unsafeResult is AssistantCommandResult.AnsweredDirectly
        )

        // 3. Requesting Live Voice without mic permission fails gracefully without crashing or starting MediaProjection
        val voiceMsg = controller.startOrFocusLiveVoiceFromAssistant()
        assertTrue(voiceMsg.contains("Microphone permission required", ignoreCase = true))
    }

    @Test
    fun wakeWordCapabilityReportIsHonestUnderThirdPartyAndMicPrivacyConstraints() {
        val probe = FakeSystemAssistantProbe(
            micPermission = true,
            micPrivacyBlocked = false,
            privilegedHotword = false
        )
        val controller = buildTestController(probe)
        val report = controller.state.value.capabilityReport

        assertEquals(WakeCapabilityStatus.REQUIRES_PRIVILEGED_DSP, report.dspStatus)
        assertEquals(WakeCapabilityStatus.MODEL_NOT_BUNDLED, report.softwareFallbackStatus)
        assertEquals(WakeDetectionMode.MANUAL_ONLY, report.effectiveActiveMode)
        assertFalse(report.isRealWakeDetectorAvailable)
        assertFalse(report.isCurrentlyListeningForWakeWord)

        // When OS microphone privacy toggle blocks mic, report reflects it immediately
        probe.micPrivacyBlocked = true
        controller.refreshSystemStatus()
        val blockedReport = controller.state.value.capabilityReport
        assertEquals(WakeCapabilityStatus.MIC_PRIVACY_BLOCKED, blockedReport.dspStatus)
        assertEquals(WakeCapabilityStatus.MIC_PRIVACY_BLOCKED, blockedReport.softwareFallbackStatus)
        assertTrue(controller.startOrFocusLiveVoiceFromAssistant().contains("privacy", ignoreCase = true))

        controller.stopWakeListeningIfActive()
        assertFalse(controller.state.value.capabilityReport.isCurrentlyListeningForWakeWord)
    }

    @Test
    fun settingsRepositoryPersistsAppLevelHeyNeyaPreferencesOnly() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<ScreenSightApp>()
        val repo = app.container.settingsRepository

        repo.updateHeyNeyaEnabled(true)
        repo.updateWakeDetectionMode(WakeDetectionMode.MANUAL_ONLY)
        repo.updateAssistantOnboardingDismissed(true)

        val saved = repo.settingsFlow.first()
        assertTrue(saved.heyNeyaEnabled)
        assertEquals(WakeDetectionMode.MANUAL_ONLY, saved.wakeDetectionMode)
        assertTrue(saved.assistantOnboardingDismissed)

        // Reset back to defaults for test isolation
        repo.updateHeyNeyaEnabled(false)
        repo.updateAssistantOnboardingDismissed(false)
    }

    @Test
    fun neyaAssistantSettingsSectionRendersHonestStatusAndOmitsFakeTestWakeButton() {
        var roleRequested = false
        var manualInvoked = false
        var heyNeyaToggled: Boolean? = null

        composeRule.setContent {
            ScreenSightTheme(darkTheme = true) {
                NeyaAssistantSettingsSection(
                    settings = AppSettings(heyNeyaEnabled = false, wakeDetectionMode = WakeDetectionMode.MANUAL_ONLY),
                    assistantState = NeyaAssistantRuntimeState(roleStatus = DefaultAssistantRoleStatus.NOT_SELECTED),
                    onRequestDefaultAssistantRole = { roleRequested = true },
                    onRefreshAssistantStatus = {},
                    onToggleHeyNeya = { heyNeyaToggled = it },
                    onSelectWakeMode = {},
                    onInvokeNeyaManually = { manualInvoked = true }
                )
            }
        }

        composeRule.onNodeWithTag("neya_assistant_settings_section").assertIsDisplayed()
        composeRule.onNodeWithTag("neya_role_status_badge").assertIsDisplayed()
        composeRule.onNodeWithTag("set_default_assistant_button").assertIsDisplayed().performClick()
        assertTrue(roleRequested)

        composeRule.onNodeWithTag("manual_invoke_neya_button").assertIsDisplayed().performClick()
        assertTrue(manualInvoked)

        composeRule.onNodeWithTag("hey_neya_toggle").performClick()
        assertEquals(true, heyNeyaToggled)

        // Must NOT render any fake "Test Wake Word" button
        composeRule.onNodeWithText("Test Wake Word", ignoreCase = true).assertDoesNotExist()
    }
}
