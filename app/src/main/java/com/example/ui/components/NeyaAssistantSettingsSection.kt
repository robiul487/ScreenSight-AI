package com.example.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Assistant
import androidx.compose.material.icons.filled.Hearing
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.assistant.DefaultAssistantRoleStatus
import com.example.assistant.NeyaAssistantRuntimeState
import com.example.assistant.WakeCapabilityStatus
import com.example.assistant.WakeDetectionMode
import com.example.domain.model.AppSettings

/**
 * Modular Material 3 settings section for Native Android Assistant (`VoiceInteractionService` /
 * `RoleManager.ROLE_ASSISTANT`) and honest "Hey Neya" wake-word capability status.
 *
 * Intentionally omits any fake "Test Wake Word" button because neither privileged OEM DSP
 * access nor a bundled offline neural wake-word model is present in this APK.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NeyaAssistantSettingsSection(
    settings: AppSettings,
    assistantState: NeyaAssistantRuntimeState,
    onRequestDefaultAssistantRole: () -> Unit,
    onRefreshAssistantStatus: () -> Unit,
    onToggleHeyNeya: (Boolean) -> Unit,
    onSelectWakeMode: (WakeDetectionMode) -> Unit,
    onInvokeNeyaManually: () -> Unit,
    modifier: Modifier = Modifier
) {
    val report = assistantState.capabilityReport
    val roleTone = when (assistantState.roleStatus) {
        DefaultAssistantRoleStatus.ACTIVE -> StatusTone.POSITIVE
        DefaultAssistantRoleStatus.SELECTION_CANCELLED,
        DefaultAssistantRoleStatus.NOT_SELECTED -> StatusTone.WARNING
        DefaultAssistantRoleStatus.UNSUPPORTED_DEVICE_OR_API,
        DefaultAssistantRoleStatus.UNVERIFIED -> StatusTone.NEUTRAL
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag("neya_assistant_settings_section"),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        SettingsGroup(
            title = "Neya Native Android Assistant",
            subtitle = "System Digital Assistant role & VoiceInteractionService"
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Default Assistant Role",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = assistantState.roleStatus.summary,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    StatusIndicator(
                        label = when (assistantState.roleStatus) {
                            DefaultAssistantRoleStatus.ACTIVE -> "Active"
                            DefaultAssistantRoleStatus.NOT_SELECTED -> "Not Set"
                            DefaultAssistantRoleStatus.SELECTION_CANCELLED -> "Cancelled"
                            DefaultAssistantRoleStatus.UNSUPPORTED_DEVICE_OR_API -> "System Settings"
                            DefaultAssistantRoleStatus.UNVERIFIED -> "Unverified"
                        },
                        tone = roleTone,
                        modifier = Modifier.testTag("neya_role_status_badge")
                    )
                }

                Text(
                    text = assistantState.lastStatusNote,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("neya_status_note_text")
                )

                PrimaryButton(
                    text = if (assistantState.roleStatus.isActive) {
                        "Change Default Assistant Settings"
                    } else {
                        "Set Neya as Default Assistant"
                    },
                    icon = Icons.Default.Assistant,
                    onClick = onRequestDefaultAssistantRole,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("set_default_assistant_button")
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    SecondaryButton(
                        text = "Invoke Neya Now",
                        icon = Icons.Default.PlayArrow,
                        onClick = onInvokeNeyaManually,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("manual_invoke_neya_button")
                    )
                    SecondaryButton(
                        text = "Recheck",
                        icon = Icons.Default.Refresh,
                        onClick = onRefreshAssistantStatus,
                        modifier = Modifier.testTag("recheck_assistant_role_button")
                    )
                }
            }
        }

        SettingsGroup(
            title = "\"Hey Neya\" Wake Word & Device Capability",
            subtitle = "Honest hardware DSP and on-device wake availability"
        ) {
            SettingsToggleItem(
                icon = Icons.Default.Hearing,
                title = "Enable \"Hey Neya\" Preference",
                subtitle = if (settings.heyNeyaEnabled) {
                    "Preference saved. Active trigger remains Manual because hardware DSP & offline neural wake model are unavailable on this install."
                } else {
                    "Disabled. Invoke Neya via Android system assistant gesture or in-app controls."
                },
                checked = settings.heyNeyaEnabled,
                onCheckedChange = onToggleHeyNeya,
                switchTestTag = "hey_neya_toggle",
                showDivider = true
            )

            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "Wake Detection Mode",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    WakeDetectionMode.entries.forEach { mode ->
                        val isSelectable = mode == WakeDetectionMode.MANUAL_ONLY ||
                            (mode == WakeDetectionMode.DSP_HARDWARE && report.dspStatus == WakeCapabilityStatus.AVAILABLE) ||
                            (mode == WakeDetectionMode.SOFTWARE_FALLBACK && report.softwareFallbackStatus == WakeCapabilityStatus.AVAILABLE)
                        FilterChip(
                            selected = settings.wakeDetectionMode == mode,
                            onClick = { onSelectWakeMode(mode) },
                            enabled = isSelectable || mode == WakeDetectionMode.MANUAL_ONLY,
                            label = {
                                Text(
                                    text = if (isSelectable) mode.shortLabel else "${mode.shortLabel} (Unavailable)",
                                    maxLines = 1
                                )
                            },
                            modifier = Modifier.testTag("wake_mode_${mode.name.lowercase()}")
                        )
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                CapabilityReadoutRow(
                    label = "Effective Wake Trigger",
                    value = report.effectiveActiveMode.shortLabel,
                    tone = StatusTone.INFO
                )
                CapabilityReadoutRow(
                    label = "Hardware DSP Hotword",
                    value = report.dspStatus.shortLabel,
                    tone = if (report.dspStatus == WakeCapabilityStatus.AVAILABLE) StatusTone.POSITIVE else StatusTone.WARNING
                )
                CapabilityReadoutRow(
                    label = "Software Wake Fallback",
                    value = report.softwareFallbackStatus.shortLabel,
                    tone = if (report.softwareFallbackStatus == WakeCapabilityStatus.AVAILABLE) StatusTone.POSITIVE else StatusTone.WARNING
                )
                CapabilityReadoutRow(
                    label = "Microphone Permission",
                    value = when {
                        report.isMicHardwarePrivacyBlocked -> "Blocked by OS Toggle"
                        report.hasRecordAudioPermission -> "Granted"
                        else -> "Not Granted"
                    },
                    tone = when {
                        report.isMicHardwarePrivacyBlocked -> StatusTone.ERROR
                        report.hasRecordAudioPermission -> StatusTone.POSITIVE
                        else -> StatusTone.WARNING
                    }
                )
                CapabilityReadoutRow(
                    label = "Wake Listening State",
                    value = if (report.isCurrentlyListeningForWakeWord) "Listening" else "Off (Zero Mic Polling)",
                    tone = StatusTone.NEUTRAL
                )

                Text(
                    text = "Why DSP / Always-On is unavailable: ${report.dspReason}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "Battery & Privacy: ${report.batteryPrivacySummary}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun CapabilityReadoutRow(
    label: String,
    value: String,
    tone: StatusTone
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f)
        )
        StatusIndicator(label = value, tone = tone)
    }
}
