package com.example.agent

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Real-time Android Permission & Capability Status Manager (Section 13).
 *
 * Inspects actual Android OS states for Microphone, AccessibilityService,
 * Display Over Other Apps, Notifications, and optional Contacts permission.
 * Never fakes or caches stale granted states.
 */
class AgentPermissionManager(private val context: Context) {

    private val _permissions = MutableStateFlow(AgentPermissionsSnapshot())
    val permissions: StateFlow<AgentPermissionsSnapshot> = _permissions.asStateFlow()

    fun refresh(isScreenSharingActive: Boolean = _permissions.value.isScreenSharingActive): AgentPermissionsSnapshot {
        val hasMic = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        val hasNotifications = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

        val hasContacts = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_CONTACTS
        ) == PackageManager.PERMISSION_GRANTED

        val canDrawOverlays = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(context)
        } else {
            true
        }

        val accessibilityEnabled = AgentAccessibilityService.isEnabledInSystemSettings(context)
        val accessibilityConnected = AgentAccessibilityService.isConnected.value

        val snapshot = AgentPermissionsSnapshot(
            hasMicrophone = hasMic,
            isAccessibilityEnabledInSettings = accessibilityEnabled,
            isAccessibilityServiceConnected = accessibilityConnected,
            canDrawOverlays = canDrawOverlays,
            hasNotifications = hasNotifications,
            hasContacts = hasContacts,
            isScreenSharingActive = isScreenSharingActive
        )
        _permissions.value = snapshot
        return snapshot
    }

    fun createAccessibilitySettingsIntent(): Intent {
        return Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    fun createOverlaySettingsIntent(): Intent {
        return Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${context.packageName}")
        ).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    fun createAppDetailsSettingsIntent(): Intent {
        return Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:${context.packageName}")
        ).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }
}
