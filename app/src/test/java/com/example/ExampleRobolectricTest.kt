package com.example

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import com.example.agent.AgentActionRequest
import com.example.agent.AgentActionType
import com.example.agent.AgentStepStatus
import com.example.agent.TrustedContactsRepository
import com.example.data.local.ApiKeyRepository
import com.example.data.local.ApiKeySource
import com.example.data.local.EncryptedPayload
import com.example.data.local.KeystoreCipherProvider
import com.example.service.FrameProcessor
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Robolectric & Compose UI Integration Tests (Section 23).
 * Validates both existing ScreenSight AI regressions and new Android AI Agent / Family Easy Mode workflows:
 * 1. Existing ScreenSight AI branding, API & Model Settings, security warning, Privacy, and Appearance screens
 * 2. ApiKeyRepository saving, masking, reporting KEYSTORE_ENCRYPTED source, and deleting key
 * 3. FrameProcessor bitmap resizing, JPEG compression, and duplicate frame skipping
 * 4. SessionManager pause/resume and mic mute toggling
 * 5. Family Easy Mode UI: large Bangla Tap-to-Talk button, Bangla prompt, 4 quick cards, Standard Mode switch
 * 6. Agent Permissions & Setup: accurate Accessibility Disabled detection (never falsely reporting Enabled)
 * 7. Trusted Family Contacts: adding Bangla-nicknamed contact, safe resolution, ambiguity detection, deletion
 * 8. AgentActionExecutor: deterministic date/time, honest ACCESSIBILITY_DISABLED when off, Group C safety block
 * 9. AgentCoordinator: Group B confirmation pause, rejection/cancellation, and task step status updates
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun `app launches with ScreenSight AI branding and navigates to API settings`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertEquals("ScreenSight AI", context.getString(R.string.app_name))

        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("hero_welcome_card").assertIsDisplayed()
        composeTestRule.onNodeWithTag("home_settings_button").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("api_key_card").assertIsDisplayed()
        composeTestRule.onNodeWithTag("security_warning_card").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("model_selector_card").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `family easy mode displays Bangla voice-first controls and switches back to standard mode`() {
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("open_family_mode_button").performScrollTo().performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("family_main_prompt").assertIsDisplayed()
        composeTestRule.onNodeWithTag("family_tap_to_talk_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("family_quick_call").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("family_quick_youtube").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("family_quick_read_screen").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("family_quick_time").performScrollTo().assertIsDisplayed()

        composeTestRule.onNodeWithTag("family_switch_to_standard_button").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("hero_welcome_card").assertIsDisplayed()
    }

    @Test
    fun `agent permissions screen accurately reports accessibility disabled in Robolectric`() {
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("agent_permissions_summary_card").performScrollTo().performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("family_setup_checklist_card").assertIsDisplayed()
        composeTestRule.onNodeWithTag("perm_accessibility_card").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("Enable Accessibility Service").performScrollTo().assertIsDisplayed()

        val app = ApplicationProvider.getApplicationContext<ScreenSightApp>()
        val snapshot = app.container.agentPermissionManager.refresh(isScreenSharingActive = false)
        assertFalse("Accessibility must not report enabled when not granted", snapshot.isAgentControlReady)
    }

    @Test
    fun `trusted family contacts can be added, resolved in Bangla, detect ambiguity, and be deleted`() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<ScreenSightApp>()
        val contactsRepo = app.container.trustedContactsRepository

        // Navigate via UI and add a Bangla-nicknamed contact
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("quick_action_contacts").performScrollTo().performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("pick_system_contact_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("contact_name_input").performTextInput("Tariq Hasan")
        composeTestRule.onNodeWithTag("contact_phone_input").performTextInput("+8801711000111")
        composeTestRule.onNodeWithTag("contact_nickname_input").performTextInput("বড় ছেলে")
        composeTestRule.onNodeWithTag("contact_relation_input").performTextInput("ছেলে")
        composeTestRule.onNodeWithTag("save_trusted_contact_button").performScrollTo().performClick()
        composeTestRule.waitUntil(timeoutMillis = 5_000L) {
            composeTestRule.onAllNodesWithText("Tariq Hasan").fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithText("Tariq Hasan").performScrollTo().assertIsDisplayed()

        // Verify Bangla spoken query resolves to Tariq Hasan
        val singleMatch = contactsRepo.resolveContact("আমার বড় ছেলে")
        assertTrue(singleMatch is TrustedContactsRepository.ContactLookupResult.SingleMatch)
        assertEquals(
            "+8801711000111",
            (singleMatch as TrustedContactsRepository.ContactLookupResult.SingleMatch).contact.phoneNumber
        )

        // Add second son and verify generic "ছেলে" triggers Ambiguous instead of guessing
        val second = contactsRepo.addOrUpdateContact(
            name = "Rafiq Hasan",
            phoneNumber = "+8801711000222",
            nickname = "ছোট ছেলে",
            relationship = "ছেলে"
        )
        assertNotNull(second)
        val ambiguous = contactsRepo.resolveContact("ছেলে")
        assertTrue(ambiguous is TrustedContactsRepository.ContactLookupResult.Ambiguous)

        // Clean up contacts
        val all = contactsRepo.resolveContact("ছেলে") as TrustedContactsRepository.ContactLookupResult.Ambiguous
        all.candidates.forEach { contactsRepo.removeContact(it.id) }
    }

    @Test
    fun `agent action executor handles date-time, missing accessibility, and blocks high-risk actions`() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<ScreenSightApp>()
        val executor = app.container.agentActionExecutor

        // 1. Deterministic date/time succeeds without needing Accessibility
        val dateResult = executor.execute(AgentActionRequest(actionType = AgentActionType.GET_DATE_TIME))
        assertTrue(dateResult.success)
        assertEquals("DATE_TIME_OK", dateResult.status)
        assertTrue(dateResult.summaryBn.contains("আজ"))

        // 2. Reading screen without Accessibility honestly fails with ACCESSIBILITY_DISABLED
        val readResult = executor.execute(AgentActionRequest(actionType = AgentActionType.READ_SCREEN))
        assertFalse(readResult.success)
        assertEquals("ACCESSIBILITY_DISABLED", readResult.status)
        assertTrue(readResult.needsAccessibilityPermission)

        // 3. High-risk payment action is unconditionally blocked
        val blockedResult = executor.execute(
            AgentActionRequest(
                actionType = AgentActionType.CLICK_ELEMENT,
                parameters = mapOf("target_text" to "Confirm Payment bKash")
            )
        )
        assertFalse(blockedResult.success)
        assertTrue(blockedResult.blockedBySafety)
        assertEquals("BLOCKED_HIGH_RISK", blockedResult.status)

        // 4. Invalid alarm time fails safely without falsely reporting success
        val badAlarm = executor.execute(
            AgentActionRequest(
                actionType = AgentActionType.SET_ALARM,
                parameters = mapOf("hour_24" to "99", "minute" to "0")
            )
        )
        assertFalse(badAlarm.success)
        assertEquals("INVALID_TIME", badAlarm.status)
    }

    @Test
    fun `agent coordinator pauses for Group B confirmation and supports immediate cancellation`() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<ScreenSightApp>()
        val coordinator = app.container.agentCoordinator
        coordinator.clearTaskHistory()

        coordinator.executeDirectQuickAction(
            AgentActionRequest(
                actionType = AgentActionType.OPEN_DIALER,
                parameters = mapOf("phone_number" to "+8801711000999", "display_name" to "Test Family")
            ),
            isGeminiAudioActive = true
        )

        delay(100L)
        assertNotNull("Group B dialer action must pause for confirmation", coordinator.pendingConfirmation.value)
        assertTrue(coordinator.taskSteps.value.any { it.status == AgentStepStatus.WAITING_CONFIRMATION })

        // Immediately cancel task
        coordinator.cancelCurrentTask()
        delay(50L)
        assertEquals(null, coordinator.pendingConfirmation.value)
        assertFalse(coordinator.isTaskRunning.value)
        assertTrue(coordinator.taskSteps.value.any { it.status == AgentStepStatus.CANCELLED })
    }

    @Test
    fun `frame processor compresses bitmap to JPEG and skips duplicate unchanged frames`() {
        val processor = FrameProcessor(maxDimensionPx = 640, jpegQuality = 70)
        val bitmap = Bitmap.createBitmap(1080, 1920, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.rgb(20, 40, 80))

        val firstResult = processor.processBitmap(bitmap, currentTimeMs = 1000L)
        assertNotNull(firstResult)
        assertFalse(firstResult!!.wasSkippedDuplicate)
        assertEquals(640, firstResult.height)
        assertEquals(360, firstResult.width)
        assertTrue(firstResult.jpegBytes.isNotEmpty())

        val secondResult = processor.processBitmap(bitmap, currentTimeMs = 2000L)
        assertNotNull(secondResult)
        assertTrue(secondResult!!.wasSkippedDuplicate)
        bitmap.recycle()
    }

    @Test
    fun `api key repository saves, masks, tracks KEYSTORE_ENCRYPTED source, and deletes key`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val testCipher = object : KeystoreCipherProvider {
            override fun encrypt(plaintext: ByteArray): EncryptedPayload {
                return EncryptedPayload(plaintext.reversedArray(), byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12))
            }

            override fun decrypt(payload: EncryptedPayload): ByteArray {
                return payload.ciphertext.reversedArray()
            }
        }
        val repo = ApiKeyRepository(
            context = context,
            cipherProvider = testCipher,
            allowBuildConfigFallback = false
        )
        repo.clearApiKey()
        assertFalse(repo.hasApiKeyFlow.value)
        assertEquals(ApiKeySource.NONE, repo.keySourceFlow.value)

        val sampleKey = "DUMMY_TEST_API_KEY_SCREENSIGHT_1234567890"
        repo.saveApiKey(sampleKey)
        assertTrue(repo.hasApiKeyFlow.value)
        assertEquals(ApiKeySource.KEYSTORE_ENCRYPTED, repo.keySourceFlow.value)
        assertEquals(sampleKey, repo.getApiKey())
        assertTrue(repo.maskedApiKeyFlow.value.startsWith("DUMM"))
        assertTrue(repo.maskedApiKeyFlow.value.endsWith("7890"))

        repo.clearApiKey()
        assertFalse(repo.hasApiKeyFlow.value)
        assertEquals(ApiKeySource.NONE, repo.keySourceFlow.value)
        assertEquals("", repo.getApiKey())
    }

    @Test
    fun `session manager synchronizes screen sharing pause state and microphone mute`() {
        val app = ApplicationProvider.getApplicationContext<ScreenSightApp>()
        val sessionManager = app.container.sessionManager

        sessionManager.onScreenSharingStateChanged(isSharing = true)
        assertTrue(sessionManager.isSharingScreen.value)
        assertFalse(sessionManager.isScreenCapturePaused.value)

        sessionManager.onScreenCapturePausedChanged(isPaused = true)
        assertTrue(sessionManager.isScreenCapturePaused.value)

        sessionManager.onScreenCapturePausedChanged(isPaused = false)
        assertFalse(sessionManager.isScreenCapturePaused.value)

        val initialMute = sessionManager.audioCaptureManager.isMuted.value
        val toggled = sessionManager.toggleMicrophoneMute()
        assertEquals(!initialMute, toggled)
        sessionManager.audioCaptureManager.setMuted(false)

        sessionManager.stopSession(clearTranscriptOnStop = true)
        assertFalse(sessionManager.isSharingScreen.value)
        assertFalse(sessionManager.isScreenCapturePaused.value)
    }

    @Test
    fun `navigating to privacy and appearance screens works cleanly`() {
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("privacy_info_card").performScrollTo().performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Transparent Open-Source Architecture").assertIsDisplayed()
        composeTestRule.onNodeWithTag("privacy_back_button").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("home_appearance_button").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Theme & Display").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp")
    fun `live assistant console displays Phase 2 agent execution state badge and task logs tab`() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<ScreenSightApp>()
        val coordinator = app.container.agentCoordinator
        coordinator.clearTaskHistory()

        // Trigger a quick deterministic action so state, step history, and sanitized logs populate
        coordinator.onUserRequestReceived("What time is it?")
        coordinator.executeDirectQuickAction(
            AgentActionRequest(actionType = AgentActionType.GET_DATE_TIME),
            isGeminiAudioActive = true
        )
        delay(120L)

        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("open_assistant_console_button").performScrollTo().performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("agent_execution_state_badge").assertIsDisplayed()
        composeTestRule.onNodeWithTag("console_tab_agent_activity").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("agent_activity_list").assertIsDisplayed()

        composeTestRule.onNodeWithTag("console_tab_task_logs").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("agent_task_logs_list").assertIsDisplayed()

        assertTrue(coordinator.taskLogger.recentEntries.value.isNotEmpty())
        assertTrue(coordinator.taskSteps.value.isNotEmpty())
    }
}
