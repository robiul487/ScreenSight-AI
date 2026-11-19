package com.example.ui.screens

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.domain.model.AppSettings
import com.example.domain.model.GeminiVoice
import com.example.domain.model.ThemePreference
import kotlin.math.roundToInt

/**
 * Appearance, Voice, Language & Frame Processing Settings Screen (Sections 6.3, 8, 9.4).
 * Includes:
 * - System / Light / Dark theme selector
 * - Dynamic color toggle (Android 12+)
 * - Haptic feedback toggle
 * - Preferred AI response language selector
 * - Gemini Live voice selector (Aoede, Puck, Charon, Kore, Fenrir)
 * - Screen capture FrameProcessor parameters (1 FPS, max dimension 1280px, JPEG quality 70)
 * - Custom ScreenSight AI system instruction editor with reset to default
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AppearanceSettingsScreen(
    settings: AppSettings,
    onSelectTheme: (ThemePreference) -> Unit,
    onToggleDynamicColor: (Boolean) -> Unit,
    onToggleHapticFeedback: (Boolean) -> Unit,
    onSelectVoice: (String) -> Unit,
    onSelectLanguage: (String) -> Unit,
    onUpdateFrameConfig: (Int, Int, Int) -> Unit,
    onUpdateSystemInstruction: (String) -> Unit,
    onBack: () -> Unit
) {
    BackHandler(onBack = onBack)

    var instructionDraft by remember(settings.customSystemInstruction) {
        mutableStateOf(settings.customSystemInstruction)
    }
    var qualitySlider by remember(settings.jpegQuality) {
        mutableFloatStateOf(settings.jpegQuality.toFloat())
    }

    val supportedLanguages = listOf(
        "Auto-detect (Match user language)",
        "English",
        "Bengali (বাংলা)",
        "Spanish (Español)",
        "Hindi (हिन्दी)",
        "Arabic (العربية)",
        "Japanese (日本語)",
        "German (Deutsch)"
    )

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.testTag("appearance_back_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                title = {
                    Text(
                        text = stringResource(R.string.appearance_settings_title),
                        style = MaterialTheme.typography.titleLarge
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentAlignment = Alignment.TopCenter
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 600.dp)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp)
            ) {
                // 1. Theme & Visual Appearance
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(Icons.Default.Palette, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Text("Theme & Display", style = MaterialTheme.typography.titleMedium)
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            ThemePreference.entries.forEach { pref ->
                                FilterChip(
                                    selected = settings.themePreference == pref,
                                    onClick = { onSelectTheme(pref) },
                                    label = { Text(pref.label) },
                                    modifier = Modifier.testTag("theme_chip_${pref.name}")
                                )
                            }
                        }

                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("Dynamic Material You Color", style = MaterialTheme.typography.bodyLarge)
                                    Text(
                                        "Adapt palette to your Android 12+ wallpaper",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Switch(
                                    checked = settings.dynamicColorEnabled,
                                    onCheckedChange = onToggleDynamicColor,
                                    modifier = Modifier.testTag("dynamic_color_switch")
                                )
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Haptic Feedback", style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    "Subtle tactile confirmation on live controls",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = settings.hapticFeedbackEnabled,
                                onCheckedChange = onToggleHapticFeedback,
                                modifier = Modifier.testTag("haptic_feedback_switch")
                            )
                        }
                    }
                }

                // 2. Voice & Language Preference
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(Icons.Default.RecordVoiceOver, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Text("Gemini Live Voice & Language", style = MaterialTheme.typography.titleMedium)
                        }

                        Text(
                            text = "Prebuilt Voice Profile",
                            style = MaterialTheme.typography.labelLarge
                        )

                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            GeminiVoice.entries.forEach { voice ->
                                FilterChip(
                                    selected = settings.selectedVoiceName.equals(voice.voiceName, ignoreCase = true),
                                    onClick = { onSelectVoice(voice.voiceName) },
                                    label = { Text(voice.voiceName) },
                                    modifier = Modifier.testTag("voice_chip_${voice.voiceName}")
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(4.dp))

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(Icons.Default.Language, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Text("Preferred Response Language", style = MaterialTheme.typography.labelLarge)
                        }

                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            supportedLanguages.forEach { lang ->
                                FilterChip(
                                    selected = settings.preferredLanguage == lang,
                                    onClick = { onSelectLanguage(lang) },
                                    label = { Text(lang) },
                                    modifier = Modifier.testTag("lang_chip_$lang")
                                )
                            }
                        }
                    }
                }

                // 3. FrameProcessor Optimization Settings (Section 6.3)
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(Icons.Default.Tune, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Text("Screen FrameProcessor Settings", style = MaterialTheme.typography.titleMedium)
                        }

                        Text(
                            text = "Gemini Live processes screen context at 1 FPS. ScreenSight AI resizes frames preserving aspect ratio and skips duplicate static frames.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Text(
                            text = "Max Image Dimension: ${settings.maxFrameDimensionPx} px",
                            style = MaterialTheme.typography.labelLarge
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(720, 1024, 1280, 1600).forEach { dim ->
                                FilterChip(
                                    selected = settings.maxFrameDimensionPx == dim,
                                    onClick = {
                                        onUpdateFrameConfig(settings.frameRateFps, dim, settings.jpegQuality)
                                    },
                                    label = { Text("${dim}px") }
                                )
                            }
                        }

                        Text(
                            text = "JPEG Compression Quality: ${qualitySlider.roundToInt()}%",
                            style = MaterialTheme.typography.labelLarge
                        )
                        Slider(
                            value = qualitySlider,
                            onValueChange = { qualitySlider = it },
                            onValueChangeFinished = {
                                onUpdateFrameConfig(
                                    settings.frameRateFps,
                                    settings.maxFrameDimensionPx,
                                    qualitySlider.roundToInt()
                                )
                            },
                            valueRange = 50f..90f
                        )
                    }
                }

                // 4. Screen-Aware AI Personality / System Instruction (Section 8)
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = "ScreenSight AI System Instruction",
                            style = MaterialTheme.typography.titleMedium
                        )
                        OutlinedTextField(
                            value = instructionDraft,
                            onValueChange = {
                                instructionDraft = it
                                onUpdateSystemInstruction(it)
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(180.dp)
                                .testTag("system_instruction_input"),
                            shape = RoundedCornerShape(14.dp)
                        )
                        OutlinedButton(
                            onClick = {
                                instructionDraft = AppSettings.DEFAULT_SYSTEM_INSTRUCTION
                                onUpdateSystemInstruction(AppSettings.DEFAULT_SYSTEM_INSTRUCTION)
                            },
                            modifier = Modifier.testTag("reset_system_instruction_button"),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Default.Restore, contentDescription = null)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Restore Default Screen-Aware Personality")
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}
