package com.example.ui.screens

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Pageview
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.R
import com.example.agent.SafeWebAutomationManager
import com.example.ui.theme.AmberThinking
import com.example.ui.theme.CrimsonAlert
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.EmeraldLive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Phase 5: Explicitly User-Controlled Safe Browser Screen.
 *
 * Implements hardened Android WebView browsing with:
 * - Strict URL validation (blocks javascript:, file:, content:, data:, intent:)
 * - File/content access disabled (`allowFileAccess = false`, `allowContentAccess = false`)
 * - Zero leakage of API keys, passwords, OTPs, or private local memory
 * - Explicit user confirmation dialog before risky downloads, logins, or payment pages
 * - Grounded "Observe Page Safely" extraction with webpage prompt-injection detection
 * - Honest compatibility disclaimer & one-tap "Open External Browser" fallback
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SafeBrowserScreen(
    webAutomationManager: SafeWebAutomationManager,
    onBack: () -> Unit
) {
    BackHandler(onBack = onBack)

    val context = LocalContext.current
    var urlInput by rememberSaveable { mutableStateOf("https://en.wikipedia.org") }
    var currentLoadedUrl by rememberSaveable { mutableStateOf("https://en.wikipedia.org") }
    var currentTitle by rememberSaveable { mutableStateOf("Safe Browser") }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var observedSnapshot by remember { mutableStateOf<SafeWebAutomationManager.SafeWebPageSnapshot?>(null) }
    var pendingSensitiveNavigation by remember { mutableStateOf<Pair<String, String>?>(null) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }

    fun navigateSafely(rawUrl: String) {
        when (val validated = webAutomationManager.validateAndNormalizeUrl(rawUrl)) {
            is SafeWebAutomationManager.UrlValidationResult.Blocked -> {
                statusMessage = "${validated.reasonEn} (${validated.reasonBn})"
            }
            is SafeWebAutomationManager.UrlValidationResult.Allowed -> {
                val decision = webAutomationManager.evaluateWebAction(
                    url = validated.normalizedUrl,
                    actionKind = SafeWebAutomationManager.WebActionKind.NAVIGATE
                )
                if (decision.blocked) {
                    statusMessage = decision.reasonEn
                } else if (decision.requiresExplicitUserConfirmation) {
                    pendingSensitiveNavigation = validated.normalizedUrl to decision.reasonEn
                } else {
                    statusMessage = null
                    currentLoadedUrl = validated.normalizedUrl
                    urlInput = validated.normalizedUrl
                    webViewRef?.loadUrl(validated.normalizedUrl)
                }
            }
        }
    }

    if (pendingSensitiveNavigation != null) {
        val (targetUrl, reason) = pendingSensitiveNavigation!!
        AlertDialog(
            onDismissRequest = { pendingSensitiveNavigation = null },
            title = { Text("Sensitive Web Action Confirmation") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(reason)
                    Text(
                        text = "URL: $targetUrl",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "ScreenSight AI will NEVER auto-fill passwords, OTPs, payment details, or private memories.",
                        style = MaterialTheme.typography.bodySmall,
                        color = AmberThinking
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        pendingSensitiveNavigation = null
                        currentLoadedUrl = targetUrl
                        urlInput = targetUrl
                        webViewRef?.loadUrl(targetUrl)
                    }
                ) {
                    Text("Proceed Manually")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingSensitiveNavigation = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = stringResource(R.string.safe_browser_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = currentTitle.take(40),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.testTag("safe_browser_back_button")
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(
                        onClick = { webViewRef?.reload() },
                        modifier = Modifier.testTag("safe_browser_reload_button")
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = "Reload page")
                    }
                    IconButton(
                        onClick = {
                            val validated = webAutomationManager.validateAndNormalizeUrl(currentLoadedUrl)
                            if (validated is SafeWebAutomationManager.UrlValidationResult.Allowed) {
                                runCatching {
                                    context.startActivity(
                                        Intent(Intent.ACTION_VIEW, Uri.parse(validated.normalizedUrl))
                                    )
                                }
                            }
                        },
                        modifier = Modifier.testTag("safe_browser_external_button")
                    ) {
                        Icon(Icons.Default.OpenInBrowser, contentDescription = "Open in External Browser")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = urlInput,
                    onValueChange = { urlInput = it },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("safe_browser_url_input"),
                    label = { Text("https://...") },
                    singleLine = true
                )
                Button(
                    onClick = { navigateSafely(urlInput) },
                    modifier = Modifier
                        .height(52.dp)
                        .testTag("safe_browser_go_button"),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Go")
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilledTonalButton(
                    onClick = {
                        val wv = webViewRef ?: return@FilledTonalButton
                        val prevUrl = currentLoadedUrl
                        wv.evaluateJavascript(webAutomationManager.buildSafeDomObservationScript()) { rawJson ->
                            val parsed = parseDomObservationResult(rawJson)
                            val snapshot = webAutomationManager.buildSafePageSnapshot(
                                url = wv.url ?: prevUrl,
                                title = parsed.first.ifBlank { wv.title.orEmpty() },
                                rawVisibleText = parsed.second,
                                rawElements = parsed.third
                            )
                            observedSnapshot = snapshot
                            val verification = webAutomationManager.verifyWebNavigation(prevUrl, snapshot)
                            statusMessage = verification.noteEn
                        }
                    },
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .testTag("safe_browser_observe_button"),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.Pageview, contentDescription = null)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Observe Page Safely")
                }
                OutlinedButton(
                    onClick = { observedSnapshot = null },
                    enabled = observedSnapshot != null,
                    modifier = Modifier.height(48.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Hide")
                }
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(Icons.Default.Security, contentDescription = null, tint = EmeraldLive)
                    Text(
                        text = SafeWebAutomationManager.COMPATIBILITY_NOTICE_EN,
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }

            AnimatedVisibility(visible = !statusMessage.isNullOrBlank()) {
                statusMessage?.let { msg ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("safe_browser_status_banner"),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = AmberThinking.copy(alpha = 0.16f))
                    ) {
                        Text(
                            text = msg,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(10.dp)
                        )
                    }
                }
            }

            AnimatedVisibility(visible = observedSnapshot != null) {
                observedSnapshot?.let { snap ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(170.dp)
                            .testTag("safe_browser_observation_panel"),
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState())
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            if (snap.injectionWarnings.isNotEmpty()) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Icon(Icons.Default.WarningAmber, contentDescription = null, tint = CrimsonAlert)
                                    Text(
                                        text = "Web Prompt-Injection Filtered: ${snap.injectionWarnings.joinToString(", ")}",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = CrimsonAlert,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                            Text(
                                text = "Grounded Page Summary (${snap.interactiveElements.size} links/buttons)",
                                style = MaterialTheme.typography.labelLarge,
                                color = ElectricCyan,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = snap.visibleTextSummary.ifBlank { "No public text extracted yet." },
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }

            AndroidView(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .testTag("safe_browser_webview"),
                factory = { ctx ->
                    @SuppressLint("SetJavaScriptEnabled")
                    WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.allowFileAccess = false
                        settings.allowContentAccess = false
                        settings.setSupportMultipleWindows(false)
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(
                                view: WebView?,
                                request: WebResourceRequest?
                            ): Boolean {
                                val nextUrl = request?.url?.toString().orEmpty()
                                val validated = webAutomationManager.validateAndNormalizeUrl(nextUrl)
                                if (validated is SafeWebAutomationManager.UrlValidationResult.Blocked) {
                                    statusMessage = validated.reasonEn
                                    return true
                                }
                                val decision = webAutomationManager.evaluateWebAction(
                                    url = nextUrl,
                                    actionKind = SafeWebAutomationManager.WebActionKind.NAVIGATE
                                )
                                if (decision.blocked) {
                                    statusMessage = decision.reasonEn
                                    return true
                                }
                                if (decision.requiresExplicitUserConfirmation) {
                                    pendingSensitiveNavigation = nextUrl to decision.reasonEn
                                    return true
                                }
                                return false
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                super.onPageFinished(view, url)
                                if (!url.isNullOrBlank()) {
                                    currentLoadedUrl = url
                                    urlInput = url
                                }
                                currentTitle = view?.title?.takeIf { it.isNotBlank() } ?: "Safe Browser"
                            }
                        }
                        loadUrl(currentLoadedUrl)
                        webViewRef = this
                    }
                }
            )
        }
    }
}

private fun parseDomObservationResult(
    rawJsReturn: String?
): Triple<String, String, List<SafeWebAutomationManager.WebInteractiveElement>> {
    if (rawJsReturn.isNullOrBlank() || rawJsReturn == "null") {
        return Triple("", "", emptyList())
    }
    return runCatching {
        val unquoted = Json.decodeFromString<String>(rawJsReturn)
        val obj = Json.parseToJsonElement(unquoted).jsonObject
        val title = obj["title"]?.jsonPrimitive?.content.orEmpty()
        val text = obj["text"]?.jsonPrimitive?.content.orEmpty()
        val items = obj["items"]?.jsonArray?.mapIndexedNotNull { idx, elem ->
            val raw = elem.jsonPrimitive.content
            val parts = raw.split("::", limit = 2)
            if (parts.size == 2) {
                SafeWebAutomationManager.WebInteractiveElement(
                    index = idx + 1,
                    tag = parts[0],
                    label = parts[1],
                    hrefOrType = parts[0]
                )
            } else {
                null
            }
        }.orEmpty()
        Triple(title, text, items)
    }.getOrDefault(Triple("", "", emptyList()))
}
