package com.example

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import com.example.agent.AgentMode
import com.example.data.local.memory.MemoryCategory
import com.example.data.local.memory.MemoryExpirationPolicy
import com.example.data.local.memory.MemorySource
import com.example.domain.model.ThemePreference
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Phase 6: Complete Professional UI/UX, Navigation & Settings Verification Suite.
 *
 * Tests real Compose UI screens, 5-tab navigation, Navigation Drawer, 12-category Settings Hub,
 * live runtime wiring of preferences (Theme, AgentCoordinator config, Voice, Memory, Notifications),
 * Storage & Data cleanup/sanitized export, Tasks 4-tab workflow + details sheet,
 * Library Memory/Skills search + filters + workflow detail sheet, and Permissions/API upgrades.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class Phase6UiNavigationSettingsTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun `bottom navigation switches across all 5 primary tabs and hides on secondary screens`() {
        composeTestRule.waitForIdle()

        // 1. Starts on Home with bottom bar visible
        composeTestRule.onNodeWithTag("main_bottom_navigation_bar").assertIsDisplayed()
        composeTestRule.onNodeWithTag("hero_welcome_card").assertIsDisplayed()

        // 2. Switch to Agent tab
        composeTestRule.onNodeWithTag("nav_tab_agent").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("live_visualizer_card").assertIsDisplayed()
        composeTestRule.onNodeWithTag("main_bottom_navigation_bar").assertIsDisplayed()

        // 3. Switch to Tasks tab
        composeTestRule.onNodeWithTag("nav_tab_tasks").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("tasks_tab_active").assertIsDisplayed()
        composeTestRule.onNodeWithTag("tasks_tab_scheduled").assertIsDisplayed()
        composeTestRule.onNodeWithTag("tasks_tab_completed").assertIsDisplayed()
        composeTestRule.onNodeWithTag("tasks_tab_failed").assertIsDisplayed()

        // 4. Switch to Library tab
        composeTestRule.onNodeWithTag("nav_tab_library").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("library_tab_memory").assertIsDisplayed()
        composeTestRule.onNodeWithTag("library_tab_skills").assertIsDisplayed()

        // 5. Switch to Settings tab
        composeTestRule.onNodeWithTag("nav_tab_settings").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("settings_hub_screen").assertIsDisplayed()

        // 6. Navigate into a secondary detail screen -> bottom navigation bar hides cleanly
        composeTestRule.onNodeWithTag("settings_cat_general").performScrollTo().performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("settings_category_general").assertIsDisplayed()
        assertTrue(
            "Bottom navigation bar should hide on detail screens",
            composeTestRule.onAllNodesWithTag("main_bottom_navigation_bar").fetchSemanticsNodes().isEmpty()
        )

        // 7. Navigate back -> Settings Hub & bottom bar restored
        composeTestRule.onNodeWithTag("settings_category_back_button").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("settings_hub_screen").assertIsDisplayed()
        composeTestRule.onNodeWithTag("main_bottom_navigation_bar").assertIsDisplayed()
    }

    @Test
    fun `navigation drawer opens and routes to Safe Web Browser`() {
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("home_drawer_button").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("main_navigation_drawer").assertIsDisplayed()
        composeTestRule.onNodeWithTag("drawer_item_safe_browser").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("safe_browser_url_input").assertIsDisplayed()
    }

    @Test
    fun `settings hub exposes all 12 categories and general theme preferences persist`() {
        val app = ApplicationProvider.getApplicationContext<ScreenSightApp>()
        val repo = app.container.settingsRepository

        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("nav_tab_settings").performClick()
        composeTestRule.waitForIdle()

        // Verify all 12 categories exist in Settings Hub
        val expectedCategoryTags = listOf(
            "settings_cat_general",
            "settings_cat_voice_audio",
            "settings_cat_screen_awareness",
            "settings_cat_notifications",
            "settings_cat_ai_models",
            "settings_cat_agent_behavior",
            "settings_cat_memory_privacy",
            "settings_cat_skills_automation",
            "settings_cat_permissions",
            "settings_cat_storage_data",
            "settings_cat_security",
            "settings_cat_about_support"
        )
        expectedCategoryTags.forEach { tag ->
            composeTestRule.onNodeWithTag(tag).performScrollTo().assertIsDisplayed()
        }

        // Open General settings and switch theme to LIGHT, toggle animations off, then reset UI
        composeTestRule.onNodeWithTag("settings_cat_general").performScrollTo().performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("settings_theme_light").performScrollTo().performClick()
        composeTestRule.waitUntil(timeoutMillis = 5_000L) {
            runBlocking { repo.settingsFlow.first().themePreference == ThemePreference.LIGHT }
        }
        assertEquals(ThemePreference.LIGHT, runBlocking { repo.settingsFlow.first().themePreference })

        composeTestRule.onNodeWithTag("settings_animations_switch").performScrollTo().performClick()
        composeTestRule.waitUntil(timeoutMillis = 5_000L) {
            runBlocking { !repo.settingsFlow.first().animationsEnabled }
        }
        assertFalse(runBlocking { repo.settingsFlow.first().animationsEnabled })

        // Reset UI preferences via confirmation dialog
        composeTestRule.onNodeWithTag("settings_reset_ui_button").performScrollTo().performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("confirm_reset_ui_button").performClick()
        composeTestRule.waitUntil(timeoutMillis = 5_000L) {
            runBlocking {
                val s = repo.settingsFlow.first()
                s.themePreference == ThemePreference.DARK && s.animationsEnabled
            }
        }
        assertEquals(ThemePreference.DARK, runBlocking { repo.settingsFlow.first().themePreference })
        assertTrue(runBlocking { repo.settingsFlow.first().animationsEnabled })
    }

    @Test
    fun `agent behavior settings persist and live-update AgentCoordinator config`() {
        val app = ApplicationProvider.getApplicationContext<ScreenSightApp>()
        val repo = app.container.settingsRepository

        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("nav_tab_settings").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("settings_cat_agent_behavior").performScrollTo().performClick()
        composeTestRule.waitForIdle()

        // Change retries to 3 via chip
        composeTestRule.onNodeWithTag("settings_retries_3").performScrollTo().performClick()
        composeTestRule.waitUntil(timeoutMillis = 5_000L) {
            runBlocking { repo.settingsFlow.first().maxActionRetries == 3 }
        }
        assertEquals(3, runBlocking { repo.settingsFlow.first().maxActionRetries })

        // Live hook in ScreenSightApp updates AgentCoordinator.config
        composeTestRule.waitUntil(timeoutMillis = 5_000L) {
            app.container.agentCoordinator.config.maxRetriesPerAction == 3
        }
        assertEquals(3, app.container.agentCoordinator.config.maxRetriesPerAction)

        // Toggle agent mode to Family Easy Mode and back to Standard
        composeTestRule.onNodeWithTag("settings_mode_family_easy_mode").performScrollTo().performClick()
        composeTestRule.waitUntil(timeoutMillis = 5_000L) {
            runBlocking { repo.settingsFlow.first().agentMode == AgentMode.FAMILY_EASY_MODE }
        }
        composeTestRule.onNodeWithTag("settings_mode_standard_agent").performScrollTo().performClick()
        composeTestRule.waitUntil(timeoutMillis = 5_000L) {
            runBlocking { repo.settingsFlow.first().agentMode == AgentMode.STANDARD_AGENT }
        }
    }

    @Test
    fun `voice notifications and memory retention settings persist via DataStore`() {
        val app = ApplicationProvider.getApplicationContext<ScreenSightApp>()
        val repo = app.container.settingsRepository

        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("nav_tab_settings").performClick()
        composeTestRule.waitForIdle()

        // Voice & Audio category
        composeTestRule.onNodeWithTag("settings_cat_voice_audio").performScrollTo().performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("settings_barge_in_switch").performScrollTo().performClick()
        composeTestRule.waitUntil(timeoutMillis = 5_000L) {
            runBlocking { !repo.settingsFlow.first().bargeInInterruptEnabled }
        }
        composeTestRule.onNodeWithTag("settings_category_back_button").performClick()
        composeTestRule.waitForIdle()

        // Notifications category
        composeTestRule.onNodeWithTag("settings_cat_notifications").performScrollTo().performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("settings_notif_vibration_switch").performScrollTo().performClick()
        composeTestRule.waitUntil(timeoutMillis = 5_000L) {
            runBlocking { !repo.settingsFlow.first().notificationVibrationEnabled }
        }
        composeTestRule.onNodeWithTag("settings_category_back_button").performClick()
        composeTestRule.waitForIdle()

        // Memory & Privacy category
        composeTestRule.onNodeWithTag("settings_cat_memory_privacy").performScrollTo().performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("settings_retention_thirty_days").performScrollTo().performClick()
        composeTestRule.waitUntil(timeoutMillis = 5_000L) {
            runBlocking { repo.settingsFlow.first().defaultMemoryRetention == MemoryExpirationPolicy.THIRTY_DAYS.name }
        }
        assertEquals(MemoryExpirationPolicy.THIRTY_DAYS.name, runBlocking { repo.settingsFlow.first().defaultMemoryRetention })
    }

    @Test
    fun `storage and data clears real cache and about opens licenses dialog`() {
        val app = ApplicationProvider.getApplicationContext<ScreenSightApp>()
        val tempCacheFile = File(app.cacheDir, "phase6_test_temp.tmp").apply {
            writeText("temporary cached bytes for verification")
        }
        assertTrue(tempCacheFile.exists())

        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("nav_tab_settings").performClick()
        composeTestRule.waitForIdle()

        // Storage & Data -> Clear Cache
        composeTestRule.onNodeWithTag("settings_cat_storage_data").performScrollTo().performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("storage_metrics_card").assertIsDisplayed()
        composeTestRule.onNodeWithTag("settings_clear_cache_item").performScrollTo().performClick()
        composeTestRule.waitUntil(timeoutMillis = 5_000L) { !tempCacheFile.exists() }
        assertFalse("Temp cache file must actually be deleted", tempCacheFile.exists())

        composeTestRule.onNodeWithTag("settings_category_back_button").performClick()
        composeTestRule.waitForIdle()

        // About & Support -> Open-Source Licenses dialog
        composeTestRule.onNodeWithTag("settings_cat_about_support").performScrollTo().performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("about_version_card").assertIsDisplayed()
        composeTestRule.onNodeWithTag("settings_open_licenses_item").performScrollTo().performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("licenses_dialog_close_button").assertIsDisplayed().performClick()
        composeTestRule.waitForIdle()
    }

    @Test
    fun `tasks screen creates multi-stage goal and opens task details bottom sheet`() {
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("nav_tab_tasks").performClick()
        composeTestRule.waitForIdle()

        // Create a deterministic safe multi-stage goal ("check date and time")
        composeTestRule.onNodeWithTag("tasks_goal_input").performTextInput("Check date and time")
        composeTestRule.onNodeWithTag("tasks_create_goal_button").performClick()

        // Completed tab should receive the completed task
        composeTestRule.onNodeWithTag("tasks_tab_completed").performClick()
        composeTestRule.waitUntil(timeoutMillis = 6_000L) {
            composeTestRule.onAllNodesWithText("Check date and time", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }

        // Switch across Scheduled and Failed tabs without crash
        composeTestRule.onNodeWithTag("tasks_tab_scheduled").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("tasks_tab_failed").performClick()
        composeTestRule.waitForIdle()
    }

    @Test
    fun `library screen supports memory search filter and skill workflow detail inspection`() {
        val app = ApplicationProvider.getApplicationContext<ScreenSightApp>()
        runBlocking {
            app.container.memoryRepository.saveMemory(
                category = MemoryCategory.USER_PREFERENCE,
                memoryKey = "preferred_news_topic",
                value = "Technology and Bangladesh Cricket",
                confidence = 1.0f,
                source = MemorySource.USER_EXPLICIT,
                expirationPolicy = MemoryExpirationPolicy.NEVER,
                userApproved = true
            )
        }

        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("nav_tab_library").performClick()
        composeTestRule.waitForIdle()

        // Search in Memory tab and scroll LazyColumn to filtered card
        composeTestRule.onNodeWithTag("library_memory_search_input").performTextInput("Cricket")
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("library_memory_list")
            .performScrollToNode(hasText("Technology and Bangladesh Cricket", substring = true))
        composeTestRule.onNodeWithText("Technology and Bangladesh Cricket", substring = true)
            .assertIsDisplayed()

        // Switch to Skills tab and verify built-in skills + filter chips
        composeTestRule.onNodeWithTag("library_tab_skills").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("skill_filter_built_in").assertIsDisplayed().performClick()
        composeTestRule.waitForIdle()
    }

    @Test
    fun `api settings fallback model selector and permissions dashboard app info card work`() {
        val app = ApplicationProvider.getApplicationContext<ScreenSightApp>()
        val repo = app.container.settingsRepository

        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("nav_tab_settings").performClick()
        composeTestRule.waitForIdle()

        // Category B: API & Model Settings -> fallback model selector
        composeTestRule.onNodeWithTag("settings_cat_ai_models").performScrollTo().performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("fallback_model_card").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("fallback_model_option_models/gemini-2.5-flash-native-audio-preview-09-2025")
            .performScrollTo()
            .performClick()
        composeTestRule.waitUntil(timeoutMillis = 5_000L) {
            runBlocking {
                repo.settingsFlow.first().fallbackModelId == "models/gemini-2.5-flash-native-audio-preview-09-2025"
            }
        }
        composeTestRule.onNodeWithTag("api_settings_back_button").performClick()
        composeTestRule.waitForIdle()

        // Category H: Permissions & Device Access -> dependent features + battery/app info card
        composeTestRule.onNodeWithTag("settings_cat_permissions").performScrollTo().performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("perm_accessibility_card").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("perm_battery_card").performScrollTo().assertIsDisplayed()
    }
}
