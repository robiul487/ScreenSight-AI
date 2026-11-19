package com.example.agent

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicLong

/**
 * Dedicated Android AccessibilityService — the main device-control engine (Section 4).
 *
 * Responsibilities:
 * - Track real connection state without falsely reporting enabled.
 * - Extract privacy-filtered UI hierarchy snapshots (skipping password/OTP/PIN fields).
 * - Click accessible UI elements (walking up to clickable ancestor when needed).
 * - Long press, scroll lists/pages, enter text into editable fields, and navigate Back/Home.
 * - Wait for UI state changes with bounded timeouts.
 * - Handle service disconnection and reconnection cleanly.
 */
class AgentAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        activeInstance = this
        _isConnected.value = true
        lastUiChangeTimestampMs.set(System.currentTimeMillis())
        captureScreenSnapshot()?.let { _latestSnapshot.value = it }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val pkg = event.packageName?.toString().orEmpty()
        if (pkg.isNotBlank()) {
            _activePackageName.value = pkg
        }
        lastUiChangeTimestampMs.set(System.currentTimeMillis())
    }

    override fun onInterrupt() {
        // Respect OS interruption without crashing
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        clearInstance()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        clearInstance()
        super.onDestroy()
    }

    private fun clearInstance() {
        if (activeInstance === this) {
            activeInstance = null
        }
        _isConnected.value = false
    }

    /**
     * Captures a fresh, privacy-filtered snapshot of the current active window.
     * Skips password/sensitive nodes so credentials and OTPs are never read aloud.
     */
    fun captureScreenSnapshot(): ScreenUiSnapshot? {
        val root = rootInActiveWindow ?: return null
        val pkg = root.packageName?.toString() ?: _activePackageName.value
        val visibleTexts = LinkedHashSet<String>()
        val clickableItems = LinkedHashSet<String>()
        val editableFields = LinkedHashSet<String>()
        var hasScrollable = false
        var redactedCount = 0

        fun traverse(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null || depth > MAX_TRAVERSAL_DEPTH) return
            if (!node.isVisibleToUser) return

            val viewId = node.viewIdResourceName
            val hint = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) node.hintText?.toString() else null
            val rawText = node.text?.toString()?.trim()
            val contentDesc = node.contentDescription?.toString()?.trim()

            if (AgentSafetyManager.isSensitiveTextOrField(rawText, viewId ?: hint, node.isPassword)) {
                redactedCount++
                return
            }

            val label = when {
                !rawText.isNullOrBlank() -> AgentSafetyManager.redactSensitiveContent(rawText)
                !contentDesc.isNullOrBlank() -> AgentSafetyManager.redactSensitiveContent(contentDesc)
                !hint.isNullOrBlank() -> AgentSafetyManager.redactSensitiveContent(hint)
                else -> null
            }

            if (!label.isNullOrBlank() && label.length <= 240) {
                visibleTexts.add(label)
            }
            if (node.isClickable && !label.isNullOrBlank() && label.length <= 100) {
                clickableItems.add(label)
            }
            if (node.isEditable) {
                val fieldLabel = label ?: hint ?: viewId?.substringAfterLast('/') ?: "Text field"
                editableFields.add(fieldLabel)
            }
            if (node.isScrollable) {
                hasScrollable = true
            }

            val childCount = node.childCount
            for (i in 0 until childCount) {
                val child = node.getChild(i) ?: continue
                try {
                    traverse(child, depth + 1)
                } finally {
                    @Suppress("DEPRECATION")
                    runCatching { child.recycle() }
                }
            }
        }

        try {
            traverse(root, 0)
        } finally {
            @Suppress("DEPRECATION")
            runCatching { root.recycle() }
        }

        val snapshot = ScreenUiSnapshot(
            packageName = pkg,
            timestampMillis = System.currentTimeMillis(),
            visibleTexts = visibleTexts.toList(),
            clickableItems = clickableItems.toList(),
            editableFields = editableFields.toList(),
            hasScrollableContainer = hasScrollable,
            redactedSensitiveFieldCount = redactedCount
        )
        _latestSnapshot.value = snapshot
        return snapshot
    }

    fun navigateBack(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)

    fun navigateHome(): Boolean = performGlobalAction(GLOBAL_ACTION_HOME)

    /**
     * Finds an accessible element matching [targetText] (by text or contentDescription) and clicks it
     * or its nearest clickable parent.
     */
    fun clickElementByText(targetText: String, occurrenceIndex: Int = 0): Pair<Boolean, String> {
        val query = targetText.trim()
        if (query.isEmpty()) return false to "Empty target label"
        val root = rootInActiveWindow ?: return false to "Active window not accessible"
        try {
            val matches = findMatchingNodes(root, query)
            if (matches.isEmpty()) {
                return false to "Could not find visible UI element matching \"$query\"."
            }
            val chosen = matches.getOrElse(occurrenceIndex.coerceAtLeast(0)) { matches.first() }
            val clicked = performClickOnNodeOrAncestor(chosen, longClick = false)
            return if (clicked) {
                true to "Clicked \"$query\"."
            } else {
                false to "Found \"$query\", but the element is not clickable."
            }
        } finally {
            @Suppress("DEPRECATION")
            runCatching { root.recycle() }
        }
    }

    fun longPressElementByText(targetText: String): Pair<Boolean, String> {
        val query = targetText.trim()
        if (query.isEmpty()) return false to "Empty target label"
        val root = rootInActiveWindow ?: return false to "Active window not accessible"
        try {
            val matches = findMatchingNodes(root, query)
            if (matches.isEmpty()) {
                return false to "Could not find visible UI element matching \"$query\"."
            }
            val clicked = performClickOnNodeOrAncestor(matches.first(), longClick = true)
            return if (clicked) {
                true to "Long-pressed \"$query\"."
            } else {
                false to "Found \"$query\", but long-press is not supported on this element."
            }
        } finally {
            @Suppress("DEPRECATION")
            runCatching { root.recycle() }
        }
    }

    /**
     * Scrolls the first visible scrollable container in the active window.
     */
    fun scrollScreen(direction: String): Pair<Boolean, String> {
        val root = rootInActiveWindow ?: return false to "Active window not accessible"
        val isBackward = direction.equals("up", ignoreCase = true) ||
            direction.equals("backward", ignoreCase = true) ||
            direction.equals("back", ignoreCase = true)
        val action = if (isBackward) {
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        } else {
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        }

        try {
            val scrollable = findFirstNode(root) { it.isVisibleToUser && it.isScrollable }
                ?: return false to "No scrollable list or page found on this screen."
            val performed = scrollable.performAction(action)
            return if (performed) {
                true to if (isBackward) "Scrolled up." else "Scrolled down."
            } else {
                false to "Reached the boundary or this screen blocked scrolling."
            }
        } finally {
            @Suppress("DEPRECATION")
            runCatching { root.recycle() }
        }
    }

    /**
     * Enters text into the currently focused editable field or a field matching [fieldHint].
     * Never writes into password fields.
     */
    fun typeTextIntoField(
        text: String,
        fieldHint: String = "",
        submitAfterTyping: Boolean = false
    ): Pair<Boolean, String> {
        val root = rootInActiveWindow ?: return false to "Active window not accessible"
        try {
            val hintClean = fieldHint.trim()
            val targetField: AccessibilityNodeInfo? = when {
                hintClean.isNotEmpty() -> {
                    findFirstNode(root) { node ->
                        node.isVisibleToUser && node.isEditable && !node.isPassword && matchesHintOrLabel(node, hintClean)
                    } ?: findFirstNode(root) { it.isVisibleToUser && it.isEditable && !it.isPassword && it.isFocused }
                    ?: findFirstNode(root) { it.isVisibleToUser && it.isEditable && !it.isPassword }
                }
                else -> {
                    findFirstNode(root) { it.isVisibleToUser && it.isEditable && !it.isPassword && it.isFocused }
                        ?: findFirstNode(root) { it.isVisibleToUser && it.isEditable && !it.isPassword }
                }
            }

            if (targetField == null) {
                return false to "No editable text field found on the current screen."
            }
            if (targetField.isPassword) {
                return false to "Refused to type into a password field for security."
            }

            targetField.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            targetField.performAction(AccessibilityNodeInfo.ACTION_CLICK)

            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }
            val setOk = targetField.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            if (!setOk) {
                return false to "This text field did not accept automated text entry."
            }

            if (submitAfterTyping && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                targetField.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
            }
            return true to "Entered text into the field."
        } finally {
            @Suppress("DEPRECATION")
            runCatching { root.recycle() }
        }
    }

    private fun performClickOnNodeOrAncestor(node: AccessibilityNodeInfo, longClick: Boolean): Boolean {
        val action = if (longClick) AccessibilityNodeInfo.ACTION_LONG_CLICK else AccessibilityNodeInfo.ACTION_CLICK
        var current: AccessibilityNodeInfo? = node
        var hops = 0
        while (current != null && hops < 6) {
            val supportsAction = if (longClick) current.isLongClickable else current.isClickable
            if (supportsAction && current.performAction(action)) {
                return true
            }
            current = current.parent
            hops++
        }
        return node.performAction(action)
    }

    private fun findMatchingNodes(root: AccessibilityNodeInfo, query: String): List<AccessibilityNodeInfo> {
        val exactMatches = mutableListOf<AccessibilityNodeInfo>()
        val partialMatches = mutableListOf<AccessibilityNodeInfo>()
        val lowerQuery = query.lowercase()

        fun walk(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null || depth > MAX_TRAVERSAL_DEPTH || !node.isVisibleToUser) return
            if (node.isPassword) return

            val text = node.text?.toString()?.trim().orEmpty()
            val desc = node.contentDescription?.toString()?.trim().orEmpty()
            val hint = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) node.hintText?.toString()?.trim().orEmpty() else ""

            if (text.equals(query, ignoreCase = true) ||
                desc.equals(query, ignoreCase = true) ||
                hint.equals(query, ignoreCase = true)
            ) {
                exactMatches.add(node)
            } else if (text.lowercase().contains(lowerQuery) ||
                desc.lowercase().contains(lowerQuery) ||
                hint.lowercase().contains(lowerQuery)
            ) {
                partialMatches.add(node)
            }

            for (i in 0 until node.childCount) {
                walk(node.getChild(i), depth + 1)
            }
        }

        walk(root, 0)
        return if (exactMatches.isNotEmpty()) exactMatches else partialMatches
    }

    private fun findFirstNode(
        root: AccessibilityNodeInfo,
        predicate: (AccessibilityNodeInfo) -> Boolean
    ): AccessibilityNodeInfo? {
        fun walk(node: AccessibilityNodeInfo?, depth: Int): AccessibilityNodeInfo? {
            if (node == null || depth > MAX_TRAVERSAL_DEPTH) return null
            if (predicate(node)) return node
            for (i in 0 until node.childCount) {
                val found = walk(node.getChild(i), depth + 1)
                if (found != null) return found
            }
            return null
        }
        return walk(root, 0)
    }

    private fun matchesHintOrLabel(node: AccessibilityNodeInfo, query: String): Boolean {
        val lower = query.lowercase()
        val text = node.text?.toString()?.lowercase().orEmpty()
        val desc = node.contentDescription?.toString()?.lowercase().orEmpty()
        val hint = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) node.hintText?.toString()?.lowercase().orEmpty() else ""
        val viewId = node.viewIdResourceName?.lowercase().orEmpty()
        return text.contains(lower) || desc.contains(lower) || hint.contains(lower) || viewId.contains(lower)
    }

    companion object {
        private const val MAX_TRAVERSAL_DEPTH = 24

        @Volatile
        var activeInstance: AgentAccessibilityService? = null
            private set

        private val _isConnected = MutableStateFlow(false)
        val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

        private val _activePackageName = MutableStateFlow("")
        val activePackageName: StateFlow<String> = _activePackageName.asStateFlow()

        private val _latestSnapshot = MutableStateFlow<ScreenUiSnapshot?>(null)
        val latestSnapshot: StateFlow<ScreenUiSnapshot?> = _latestSnapshot.asStateFlow()

        private val lastUiChangeTimestampMs = AtomicLong(0L)

        /**
         * Accurately checks Android system Settings to determine whether the user enabled
         * [AgentAccessibilityService]. Never returns a false positive.
         */
        fun isEnabledInSystemSettings(context: Context): Boolean {
            return try {
                val enabledServices = Settings.Secure.getString(
                    context.contentResolver,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
                ) ?: return false
                val expectedComponent = ComponentName(context, AgentAccessibilityService::class.java)
                val fullFlattened = expectedComponent.flattenToString()
                val shortFlattened = expectedComponent.flattenToShortString()
                enabledServices.split(':').any { entry ->
                    entry.equals(fullFlattened, ignoreCase = true) ||
                        entry.equals(shortFlattened, ignoreCase = true)
                }
            } catch (_: Exception) {
                false
            }
        }

        /**
         * Waits up to [timeoutMs] for either a UI update or for [expectedText] to appear on screen.
         */
        suspend fun waitForUi(expectedText: String = "", timeoutMs: Long = 3500L): ScreenUiSnapshot? {
            val service = activeInstance ?: return null
            val startChangeTs = lastUiChangeTimestampMs.get()
            val deadline = System.currentTimeMillis() + timeoutMs.coerceIn(300L, 10_000L)
            val expectedLower = expectedText.trim().lowercase()

            while (System.currentTimeMillis() < deadline) {
                val snapshot = service.captureScreenSnapshot()
                if (expectedLower.isNotEmpty() && snapshot != null) {
                    val found = snapshot.visibleTexts.any { it.lowercase().contains(expectedLower) } ||
                        snapshot.clickableItems.any { it.lowercase().contains(expectedLower) } ||
                        snapshot.packageName.lowercase().contains(expectedLower)
                    if (found) return snapshot
                } else if (expectedLower.isEmpty() && lastUiChangeTimestampMs.get() > startChangeTs) {
                    delay(250L)
                    return service.captureScreenSnapshot()
                }
                delay(250L)
            }
            return service.captureScreenSnapshot()
        }
    }
}
