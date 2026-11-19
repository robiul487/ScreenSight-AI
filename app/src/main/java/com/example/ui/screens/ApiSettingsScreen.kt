package com.example.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.local.ApiKeySource
import com.example.domain.model.AppSettings
import com.example.domain.model.GeminiLiveModel
import com.example.ui.ApiValidationUiState
import com.example.ui.theme.AmberThinking
import com.example.ui.theme.EmeraldLive

/**
 * API & Model Settings Screen (Sections 5.2 & 9.3).
 * Features:
 * - Gemini API key input, 1-tap Paste from Clipboard, Show/hide toggle, Validate API key, Save API key, Remove API key.
 * - Explicit distinction between on-device Android Keystore storage and BuildConfig development fallback,
 *   including the security warning that APK-embedded BuildConfig values can be extracted by decompiling the APK.
 * - Verified Gemini Live model selector + live `/v1beta/models` discovery + custom model ID entry.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ApiSettingsScreen(
    settings: AppSettings,
    hasApiKey: Boolean,
    maskedApiKey: String,
    apiKeySource: ApiKeySource = ApiKeySource.NONE,
    validationState: ApiValidationUiState,
    onSaveApiKey: (String) -> Unit,
    onValidateApiKey: (String) -> Unit,
    onRemoveApiKey: () -> Unit,
    onSelectModel: (String) -> Unit,
    onSelectFallbackModel: (String) -> Unit = {},
    onBack: () -> Unit
) {
    BackHandler(onBack = onBack)

    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    var apiKeyInput by rememberSaveable { mutableStateOf("") }
    var showApiKeyText by rememberSaveable { mutableStateOf(false) }
    var customModelInput by rememberSaveable { mutableStateOf("") }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.testTag("api_settings_back_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                title = {
                    Text(
                        text = stringResource(R.string.api_settings_title),
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
                // 1. BYOK Gemini API Key Section
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("api_key_card"),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
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
                            Icon(
                                imageVector = Icons.Default.Key,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Column {
                                Text(
                                    text = "Google Gemini API Key (BYOK)",
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Text(
                                    text = if (hasApiKey) "Active key: $maskedApiKey" else "No API key stored on device",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = if (hasApiKey) EmeraldLive else AmberThinking,
                                    modifier = Modifier.testTag("masked_api_key_label")
                                )
                                Text(
                                    text = "Source: ${apiKeySource.description}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (apiKeySource == ApiKeySource.BUILD_CONFIG_FALLBACK) {
                                        AmberThinking
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    }
                                )
                            }
                        }

                        Text(
                            text = "Enter your Gemini API key below to encrypt and store it on this device using Android Keystore (AES-256-GCM), or copy your key and tap Paste & Save.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        OutlinedTextField(
                            value = apiKeyInput,
                            onValueChange = { apiKeyInput = it },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("api_key_input"),
                            label = {
                                Text(if (hasApiKey) "Replace Gemini API Key" else "Enter Gemini API Key")
                            },
                            placeholder = {
                                Text("Paste your Gemini API key here...")
                            },
                            singleLine = true,
                            visualTransformation = if (showApiKeyText) {
                                VisualTransformation.None
                            } else {
                                PasswordVisualTransformation()
                            },
                            trailingIcon = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    IconButton(
                                        onClick = {
                                            val clipText = clipboardManager.getText()?.text?.trim().orEmpty()
                                            if (clipText.isNotEmpty()) {
                                                apiKeyInput = clipText
                                            }
                                        },
                                        modifier = Modifier.testTag("paste_clipboard_icon_button")
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.ContentPaste,
                                            contentDescription = "Paste API Key from Clipboard"
                                        )
                                    }
                                    IconButton(
                                        onClick = { showApiKeyText = !showApiKeyText },
                                        modifier = Modifier.testTag("toggle_api_key_visibility_button")
                                    ) {
                                        Icon(
                                            imageVector = if (showApiKeyText) {
                                                Icons.Default.VisibilityOff
                                            } else {
                                                Icons.Default.Visibility
                                            },
                                            contentDescription = if (showApiKeyText) "Hide API Key" else "Show API Key"
                                        )
                                    }
                                }
                            },
                            shape = RoundedCornerShape(14.dp)
                        )

                        OutlinedButton(
                            onClick = {
                                val clipText = clipboardManager.getText()?.text?.trim().orEmpty()
                                if (clipText.isNotEmpty()) {
                                    apiKeyInput = clipText
                                    onSaveApiKey(clipText)
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .testTag("paste_and_save_api_key_button"),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Default.ContentPaste, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("1-Tap Paste from Clipboard & Save")
                        }

                        // Action Buttons: Save, Validate, Remove
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Button(
                                onClick = {
                                    onSaveApiKey(apiKeyInput)
                                    apiKeyInput = ""
                                },
                                enabled = apiKeyInput.isNotBlank(),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp)
                                    .testTag("save_api_key_button"),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Save Key")
                            }

                            FilledTonalButton(
                                onClick = {
                                    onValidateApiKey(apiKeyInput)
                                    if (apiKeyInput.isNotBlank()) {
                                        apiKeyInput = ""
                                    }
                                },
                                enabled = apiKeyInput.isNotBlank() || hasApiKey,
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp)
                                    .testTag("validate_api_key_button"),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                if (validationState is ApiValidationUiState.Validating) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(18.dp),
                                        strokeWidth = 2.dp
                                    )
                                } else {
                                    Icon(Icons.Default.Verified, contentDescription = null, modifier = Modifier.size(18.dp))
                                }
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Validate")
                            }
                        }

                        if (hasApiKey) {
                            OutlinedButton(
                                onClick = {
                                    apiKeyInput = ""
                                    onRemoveApiKey()
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp)
                                    .testTag("remove_api_key_button"),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.DeleteOutline,
                                    contentDescription = "Remove API Key",
                                    tint = MaterialTheme.colorScheme.error
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "Remove Stored API Key",
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                        }

                        // Validation Feedback Box
                        AnimatedVisibility(visible = validationState !is ApiValidationUiState.Idle) {
                            when (validationState) {
                                is ApiValidationUiState.Validating -> {
                                    Text(
                                        text = "Querying GET /v1beta/models to verify key & discover bidiGenerateContent models...",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                                is ApiValidationUiState.Valid -> {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(12.dp))
                                            .background(EmeraldLive.copy(alpha = 0.15f))
                                            .padding(12.dp)
                                            .testTag("api_validation_success_box"),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.CheckCircle,
                                            contentDescription = null,
                                            tint = EmeraldLive
                                        )
                                        Text(
                                            text = validationState.message,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                    }
                                }
                                is ApiValidationUiState.Invalid -> {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(12.dp))
                                            .background(MaterialTheme.colorScheme.errorContainer)
                                            .padding(12.dp)
                                            .testTag("api_validation_error_box"),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.ErrorOutline,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onErrorContainer
                                        )
                                        Column {
                                            Text(
                                                text = validationState.error.title,
                                                style = MaterialTheme.typography.titleMedium,
                                                color = MaterialTheme.colorScheme.onErrorContainer
                                            )
                                            Text(
                                                text = validationState.error.userMessage,
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = MaterialTheme.colorScheme.onErrorContainer
                                            )
                                        }
                                    }
                                }
                                else -> {}
                            }
                        }
                    }
                }

                // 2. Mandatory Client-Side Security Warning & APK Extraction Notice (Priority 8)
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("security_warning_card"),
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f)
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Security,
                                contentDescription = null,
                                tint = AmberThinking
                            )
                            Text(
                                text = "API Key Storage Audit & Security Warning",
                                style = MaterialTheme.typography.titleMedium
                            )
                        }
                        Text(
                            text = stringResource(R.string.security_warning_body),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "Important: Keys entered in-app are encrypted on-device via Android Keystore (AES-256-GCM). However, any key injected at build time via BuildConfig / .env is embedded in the compiled APK bytecode and is NOT secure — anyone with the APK can decompile and extract it. Never distribute an APK built with an embedded BuildConfig key.",
                            style = MaterialTheme.typography.labelMedium,
                            color = AmberThinking
                        )
                        Text(
                            text = stringResource(R.string.billing_notice_body),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                // 3. Gemini Live Model Selector (Priority 1)
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("model_selector_card"),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
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
                            Icon(
                                imageVector = Icons.Default.Psychology,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Column {
                                Text(
                                    text = "Verified Gemini Live (BidiGenerateContent) Models",
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Text(
                                    text = "Active API ID: ${settings.selectedModelId}",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }

                        Text(
                            text = "Note: Google AI Studio dashboard display names (e.g., \"Gemini 3 Flash Live\") differ from actual WebSocket API model identifiers (e.g., \"models/gemini-3.1-flash-live-preview\"). Only verified BidiGenerateContent API identifiers are listed below. Tap Validate above to query your API key's live model catalog.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        if (validationState is ApiValidationUiState.Valid && validationState.discoveredModels.isNotEmpty()) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(EmeraldLive.copy(alpha = 0.12f))
                                    .padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    text = "Live Models Discovered on Your API Key (${validationState.discoveredModels.size}):",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = EmeraldLive
                                )
                                validationState.discoveredModels.forEach { discoveredId ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(8.dp))
                                            .clickable { onSelectModel(discoveredId) }
                                            .padding(vertical = 4.dp, horizontal = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            text = discoveredId,
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        Text(
                                            text = if (settings.selectedModelId.equals(discoveredId, ignoreCase = true)) "ACTIVE" else "Tap to Use",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                            }
                        }

                        GeminiLiveModel.VERIFIED_LIVE_MODELS.forEach { model ->
                            val isSelected = settings.selectedModelId.equals(model.id, ignoreCase = true)
                            ModelOptionItem(
                                model = model,
                                isSelected = isSelected,
                                onSelect = { onSelectModel(model.id) }
                            )
                        }

                        // Custom Model ID Entry
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
                                .padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text(
                                text = "Manual / Custom API Model Identifier",
                                style = MaterialTheme.typography.labelLarge
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                OutlinedTextField(
                                    value = customModelInput,
                                    onValueChange = { customModelInput = it },
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("custom_model_input"),
                                    placeholder = { Text("models/gemini-3.1-flash-live-preview") },
                                    singleLine = true,
                                    shape = RoundedCornerShape(12.dp)
                                )
                                FilledTonalButton(
                                    onClick = {
                                        if (customModelInput.isNotBlank()) {
                                            onSelectModel(customModelInput.trim())
                                            customModelInput = ""
                                        }
                                    },
                                    enabled = customModelInput.isNotBlank(),
                                    modifier = Modifier
                                        .height(48.dp)
                                        .testTag("apply_custom_model_button"),
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Text("Use ID")
                                }
                            }
                        }
                    }
                }

                // 3b. Fallback Model & Connection Timeout Summary
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("fallback_model_card"),
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = "Fallback Model & Session Timeout",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = "Used if your primary Live model is unavailable or quota-limited. Active fallback: ${settings.fallbackModelId}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        GeminiLiveModel.VERIFIED_LIVE_MODELS.forEach { fallbackModel ->
                            val isFallback = settings.fallbackModelId.equals(fallbackModel.id, ignoreCase = true)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(
                                        if (isFallback) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)
                                        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                                    )
                                    .clickable { onSelectFallbackModel(fallbackModel.id) }
                                    .padding(horizontal = 12.dp, vertical = 10.dp)
                                    .testTag("fallback_model_option_${fallbackModel.id}"),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = fallbackModel.displayName,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = if (isFallback) FontWeight.SemiBold else FontWeight.Normal,
                                    modifier = Modifier.weight(1f)
                                )
                                Text(
                                    text = if (isFallback) "FALLBACK" else "Select",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (isFallback) EmeraldLive else MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                        Text(
                            text = "Tool execution timeout: ${settings.agentToolTimeoutSec}s (adjust in Settings → Agent Behavior).",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // 4. Official API Documentation Links
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "Official Google Gemini Live Resources",
                            style = MaterialTheme.typography.titleMedium
                        )
                        TextButton(
                            onClick = {
                                runCatching {
                                    context.startActivity(
                                        Intent(Intent.ACTION_VIEW, Uri.parse("https://aistudio.google.com/app/apikey"))
                                    )
                                }
                            },
                            modifier = Modifier.testTag("open_aistudio_key_link")
                        ) {
                            Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Get a Gemini API Key in Google AI Studio")
                        }
                        TextButton(
                            onClick = {
                                runCatching {
                                    context.startActivity(
                                        Intent(Intent.ACTION_VIEW, Uri.parse("https://ai.google.dev/gemini-api/docs/live"))
                                    )
                                }
                            },
                            modifier = Modifier.testTag("open_live_docs_link")
                        ) {
                            Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Gemini Live API Official Documentation")
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun ModelOptionItem(
    model: GeminiLiveModel,
    isSelected: Boolean,
    onSelect: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(
                if (isSelected) {
                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                } else {
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                }
            )
            .border(
                width = if (isSelected) 1.5.dp else 1.dp,
                color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                shape = RoundedCornerShape(14.dp)
            )
            .clickable(onClick = onSelect)
            .padding(14.dp)
            .testTag("model_option_${model.id}"),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(
            imageVector = if (isSelected) Icons.Default.RadioButtonChecked else Icons.Default.RadioButtonUnchecked,
            contentDescription = model.displayName,
            tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp)
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = model.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
            }
            Text(
                text = model.roleBadge,
                style = MaterialTheme.typography.labelSmall,
                color = EmeraldLive
            )
            Text(
                text = "API ID: ${model.id}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = model.quotaSummary,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = model.description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            val modalitiesSummary = buildString {
                append("Audio In: 16 kHz PCM • ")
                if (model.supportsNativeAudio) {
                    append("Audio Out: 24 kHz PCM")
                } else {
                    append("Output: Live Text Transcription")
                }
                if (model.supportsVideoFrames) {
                    append(" • Screen Vision: 1 FPS JPEG")
                }
            }
            Text(
                text = modalitiesSummary,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}
