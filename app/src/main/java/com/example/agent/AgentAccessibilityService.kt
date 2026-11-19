package com.example.agent

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ComponentName
import android.content.Context
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume
import kotlin.math.abs

/**
 * Dedicated Android AccessibilityService — the main device-control & grounding engine (Phase 3).
 *
 * Responsibilities:
 * - Track real connection state without falsely reporting enabled.
 * - Extract privacy-filtered, grounded UI hierarchy snapshots (text, contentDescription, className,
 *   clickable/scrollable/editable/enabled flags, pixel + normalized bounding boxes, stable IDs).
 * - Redact/skip password, OTP, PIN, and sensitive credential fields before any snapshot leaves the device.
 * - Perform node-based and bounding-box-guided click, long-press, scroll, swipe, text entry, and Back/Home.
 * - Recycle every AccessibilityNodeInfo cleanly to prevent binder/memory leaks.
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
        _latestSnapshot.value = null
    }

    /**
     * Captures a fresh, privacy-filtered, grounded snapshot of the current active window.
     * Skips password/sensitive nodes so credentials and OTPs are never exposed or read aloud.
     */
    fun captureScreenSnapshot(): ScreenUiSnapshot? {
        val root = rootInActiveWindow ?: return null
        val pkg = root.packageName?.toString() ?: _activePackageName.value
        val metrics = resources.displayMetrics
        val screenW = metrics.widthPixels.coerceAtLeast(1)
        val screenH = metrics.heightPixels.coerceAtLeast(1)

        val visibleTexts = LinkedHashSet<String>()
        val clickableItems = LinkedHashSet<String>()
        val editableFields = LinkedHashSet<String>()
        val groundedElements = mutableListOf<GroundedUiElement>()
        val seenBoundsKeys = HashSet<String>()
        var hasScrollable = false
        var redactedCount = 0
        val tempRect = Rect()

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

            val safeText = rawText?.takeIf { it.isNotBlank() }?.let { AgentSafetyManager.redactSensitiveContent(it) }.orEmpty()
            val safeDesc = contentDesc?.takeIf { it.isNotBlank() }?.let { AgentSafetyManager.redactSensitiveContent(it) }.orEmpty()
            val safeHint = hint?.takeIf { it.isNotBlank() }?.let { AgentSafetyManager.redactSensitiveContent(it) }.orEmpty()

            val label = when {
                safeText.isNotBlank() -> safeText
                safeDesc.isNotBlank() -> safeDesc
                safeHint.isNotBlank() -> safeHint
                else -> null
            }

            if (!label.isNullOrBlank() && label.length <= 240) {
                visibleTexts.add(label)
            }
            if (node.isClickable && !label.isNullOrBlank() && label.length <= 100) {
                clickableItems.add(label)
            }
            if (node.isEditable) {
                val fieldLabel = label ?: viewId?.substringAfterLast('/') ?: "Text field"
                editableFields.add(fieldLabel)
            }
            if (node.isScrollable) {
                hasScrollable = true
            }

            val isInteresting = node.isClickable ||
                node.isLongClickable ||
                node.isEditable ||
                node.isScrollable ||
                !label.isNullOrBlank()

            if (isInteresting && groundedElements.size < MAX_GROUNDED_ELEMENTS) {
                tempRect.setEmpty()
                node.getBoundsInScreen(tempRect)
                val x1 = tempRect.left.coerceIn(0, screenW)
                val y1 = tempRect.top.coerceIn(0, screenH)
                val x2 = tempRect.right.coerceIn(0, screenW)
                val y2 = tempRect.bottom.coerceIn(0, screenH)
                if (x2 > x1 && y2 > y1) {
                    val dedupKey = "${label.orEmpty().take(40)}@$x1,$y1,$x2,$y2"
                    if (seenBoundsKeys.add(dedupKey)) {
                        val bounds = UiBounds.fromPixels(x1, y1, x2, y2, screenW, screenH)
                        val elementIndex = groundedElements.size
                        groundedElements.add(
                            GroundedUiElement(
                                elementId = "el_$elementIndex",
                                text = safeText.take(160),
                                contentDescription = safeDesc.take(160),
                                className = node.className?.toString().orEmpty(),
                                clickable = node.isClickable,
                                longClickable = node.isLongClickable,
                                scrollable = node.isScrollable,
                                editable = node.isEditable,
                                enabled = node.isEnabled,
                                bounds = bounds
                            )
                        )
                    }
                }
            }

            val childCount = node.childCount
            for (i in 0 until childCount) {
                val child = node.getChild(i) ?: continue
                try {
                    traverse(child, depth + 1)
                } finally {
                    safeRecycle(child)
                }
            }
        }

        try {
            traverse(root, 0)
        } finally {
            safeRecycle(root)
        }

        val snapshot = ScreenUiSnapshot(
            observationId = "obs_${UUID.randomUUID().toString().take(8)}",
            packageName = pkg,
            timestampMillis = System.currentTimeMillis(),
            screenWidthPx = screenW,
            screenHeightPx = screenH,
            visibleTexts = visibleTexts.toList(),
            clickableItems = clickableItems.toList(),
            editableFields = editableFields.toList(),
            elements = groundedElements,
            hasScrollableContainer = hasScrollable,
            redactedSensitiveFieldCount = redactedCount
        )
        _latestSnapshot.value = snapshot
        return snapshot
    }

    fun navigateBack(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)

    fun navigateHome(): Boolean = performGlobalAction(GLOBAL_ACTION_HOME)

    /**
     * Phase 3 Priority-Ordered Grounded Element Click:
     * 1. Attempts Accessibility node-based click by matching the element's live node (bounds + label).
     * 2. Falls back to bounding-box center coordinate tap gesture when node click is unavailable.
     *
     * Returns Triple(success, methodTier, message).
     */
    suspend fun clickGroundedElement(element: GroundedUiElement): Triple<Boolean, String, String> {
        val root = rootInActiveWindow
        if (root != null) {
            try {
                val matched = findNodeMatchingGroundedElement(root, element)
                if (matched != null) {
                    try {
                        if (performClickOnNodeOrAncestor(matched, longClick = false)) {
                            return Triple(
                                true,
                                "ACCESSIBILITY_NODE",
                                "Clicked grounded element ${element.elementId} (\"${element.bestLabel}\") via Accessibility node."
                            )
                        }
                    } finally {
                        if (matched !== root) safeRecycle(matched)
                    }
                }
            } finally {
                safeRecycle(root)
            }
        }

        // Priority 2: Bounding-box center coordinate interaction
        val (tapped, tapMsg) = performTapAtCoordinates(element.bounds.centerX, element.bounds.centerY)
        return Triple(
            tapped,
            "BOUNDING_BOX_COORDINATE",
            if (tapped) {
                "Tapped grounded element ${element.elementId} (\"${element.bestLabel}\") via bounding box center (${element.bounds.centerX.toInt()}, ${element.bounds.centerY.toInt()})."
            } else {
                tapMsg
            }
        )
    }

    /**
     * Phase 3 Priority-Ordered Grounded Element Long-Press:
     * 1. Accessibility node-based long-click.
     * 2. Bounding-box center long-press gesture fallback.
     */
    suspend fun longPressGroundedElement(element: GroundedUiElement): Triple<Boolean, String, String> {
        val root = rootInActiveWindow
        if (root != null) {
            try {
                val matched = findNodeMatchingGroundedElement(root, element)
                if (matched != null) {
                    try {
                        if (performClickOnNodeOrAncestor(matched, longClick = true)) {
                            return Triple(
                                true,
                                "ACCESSIBILITY_NODE",
                                "Long-pressed grounded element ${element.elementId} (\"${element.bestLabel}\") via Accessibility node."
                            )
                        }
                    } finally {
                        if (matched !== root) safeRecycle(matched)
                    }
                }
            } finally {
                safeRecycle(root)
            }
        }

        val (held, holdMsg) = performLongPressAtCoordinates(element.bounds.centerX, element.bounds.centerY)
        return Triple(
            held,
            "BOUNDING_BOX_COORDINATE",
            if (held) {
                "Long-pressed grounded element ${element.elementId} (\"${element.bestLabel}\") via bounding box center."
            } else {
                holdMsg
            }
        )
    }

    /**
     * Types text into a specifically grounded editable element, falling back to tapping its bounds
     * before focusing if needed. Refuses password fields.
     */
    suspend fun typeTextIntoGroundedElement(
        element: GroundedUiElement,
        text: String,
        submitAfterTyping: Boolean = false
    ): Pair<Boolean, String> {
        val root = rootInActiveWindow ?: return false to "Active window not accessible"
        try {
            val matched = findNodeMatchingGroundedElement(root, element)
            if (matched != null) {
                try {
                    if (matched.isPassword) {
                        return false to "Refused to type into a password field for security."
                    }
                    if (matched.isEditable) {
                        return applyTextToNode(matched, text, submitAfterTyping)
                    }
                } finally {
                    if (matched !== root) safeRecycle(matched)
                }
            }
        } finally {
            safeRecycle(root)
        }
        // Tap bounding box to focus field, then retry regular editable node entry
        performTapAtCoordinates(element.bounds.centerX, element.bounds.centerY)
        delay(220L)
        return typeTextIntoField(text, fieldHint = element.bestLabel, submitAfterTyping = submitAfterTyping)
    }

    /**
     * Finds an accessible element matching [targetText] (by text or contentDescription) and clicks it
     * or its nearest clickable parent. Recycles all intermediate nodes safely.
     */
    fun clickElementByText(targetText: String, occurrenceIndex: Int = 0): Pair<Boolean, String> {
        val query = targetText.trim()
        if (query.isEmpty()) return false to "Empty target label"
        val root = rootInActiveWindow ?: return false to "Active window not accessible"
        val matches = mutableListOf<AccessibilityNodeInfo>()
        try {
            matches.addAll(findMatchingNodes(root, query))
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
            matches.forEach { if (it !== root) safeRecycle(it) }
            safeRecycle(root)
        }
    }

    fun longPressElementByText(targetText: String): Pair<Boolean, String> {
        val query = targetText.trim()
        if (query.isEmpty()) return false to "Empty target label"
        val root = rootInActiveWindow ?: return false to "Active window not accessible"
        val matches = mutableListOf<AccessibilityNodeInfo>()
        try {
            matches.addAll(findMatchingNodes(root, query))
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
            matches.forEach { if (it !== root) safeRecycle(it) }
            safeRecycle(root)
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

        var scrollable: AccessibilityNodeInfo? = null
        try {
            scrollable = findFirstNode(root) { it.isVisibleToUser && it.isScrollable }
            if (scrollable == null) {
                return false to "No scrollable list or page found on this screen."
            }
            val performed = scrollable.performAction(action)
            return if (performed) {
                true to if (isBackward) "Scrolled up." else "Scrolled down."
            } else {
                false to "Reached the boundary or this screen blocked scrolling."
            }
        } finally {
            if (scrollable != null && scrollable !== root) safeRecycle(scrollable)
            safeRecycle(root)
        }
    }

    /**
     * Performs a touch tap at exact screen pixel coordinates using AccessibilityService.dispatchGesture.
     */
    suspend fun performTapAtCoordinates(x: Float, y: Float): Pair<Boolean, String> {
        val metrics = resources.displayMetrics
        if (metrics.widthPixels <= 0 || metrics.heightPixels <= 0) {
            return false to "Screen dimensions unavailable for coordinate tap."
        }
        val clampedX = x.coerceIn(1f, (metrics.widthPixels - 1).coerceAtLeast(1).toFloat())
        val clampedY = y.coerceIn(1f, (metrics.heightPixels - 1).coerceAtLeast(1).toFloat())

        val path = Path().apply { moveTo(clampedX, clampedY) }
        val stroke = GestureDescription.StrokeDescription(path, 0L, 60L)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()

        val dispatched = dispatchGestureAwait(gesture)
        return if (dispatched) {
            true to "Tapped screen at (${clampedX.toInt()}, ${clampedY.toInt()})."
        } else {
            false to "Android did not complete the tap gesture at (${clampedX.toInt()}, ${clampedY.toInt()})."
        }
    }

    /**
     * Performs a long-press gesture at validated pixel coordinates.
     */
    suspend fun performLongPressAtCoordinates(x: Float, y: Float): Pair<Boolean, String> {
        val metrics = resources.displayMetrics
        if (metrics.widthPixels <= 0 || metrics.heightPixels <= 0) {
            return false to "Screen dimensions unavailable for long-press gesture."
        }
        val clampedX = x.coerceIn(1f, (metrics.widthPixels - 1).coerceAtLeast(1).toFloat())
        val clampedY = y.coerceIn(1f, (metrics.heightPixels - 1).coerceAtLeast(1).toFloat())

        val path = Path().apply { moveTo(clampedX, clampedY) }
        val stroke = GestureDescription.StrokeDescription(path, 0L, 650L)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()

        val dispatched = dispatchGestureAwait(gesture)
        return if (dispatched) {
            true to "Long-pressed screen at (${clampedX.toInt()}, ${clampedY.toInt()})."
        } else {
            false to "Android did not complete the long-press gesture at (${clampedX.toInt()}, ${clampedY.toInt()})."
        }
    }

    /**
     * Performs a directional swipe gesture (up, down, left, right) across the screen center.
     */
    suspend fun performSwipeGesture(direction: String): Pair<Boolean, String> {
        val metrics = resources.displayMetrics
        val w = metrics.widthPixels.coerceAtLeast(200).toFloat()
        val h = metrics.heightPixels.coerceAtLeast(200).toFloat()

        val (startX, startY, endX, endY) = when (direction.trim().lowercase()) {
            "up" -> listOf(w * 0.5f, h * 0.72f, w * 0.5f, h * 0.25f)
            "down" -> listOf(w * 0.5f, h * 0.28f, w * 0.5f, h * 0.75f)
            "left" -> listOf(w * 0.82f, h * 0.5f, w * 0.18f, h * 0.5f)
            "right" -> listOf(w * 0.18f, h * 0.5f, w * 0.82f, h * 0.5f)
            else -> return false to "Unsupported swipe direction: $direction"
        }

        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0L, 280L)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()

        val dispatched = dispatchGestureAwait(gesture)
        return if (dispatched) {
            true to "Swiped $direction."
        } else {
            false to "Swipe $direction gesture was cancelled or blocked."
        }
    }

    private suspend fun dispatchGestureAwait(gesture: GestureDescription): Boolean {
        return suspendCancellableCoroutine { cont ->
            val accepted = runCatching {
                dispatchGesture(
                    gesture,
                    object : GestureResultCallback() {
                        override fun onCompleted(gestureDescription: GestureDescription?) {
                            if (cont.isActive) cont.resume(true)
                        }

                        override fun onCancelled(gestureDescription: GestureDescription?) {
                            if (cont.isActive) cont.resume(false)
                        }
                    },
                    null
                )
            }.getOrDefault(false)
            if (!accepted && cont.isActive) {
                cont.resume(false)
            }
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
        var targetField: AccessibilityNodeInfo? = null
        try {
            val hintClean = fieldHint.trim()
            targetField = when {
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
            return applyTextToNode(targetField, text, submitAfterTyping)
        } finally {
            if (targetField != null && targetField !== root) safeRecycle(targetField)
            safeRecycle(root)
        }
    }

    private fun applyTextToNode(
        targetField: AccessibilityNodeInfo,
        text: String,
        submitAfterTyping: Boolean
    ): Pair<Boolean, String> {
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
    }

    private fun performClickOnNodeOrAncestor(node: AccessibilityNodeInfo, longClick: Boolean): Boolean {
        val action = if (longClick) AccessibilityNodeInfo.ACTION_LONG_CLICK else AccessibilityNodeInfo.ACTION_CLICK
        val visitedParents = mutableListOf<AccessibilityNodeInfo>()
        try {
            var current: AccessibilityNodeInfo? = node
            var hops = 0
            while (current != null && hops < 6) {
                val supportsAction = if (longClick) current.isLongClickable else current.isClickable
                if (supportsAction && current.performAction(action)) {
                    return true
                }
                val parent = current.parent
                if (parent != null) {
                    visitedParents.add(parent)
                }
                current = parent
                hops++
            }
            return node.performAction(action)
        } finally {
            visitedParents.forEach { safeRecycle(it) }
        }
    }

    /**
     * Locates the live [AccessibilityNodeInfo] corresponding to a [GroundedUiElement] by matching
     * screen bounds within a tight tolerance plus text/description/className.
     */
    private fun findNodeMatchingGroundedElement(
        root: AccessibilityNodeInfo,
        target: GroundedUiElement
    ): AccessibilityNodeInfo? {
        val rect = Rect()
        val tolerancePx = 24
        return findFirstNode(root) { node ->
            if (!node.isVisibleToUser || node.isPassword) return@findFirstNode false
            rect.setEmpty()
            node.getBoundsInScreen(rect)
            val boundsClose = abs(rect.left - target.bounds.x1) <= tolerancePx &&
                abs(rect.top - target.bounds.y1) <= tolerancePx &&
                abs(rect.right - target.bounds.x2) <= tolerancePx &&
                abs(rect.bottom - target.bounds.y2) <= tolerancePx
            if (!boundsClose) return@findFirstNode false

            val nodeText = node.text?.toString()?.trim().orEmpty()
            val nodeDesc = node.contentDescription?.toString()?.trim().orEmpty()
            val nodeClass = node.className?.toString().orEmpty()
            val labelMatches = (target.text.isNotBlank() && nodeText.equals(target.text, ignoreCase = true)) ||
                (target.contentDescription.isNotBlank() && nodeDesc.equals(target.contentDescription, ignoreCase = true)) ||
                (target.text.isBlank() && target.contentDescription.isBlank() && nodeClass == target.className)
            labelMatches
        }
    }

    private fun findMatchingNodes(root: AccessibilityNodeInfo, query: String): List<AccessibilityNodeInfo> {
        val exactMatches = mutableListOf<AccessibilityNodeInfo>()
        val partialMatches = mutableListOf<AccessibilityNodeInfo>()
        val lowerQuery = query.lowercase()

        fun walk(node: AccessibilityNodeInfo, depth: Int) {
            if (depth > MAX_TRAVERSAL_DEPTH || !node.isVisibleToUser || node.isPassword) return

            val text = node.text?.toString()?.trim().orEmpty()
            val desc = node.contentDescription?.toString()?.trim().orEmpty()
            val hint = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) node.hintText?.toString()?.trim().orEmpty() else ""

            val matchedExact = text.equals(query, ignoreCase = true) ||
                desc.equals(query, ignoreCase = true) ||
                hint.equals(query, ignoreCase = true)
            val matchedPartial = !matchedExact && (
                text.lowercase().contains(lowerQuery) ||
                    desc.lowercase().contains(lowerQuery) ||
                    hint.lowercase().contains(lowerQuery)
                )

            if (matchedExact) {
                exactMatches.add(node)
            } else if (matchedPartial) {
                partialMatches.add(node)
            }

            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                walk(child, depth + 1)
                if (child !== root && !exactMatches.contains(child) && !partialMatches.contains(child)) {
                    safeRecycle(child)
                }
            }
        }

        walk(root, 0)
        return if (exactMatches.isNotEmpty()) {
            partialMatches.forEach { if (it !== root) safeRecycle(it) }
            exactMatches
        } else {
            partialMatches
        }
    }

    private fun findFirstNode(
        root: AccessibilityNodeInfo,
        predicate: (AccessibilityNodeInfo) -> Boolean
    ): AccessibilityNodeInfo? {
        fun walk(node: AccessibilityNodeInfo, depth: Int): AccessibilityNodeInfo? {
            if (depth > MAX_TRAVERSAL_DEPTH) return null
            if (predicate(node)) return node
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                val found = walk(child, depth + 1)
                if (found != null) {
                    if (child !== found && child !== root) safeRecycle(child)
                    return found
                }
                if (child !== root) safeRecycle(child)
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

    @Suppress("DEPRECATION")
    private fun safeRecycle(node: AccessibilityNodeInfo?) {
        if (node != null) {
            runCatching { node.recycle() }
        }
    }

    companion object {
        private const val MAX_TRAVERSAL_DEPTH = 24
        private const val MAX_GROUNDED_ELEMENTS = 80

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
