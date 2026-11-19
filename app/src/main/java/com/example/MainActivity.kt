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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.agent.AgentMode
import com.example.domain.model.GeminiLiveModel
import com.example.domain.model.ThemePreference
import com.example.ui.ScreenSightViewModel
import com.example.ui.screens.AgentPermissionsScreen
import com.example.ui.screens.ApiSettingsScreen
import com.example.ui.screens.AppearanceSettingsScreen
import com.example.ui.screens.FamilyEasyModeScreen
import com.example.ui.screens.HomeScreen
import com.example.ui.screens.LiveAssistantScreen
import com.example.ui.screens.PrivacySecurityScreen
import com.example.ui.screens.TrustedContactsScreen
import com.example.ui.theme.ScreenSightTheme

object ScreenRoutes {
    const val ROUTE_HOME = "home"
    const val ROUTE_LIVE_ASSISTANT = "live_assistant"
    const val ROUTE_FAMILY_EASY_MODE = "family_easy_mode"
    const val ROUTE_AGENT_PERMISSIONS = "agent_permissions"
    const val ROUTE_TRUSTED_CONTACTS = "trusted_contacts"
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
    val lifecycleOwner = LocalLifecycleOwner.current
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

    // Android AI Agent State
    val agentTaskSteps by viewModel.agentTaskSteps.collectAsStateWithLifecycle()
    val isAgentTaskRunning by viewModel.isAgentTaskRunning.collectAsStateWithLifecycle()
    val pendingConfirmation by viewModel.pendingConfirmation.collectAsStateWithLifecycle()
    val agentStatusBannerBn by viewModel.agentStatusBannerBn.collectAsStateWithLifecycle()
    val permissionsSnapshot by viewModel.permissionsSnapshot.collectAsStateWithLifecycle()
    val trustedContacts by viewModel.trustedContacts.collectAsStateWithLifecycle()

    // Always re-check real OS permissions (especially Accessibility & Overlay) when user returns to app
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refreshPermissions()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Tracks whether screen-sharing consent should chain automatically after runtime mic/notification permissions finish
    var pendingScreenShareAfterPermissions by rememberSaveable { mutableStateOf(false) }

    // Step 3: Official Android MediaProjection consent dialog launcher
    val mediaProjectionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val granted = result.resultCode == Activity.RESULT_OK && result.data != null
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

    // Step 2: Overlay permission launcher ("Display over other apps")
    val overlaySettingsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        viewModel.refreshPermissions()
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
        viewModel.refreshPermissions()
        if (pendingScreenShareAfterPermissions) {
            pendingScreenShareAfterPermissions = false
            if (!isSharingScreen) {
                launchScreenSharingWithOverlayCheck()
            }
        }
    }

    // Contextual single-permission launcher for optional Contacts or Notification grant from Permissions screen
    val singlePermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) {
        viewModel.refreshPermissions()
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
                permissions = permissionsSnapshot,
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
                onSelectAgentMode = { mode ->
                    viewModel.updateAgentMode(mode)
                    if (mode == AgentMode.FAMILY_EASY_MODE) {
                        navController.navigate(ScreenRoutes.ROUTE_FAMILY_EASY_MODE) {
                            launchSingleTop = true
                        }
                    }
                },
                onOpenFamilyEasyMode = {
                    viewModel.updateAgentMode(AgentMode.FAMILY_EASY_MODE)
                    navController.navigate(ScreenRoutes.ROUTE_FAMILY_EASY_MODE) {
                        launchSingleTop = true
                    }
                },
                onNavigateToLiveScreen = {
                    navController.navigate(ScreenRoutes.ROUTE_LIVE_ASSISTANT) {
                        launchSingleTop = true
                    }
                },
                onNavigateToAgentPermissions = {
                    viewModel.refreshPermissions()
                    navController.navigate(ScreenRoutes.ROUTE_AGENT_PERMISSIONS) {
                        launchSingleTop = true
                    }
                },
                onNavigateToTrustedContacts = {
                    navController.navigate(ScreenRoutes.ROUTE_TRUSTED_CONTACTS) {
                        launchSingleTop = true
                    }
                },
                onQuickOpenYouTube = { viewModel.runQuickOpenApp("YouTube") },
                onQuickReadScreen = { viewModel.runQuickReadScreen() },
                onQuickSetAlarm = { viewModel.runQuickSetAlarm(hour24 = 6, minute = 0, label = "Morning Alarm") },
                onQuickTellTime = { viewModel.runQuickDateTime() },
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

        composable(ScreenRoutes.ROUTE_FAMILY_EASY_MODE) {
            FamilyEasyModeScreen(
                hasApiKey = hasApiKey,
                sessionState = sessionState,
                isMicMuted = isMicMuted,
                isUserSpeaking = isUserSpeaking,
                isAiSpeaking = isAiSpeaking,
                isAgentTaskRunning = isAgentTaskRunning,
                statusBannerBn = agentStatusBannerBn,
                pendingConfirmation = pendingConfirmation,
                activeError = activeError,
                permissions = permissionsSnapshot,
                onTapToTalk = {
                    if (!hasApiKey) {
                        navController.navigate(ScreenRoutes.ROUTE_API_SETTINGS) { launchSingleTop = true }
                    } else if (!sessionState.isActive) {
                        viewModel.startLiveSessionDirectly()
                        // In Family Easy Mode, start voice immediately without interrupting with MediaProjection popup
                        requestPermissionsAndLaunchLive(autoPromptScreenShare = false)
                    } else {
                        viewModel.toggleMicMute()
                    }
                },
                onStopEverything = {
                    viewModel.cancelActiveAgentTask()
                    if (sessionState.isActive) {
                        viewModel.stopLiveSession()
                    }
                },
                onApproveConfirmation = { viewModel.approvePendingConfirmation() },
                onRejectConfirmation = { viewModel.rejectPendingConfirmation() },
                onQuickCallFamily = {
                    navController.navigate(ScreenRoutes.ROUTE_TRUSTED_CONTACTS) {
                        launchSingleTop = true
                    }
                },
                onQuickOpenYouTube = { viewModel.runQuickOpenApp("YouTube") },
                onQuickReadScreen = { viewModel.runQuickReadScreen() },
                onQuickTellTime = { viewModel.runQuickDateTime() },
                onOpenPermissionsSetup = {
                    viewModel.refreshPermissions()
                    navController.navigate(ScreenRoutes.ROUTE_AGENT_PERMISSIONS) {
                        launchSingleTop = true
                    }
                },
                onOpenApiSettings = {
                    navController.navigate(ScreenRoutes.ROUTE_API_SETTINGS) {
                        launchSingleTop = true
                    }
                },
                onSwitchToStandardMode = {
                    viewModel.updateAgentMode(AgentMode.STANDARD_AGENT)
                    navController.popBackStack(ScreenRoutes.ROUTE_HOME, inclusive = false)
                }
            )
        }

        composable(ScreenRoutes.ROUTE_AGENT_PERMISSIONS) {
            AgentPermissionsScreen(
                permissions = permissionsSnapshot,
                agentToolsEnabled = settings.agentToolsEnabled,
                onToggleAgentTools = { viewModel.updateAgentToolsEnabled(it) },
                onRefreshPermissions = { viewModel.refreshPermissions() },
                onOpenAccessibilitySettings = {
                    runCatching { context.startActivity(viewModel.createAccessibilitySettingsIntent()) }
                },
                onRequestMicPermission = {
                    singlePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                },
                onOpenOverlaySettings = {
                    runCatching { context.startActivity(viewModel.createOverlaySettingsIntent()) }
                },
                onRequestNotificationPermission = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        singlePermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                },
                onRequestContactsPermission = {
                    singlePermissionLauncher.launch(Manifest.permission.READ_CONTACTS)
                },
                onBack = { navController.popBackStack() }
            )
        }

        composable(ScreenRoutes.ROUTE_TRUSTED_CONTACTS) {
            TrustedContactsScreen(
                contacts = trustedContacts,
                onAddContact = { name, phone, nickname, relation ->
                    viewModel.addTrustedContact(name, phone, nickname, relation)
                },
                onDeleteContact = { id -> viewModel.removeTrustedContact(id) },
                onDialContact = { contact -> viewModel.dialTrustedContact(contact) },
                onBack = { navController.popBackStack() }
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
                agentTaskSteps = agentTaskSteps,
                isAgentTaskRunning = isAgentTaskRunning,
                pendingConfirmation = pendingConfirmation,
                onApproveConfirmation = { viewModel.approvePendingConfirmation() },
                onRejectConfirmation = { viewModel.rejectPendingConfirmation() },
                onStopAgentTask = { viewModel.cancelActiveAgentTask() },
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
