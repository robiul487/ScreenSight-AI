package com.example

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.ApiKeyRepository
import com.example.data.local.ApiKeySource
import com.example.data.local.EncryptedPayload
import com.example.data.local.KeystoreCipherProvider
import com.example.service.FrameProcessor
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
 * Robolectric & Compose UI Integration Tests (Section 17 & Priority 10).
 * Validates:
 * 1. Starting application with ScreenSight AI branding and navigating to API Settings
 * 2. Verifying security warning and APK BuildConfig extraction notice in API Settings
 * 3. ApiKeyRepository saving, masking, reporting KEYSTORE_ENCRYPTED source, and deleting key
 * 4. FrameProcessor bitmap resizing, JPEG compression, and duplicate static frame skipping
 * 5. SessionManager pause/resume state synchronization and mic mute toggling
 * 6. Navigating to Privacy & Appearance screens cleanly
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

        val sampleKey = "AIzaSyTestKeyForScreenSightAi1234567890"
        repo.saveApiKey(sampleKey)
        assertTrue(repo.hasApiKeyFlow.value)
        assertEquals(ApiKeySource.KEYSTORE_ENCRYPTED, repo.keySourceFlow.value)
        assertEquals(sampleKey, repo.getApiKey())
        assertTrue(repo.maskedApiKeyFlow.value.startsWith("AIza"))
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
}
