package com.example

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.domain.model.GeminiLiveModel
import com.example.domain.model.ThemePreference
import com.example.ui.ScreenSightViewModel
import com.example.ui.screens.ApiSettingsScreen
import com.example.ui.screens.AppearanceSettingsScreen
import com.example.ui.screens.HomeScreen
import com.example.ui.screens.LiveAssistantScreen
import com.example.ui.screens.PrivacySecurityScreen
import com.example.ui.theme.ScreenSightTheme

object ScreenRoutes {
    const val ROUTE_HOME = "home"
    const val ROUTE_LIVE_ASSISTANT = "live_assistant"
    const val ROUTE_API_SETTINGS = "api_settings"
    const val ROUTE_APPEARANCE_SETTINGS = "appearance_settings"
    const val ROUTE_PRIVACY_SECURITY = "privacy_security"
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val appContainer = (application as ScreenSightApp).container

        setContent {
            val viewModel: ScreenSightViewModel = viewModel(
                factory = ScreenSightViewModel.provideFactory(appContainer)
            )
            val settings by viewModel.settings.collectAsStateWithLifecycle()

            val useDarkTheme = when (settings.themePreference) {
                ThemePreference.SYSTEM -> isSystemInDarkTheme()
                ThemePreference.DARK -> true
                ThemePreference.LIGHT -> false
            }

            ScreenSightTheme(
                darkTheme = useDarkTheme,
                dynamicColor = settings.dynamicColorEnabled
            ) {
                ScreenSightAppNavHost(viewModel = viewModel)
            }
        }
    }
}

@Composable
fun ScreenSightAppNavHost(
    viewModel: ScreenSightViewModel
) {
    val context = LocalContext.current
    val navController = rememberNavController()

    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val hasApiKey by viewModel.hasApiKey.collectAsStateWithLifecycle()
    val maskedApiKey by viewModel.maskedApiKey.collectAsStateWithLifecycle()
    val apiKeySource by viewModel.apiKeySource.collectAsStateWithLifecycle()
    val sessionState by viewModel.sessionState.collectAsStateWithLifecycle()
    val isSharingScreen by viewModel.isSharingScreen.collectAsStateWithLifecycle()
    val isMicMuted by viewModel.isMicMuted.collectAsStateWithLifecycle()
    val isUserSpeaking by viewModel.isUserSpeaking.collectAsStateWithLifecycle()
    val micLevel by viewModel.micLevel.collectAsStateWithLifecycle()
    val isAiMuted by viewModel.isAiMuted.collectAsStateWithLifecycle()
    val isAiSpeaking by viewModel.isAiSpeaking.collectAsStateWithLifecycle()
    val framesSentCount by viewModel.framesSentCount.collectAsStateWithLifecycle()
    val framesSkippedCount by viewModel.framesSkippedCount.collectAsStateWithLifecycle()
    val lastFrameDimensions by viewModel.lastFrameDimensions.collectAsStateWithLifecycle()
    val connectionQuality by viewModel.connectionQuality.collectAsStateWithLifecycle()
    val transcript by viewModel.transcript.collectAsStateWithLifecycle()
    val activeError by viewModel.activeError.collectAsStateWithLifecycle()
    val validationState by viewModel.apiValidationState.collectAsStateWithLifecycle()
    val showPrivacyDialog by viewModel.showFirstSessionPrivacyDialog.collectAsStateWithLifecycle()

    // Tracks whether screen-sharing consent should chain automatically after runtime mic/notification permissions finish
    var pendingScreenShareAfterPermissions by rememberSaveable { mutableStateOf(false) }

    // Step 3: Official Android MediaProjection consent dialog launcher
    val mediaProjectionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val granted = result.resultCode == Activity.RESULT_OK && result.data != null
        // ScreenCaptureService promotes itself to foreground and creates VirtualDisplay while
        // MainActivity is still in the foreground, then automatically minimizes to the phone Home screen.
        viewModel.onScreenCapturePermissionResult(
            granted = granted,
            resultCode = result.resultCode,
            data = result.data
        )
    }

    fun launchScreenCaptureConsent() {
        val projectionManager =
            context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        viewModel.onRequestingScreenCapturePermission()
        mediaProjectionLauncher.launch(projectionManager.createScreenCaptureIntent())
    }

    // Step 2: Overlay permission launcher ("Display over other apps") so the floating corner pill is shown
    val overlaySettingsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        // Proceed to MediaProjection consent whether user enabled overlay or skipped
        launchScreenCaptureConsent()
    }

    fun launchScreenSharingWithOverlayCheck() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(context)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${context.packageName}")
            )
            val launched = runCatching { overlaySettingsLauncher.launch(intent) }.isSuccess
            if (!launched) {
                launchScreenCaptureConsent()
            }
        } else {
            launchScreenCaptureConsent()
        }
    }

    // Step 1: Runtime permission launcher for RECORD_AUDIO + POST_NOTIFICATIONS (Android 13+)
    // Sequentially chains to Step 2/3 only AFTER the runtime permission dialog finishes!
    val audioAndNotificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val audioGranted = permissions[Manifest.permission.RECORD_AUDIO] == true ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (audioGranted) {
            viewModel.onMicrophonePermissionGranted()
        } else {
            viewModel.onMicrophonePermissionDenied()
        }
        if (pendingScreenShareAfterPermissions) {
            pendingScreenShareAfterPermissions = false
            if (!isSharingScreen) {
                launchScreenSharingWithOverlayCheck()
            }
        }
    }

    fun requestPermissionsAndLaunchLive(autoPromptScreenShare: Boolean = false) {
        val needed = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.RECORD_AUDIO)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                needed.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (needed.isNotEmpty()) {
            // Wait for runtime permissions dialog to complete before launching Overlay / MediaProjection intents
            pendingScreenShareAfterPermissions = autoPromptScreenShare
            audioAndNotificationPermissionLauncher.launch(needed.toTypedArray())
        } else {
            viewModel.onMicrophonePermissionGranted()
            if (autoPromptScreenShare && !isSharingScreen) {
                launchScreenSharingWithOverlayCheck()
            }
        }
    }

    NavHost(
        navController = navController,
        startDestination = ScreenRoutes.ROUTE_HOME
    ) {
        composable(ScreenRoutes.ROUTE_HOME) {
            HomeScreen(
                settings = settings,
                hasApiKey = hasApiKey,
                maskedApiKey = maskedApiKey,
                sessionState = sessionState,
                isSharingScreen = isSharingScreen,
                showFirstSessionPrivacyDialog = showPrivacyDialog,
                onStartLiveClicked = {
                    viewModel.requestStartLiveSession {
                        navController.navigate(ScreenRoutes.ROUTE_LIVE_ASSISTANT) {
                            launchSingleTop = true
                        }
                        requestPermissionsAndLaunchLive(autoPromptScreenShare = true)
                    }
                },
                onConfirmPrivacyAndStart = {
                    viewModel.confirmFirstSessionPrivacyAndStart {
                        navController.navigate(ScreenRoutes.ROUTE_LIVE_ASSISTANT) {
                            launchSingleTop = true
                        }
                        requestPermissionsAndLaunchLive(autoPromptScreenShare = true)
                    }
                },
                onDismissPrivacyDialog = {
                    viewModel.dismissFirstSessionPrivacyDialog()
                },
                onNavigateToLiveScreen = {
                    navController.navigate(ScreenRoutes.ROUTE_LIVE_ASSISTANT) {
                        launchSingleTop = true
                    }
                },
                onNavigateToApiSettings = {
                    navController.navigate(ScreenRoutes.ROUTE_API_SETTINGS) {
                        launchSingleTop = true
                    }
                },
                onNavigateToAppearanceSettings = {
                    navController.navigate(ScreenRoutes.ROUTE_APPEARANCE_SETTINGS) {
                        launchSingleTop = true
                    }
                },
                onNavigateToPrivacy = {
                    navController.navigate(ScreenRoutes.ROUTE_PRIVACY_SECURITY) {
                        launchSingleTop = true
                    }
                }
            )
        }

        composable(ScreenRoutes.ROUTE_LIVE_ASSISTANT) {
            val selectedModel = GeminiLiveModel.findByIdOrCustom(settings.selectedModelId)
            LiveAssistantScreen(
                selectedModelName = "${selectedModel.displayName} (${selectedModel.id.removePrefix("models/")})",
                sessionState = sessionState,
                isSharingScreen = isSharingScreen,
                isMicMuted = isMicMuted,
                isUserSpeaking = isUserSpeaking,
                micLevel = micLevel,
                isAiMuted = isAiMuted,
                isAiSpeaking = isAiSpeaking,
                framesSentCount = framesSentCount,
                framesSkippedCount = framesSkippedCount,
                lastFrameDimensions = lastFrameDimensions,
                connectionQuality = connectionQuality,
                transcript = transcript,
                activeError = activeError,
                onBack = {
                    navController.popBackStack()
                },
                onStartSession = {
                    viewModel.startLiveSessionDirectly()
                    requestPermissionsAndLaunchLive(autoPromptScreenShare = true)
                },
                onRequestShareScreen = {
                    if (!sessionState.isActive) {
                        viewModel.startLiveSessionDirectly()
                    }
                    requestPermissionsAndLaunchLive(autoPromptScreenShare = true)
                },
                onStopShareScreen = {
                    viewModel.stopScreenSharing()
                },
                onToggleMicMute = {
                    viewModel.toggleMicMute()
                },
                onToggleAiAudioMute = {
                    viewModel.toggleAiAudioMute()
                },
                onEndConversation = {
                    viewModel.stopLiveSession()
                },
                onSendTextMessage = { msg ->
                    viewModel.sendTextMessage(msg)
                },
                onClearTranscript = {
                    viewModel.clearTranscript()
                },
                onDismissError = {
                    viewModel.dismissError()
                },
                onNavigateToApiSettings = {
                    navController.navigate(ScreenRoutes.ROUTE_API_SETTINGS) {
                        launchSingleTop = true
                    }
                }
            )
        }

        composable(ScreenRoutes.ROUTE_API_SETTINGS) {
            ApiSettingsScreen(
                settings = settings,
                hasApiKey = hasApiKey,
                maskedApiKey = maskedApiKey,
                apiKeySource = apiKeySource,
                validationState = validationState,
                onSaveApiKey = { viewModel.saveApiKey(it) },
                onValidateApiKey = { viewModel.validateApiKey(it) },
                onRemoveApiKey = { viewModel.removeApiKey() },
                onSelectModel = { viewModel.selectModel(it) },
                onBack = { navController.popBackStack() }
            )
        }

        composable(ScreenRoutes.ROUTE_APPEARANCE_SETTINGS) {
            AppearanceSettingsScreen(
                settings = settings,
                onSelectTheme = { viewModel.updateThemePreference(it) },
                onToggleDynamicColor = { viewModel.updateDynamicColor(it) },
                onToggleHapticFeedback = { viewModel.updateHapticFeedback(it) },
                onSelectVoice = { viewModel.selectVoice(it) },
                onSelectLanguage = { viewModel.updatePreferredLanguage(it) },
                onUpdateFrameConfig = { fps, dim, q -> viewModel.updateFrameConfig(fps, dim, q) },
                onUpdateSystemInstruction = { viewModel.updateSystemInstruction(it) },
                onBack = { navController.popBackStack() }
            )
        }

        composable(ScreenRoutes.ROUTE_PRIVACY_SECURITY) {
            PrivacySecurityScreen(
                onBack = { navController.popBackStack() }
            )
        }
    }
}
