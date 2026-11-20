package com.example

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import com.example.domain.model.AutonomyLevel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Automated Robolectric + Compose UI verification suite for the Autonomous Agent Command Hub
 * & Functional UX Upgrade across Home, Agent, Tasks, Library, and Settings.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h891dp")
class Phase7AgentCommandHubTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun `home autonomy switcher persists to DataStore and updates AgentCoordinator live`() {
        val app = ApplicationProvider.getApplicationContext<ScreenSightApp>()
        val repo = app.container.settingsRepository

        composeTestRule.waitForIdle()

        // Default is BALANCED; switch to ASK_FIRST on Home Command Hub
        composeTestRule.onNodeWithTag("home_autonomy_ask_first").performScrollTo().performClick()
        composeTestRule.waitUntil(timeoutMillis = 5_000L) {
            runBlocking { repo.settingsFlow.first().autonomyLevel == AutonomyLevel.ASK_FIRST }
        }
        composeTestRule.waitUntil(timeoutMillis = 5_000L) {
            app.container.agentCoordinator.autonomyLevel == AutonomyLevel.ASK_FIRST
        }
        assertEquals(AutonomyLevel.ASK_FIRST, app.container.agentCoordinator.autonomyLevel)

        // Switch back to BALANCED
        composeTestRule.onNodeWithTag("home_autonomy_balanced").performScrollTo().performClick()
        composeTestRule.waitUntil(timeoutMillis = 5_000L) {
            runBlocking { repo.settingsFlow.first().autonomyLevel == AutonomyLevel.BALANCED }
        }
        composeTestRule.waitUntil(timeoutMillis = 5_000L) {
            app.container.agentCoordinator.autonomyLevel == AutonomyLevel.BALANCED
        }
        assertEquals(AutonomyLevel.BALANCED, app.container.agentCoordinator.autonomyLevel)
    }

    @Test
    fun `home readiness fix chips and custom mission dialogs work cleanly`() {
        composeTestRule.waitForIdle()

        // 4 actionable permission fix chips are visible on Home readiness card
        composeTestRule.onNodeWithTag("readiness_chip_api").assertIsDisplayed()
        composeTestRule.onNodeWithTag("readiness_chip_control").assertIsDisplayed()
        composeTestRule.onNodeWithTag("readiness_chip_mic").assertIsDisplayed()
        composeTestRule.onNodeWithTag("readiness_chip_overlay").assertIsDisplayed()

        // Floating Agent Overlay button is visible in Command Hub
        composeTestRule.onNodeWithTag("home_floating_overlay_button").performScrollTo().assertIsDisplayed()

        // Custom Open Any App mission dialog opens from Agent Missions card
        composeTestRule.onNodeWithTag("quick_action_youtube").performScrollTo().performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("custom_open_app_input").assertIsDisplayed()
        composeTestRule.onNodeWithText("Cancel").performClick()
        composeTestRule.waitForIdle()

        // Custom Alarm mission dialog opens from Agent Missions card
        composeTestRule.onNodeWithTag("quick_action_alarm").performScrollTo().performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("custom_alarm_hour_input").assertIsDisplayed()
        composeTestRule.onNodeWithTag("custom_alarm_minute_input").assertIsDisplayed()
        composeTestRule.onNodeWithText("Cancel").performClick()
        composeTestRule.waitForIdle()

        // Tapping API readiness chip navigates straight to API Settings
        composeTestRule.onNodeWithTag("readiness_chip_api").performScrollTo().performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("api_key_input").assertIsDisplayed()
    }

    @Test
    fun `dual mode agent tab accepts text command while idle and runs locally`() {
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("nav_tab_agent").performClick()
        composeTestRule.waitForIdle()

        // Compact non-clipped console tabs + Floating Pill dock button are visible
        composeTestRule.onNodeWithTag("console_tab_transcript").assertIsDisplayed()
        composeTestRule.onNodeWithTag("console_tab_agent_activity").assertIsDisplayed()
        composeTestRule.onNodeWithTag("console_tab_task_logs").assertIsDisplayed()
        composeTestRule.onNodeWithTag("console_tab_diagnostics").assertIsDisplayed()
        composeTestRule.onNodeWithTag("live_dock_floating_button").assertIsDisplayed()

        // Type a command while session is IDLE -> send button MUST be enabled (Dual-Mode)
        composeTestRule.onNodeWithTag("live_text_input").performTextInput("Check date and time")
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("send_text_message_button").assertIsEnabled().performClick()

        // Should execute locally and auto-switch to Steps tab showing agent_activity_list
        composeTestRule.waitUntil(timeoutMillis = 6_000L) {
            composeTestRule.onAllNodesWithTag("agent_activity_list").fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithTag("agent_activity_list").assertIsDisplayed()
    }

    @Test
    fun `tasks tab shows top schedule action and starter mission chips`() {
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("nav_tab_tasks").performClick()
        composeTestRule.waitForIdle()

        // All 4 tabs visible simultaneously in fixed TabRow without clipping
        composeTestRule.onNodeWithTag("tasks_tab_active").assertIsDisplayed()
        composeTestRule.onNodeWithTag("tasks_tab_scheduled").assertIsDisplayed()
        composeTestRule.onNodeWithTag("tasks_tab_completed").assertIsDisplayed()
        composeTestRule.onNodeWithTag("tasks_tab_failed").assertIsDisplayed()

        // Starter mission chips visible on Active tab
        composeTestRule.onNodeWithTag("task_starter_time").assertIsDisplayed()
        composeTestRule.onNodeWithTag("task_starter_schedule").assertIsDisplayed()

        // Top bar + Schedule button opens Create Scheduled Task dialog from any tab
        composeTestRule.onNodeWithTag("tasks_top_schedule_button").assertIsDisplayed().performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Create Scheduled Task").assertIsDisplayed()
        composeTestRule.onNodeWithText("Cancel").performClick()
        composeTestRule.waitForIdle()
    }

    @Test
    fun `library empty state starter memory template prefills memory editor dialog`() {
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("nav_tab_library").performClick()
        composeTestRule.waitForIdle()

        // Scroll LazyColumn to starter memory templates when empty
        composeTestRule.onNodeWithTag("library_memory_list")
            .performScrollToNode(hasTestTag("memory_template_language"))
        composeTestRule.onNodeWithTag("memory_template_language").assertIsDisplayed().performClick()
        composeTestRule.waitForIdle()

        // Dialog opens pre-filled with preferred_language key
        composeTestRule.onNodeWithTag("memory_editor_key_input").assertIsDisplayed()
        composeTestRule.onNodeWithText("preferred_language", substring = true).assertIsDisplayed()
        composeTestRule.onNodeWithText("Cancel").performClick()
        composeTestRule.waitForIdle()
    }

    @Test
    fun `settings hub live search filters categories by keyword`() {
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("nav_tab_settings").performClick()
        composeTestRule.waitForIdle()

        // Search by deep keyword "battery" -> matches Permissions & Device Access only
        composeTestRule.onNodeWithTag("settings_search_input").performTextInput("battery")
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("settings_cat_permissions").assertIsDisplayed()
        assertTrue(
            "Unrelated General category should be filtered out when searching for 'battery'",
            composeTestRule.onAllNodesWithTag("settings_cat_general").fetchSemanticsNodes().isEmpty()
        )

        // Clear search -> all groups restored
        composeTestRule.onNodeWithTag("settings_search_clear_button").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("settings_cat_general").assertIsDisplayed()

        // Search by "autonomy" -> matches Agent Behavior, open it and verify Autonomy Policy chips
        composeTestRule.onNodeWithTag("settings_search_input").performTextInput("autonomy")
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("settings_cat_agent_behavior").assertIsDisplayed().performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("settings_autonomy_ask_first").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("settings_autonomy_balanced").performScrollTo().assertIsDisplayed()
    }
}
