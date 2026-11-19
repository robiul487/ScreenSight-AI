package com.example.service

import android.annotation.SuppressLint
import android.app.ActivityOptions
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.example.MainActivity
import com.example.agent.AgentExecutionState
import com.example.domain.model.LiveSessionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Floating Overlay Controller ("Display over other apps") (Priority 5).
 *
 * Allows the user to navigate anywhere on their phone while:
 * - Seeing a compact floating pill at the corner of the screen with live state indicator
 *   (ScreenSight Live / Gemini Speaking / Thinking... / Screen Paused / Voice Live)
 * - Dragging the floating pill anywhere on the screen (with automatic bounds clamping on rotation)
 * - Tapping the pill to expand quick controls:
 *   1. Mic: ON / MUTED
 *   2. Pause Screen / Resume Screen (or Share Screen when in voice-only mode)
 *   3. Open App (brings ScreenSight AI to front reliably via PendingIntent)
 *   4. End Live (terminates capture, WebSocket, and foreground service immediately)
 */
class FloatingOverlayController(
    private val context: Context,
    private val onToggleMic: () -> Unit,
    private val onTogglePauseCapture: () -> Unit,
    private val onEndSession: () -> Unit
) {
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var rootView: LinearLayout? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var statusDotView: View? = null
    private var statusLabelView: TextView? = null
    private var expandedPanel: LinearLayout? = null
    private var micButton: TextView? = null
    private var pauseButton: TextView? = null

    private var isExpanded = false
    private var isSharingScreenState = false
    private var isCapturePausedState = false
    private var isMicMutedState = false
    private var isAgentWorkingState = false
    private var hasPendingConfirmationState = false
    private var latestAgentState = AgentExecutionState.IDLE
    private var latestSessionState = LiveSessionState.IDLE
    private var stateJob: Job? = null

    fun canDrawOverlays(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(context)
        } else {
            true
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    fun showOverlay(sessionManager: SessionManager) {
        if (!canDrawOverlays()) return
        if (rootView != null) {
            observeSessionState(sessionManager)
            return
        }

        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = dp(12)
            y = dp(110)
        }
        layoutParams = params

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = createRoundedBackground(
                fillColor = Color.parseColor("#EE0F172A"),
                strokeColor = Color.parseColor("#38BDF8"),
                cornerRadiusDp = 24
            )
            setPadding(dp(12), dp(8), dp(12), dp(8))
            elevation = dp(10).toFloat()
        }

        // Top Compact Pill Row (Always visible, draggable, tap to expand/collapse)
        val pillRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(32)
        }

        val dot = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(10), dp(10)).apply {
                marginEnd = dp(8)
            }
            background = createRoundedBackground(
                fillColor = Color.parseColor("#10B981"),
                strokeColor = Color.TRANSPARENT,
                cornerRadiusDp = 10
            )
        }
        statusDotView = dot

        val titleText = TextView(context).apply {
            text = "ScreenSight Live ▾"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Typeface.DEFAULT_BOLD
        }
        statusLabelView = titleText

        pillRow.addView(dot)
        pillRow.addView(titleText)

        // Expanded Control Actions Row (Visible when tapped)
        val controlsLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(0, dp(8), 0, dp(2))
        }

        val row1 = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        val micBtn = createActionChip("Mic: ON", "#1E293B", "#38BDF8") {
            onToggleMic()
        }
        micButton = micBtn

        val pauseBtn = createActionChip("Pause Screen", "#1E293B", "#F59E0B") {
            if (isSharingScreenState) {
                onTogglePauseCapture()
            } else {
                // If user is in voice-only mode, open app so they can start screen sharing
                openMainApplication()
            }
        }
        pauseButton = pauseBtn

        row1.addView(micBtn)
        row1.addView(pauseBtn)

        val row2 = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(6), 0, 0)
        }

        val openAppBtn = createActionChip("Open App", "#1E293B", "#E2E8F0") {
            openMainApplication()
        }

        val stopBtn = createActionChip("End Live", "#7F1D1D", "#FCA5A5") {
            onEndSession()
        }

        row2.addView(openAppBtn)
        row2.addView(stopBtn)

        controlsLayout.addView(row1)
        controlsLayout.addView(row2)
        expandedPanel = controlsLayout

        container.addView(pillRow)
        container.addView(controlsLayout)

        // Drag and Click Touch Listener on the pill header
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var isDragging = false

        pillRow.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isDragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (initialTouchX - event.rawX).toInt() // Gravity.END uses right-offset
                    val dy = (event.rawY - initialTouchY).toInt()
                    if (abs(dx) > dp(6) || abs(dy) > dp(6)) {
                        isDragging = true
                        val metrics = context.resources.displayMetrics
                        val maxX = (metrics.widthPixels - dp(80)).coerceAtLeast(0)
                        val maxY = (metrics.heightPixels - dp(80)).coerceAtLeast(0)
                        params.x = (initialX + dx).coerceIn(0, maxX)
                        params.y = (initialY + dy).coerceIn(0, maxY)
                        runCatching { windowManager.updateViewLayout(container, params) }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!isDragging) {
                        isExpanded = !isExpanded
                        expandedPanel?.visibility = if (isExpanded) View.VISIBLE else View.GONE
                        refreshStatusUi()
                    }
                    true
                }
                else -> false
            }
        }

        try {
            windowManager.addView(container, params)
            rootView = container
            observeSessionState(sessionManager)
        } catch (_: Exception) {
            rootView = null
            layoutParams = null
        }
    }

    fun onConfigurationChanged() {
        val view = rootView ?: return
        val params = layoutParams ?: return
        val metrics = context.resources.displayMetrics
        val maxX = (metrics.widthPixels - dp(80)).coerceAtLeast(0)
        val maxY = (metrics.heightPixels - dp(80)).coerceAtLeast(0)
        params.x = params.x.coerceIn(0, maxX)
        params.y = params.y.coerceIn(0, maxY)
        runCatching { windowManager.updateViewLayout(view, params) }
    }

    private fun openMainApplication() {
        isExpanded = false
        expandedPanel?.visibility = View.GONE
        refreshStatusUi()

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_SINGLE_TOP or
                Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
        }

        val pendingOpened = runCatching {
            val pendingIntent = PendingIntent.getActivity(
                context,
                901,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val options = ActivityOptions.makeBasic().apply {
                    setPendingIntentBackgroundActivityStartMode(
                        ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
                    )
                }
                pendingIntent.send(context, 0, null, null, null, null, options.toBundle())
            } else {
                pendingIntent.send()
            }
            true
        }.getOrDefault(false)

        if (!pendingOpened) {
            runCatching { context.startActivity(intent) }
        }
    }

    private fun observeSessionState(sessionManager: SessionManager) {
        stateJob?.cancel()
        stateJob = mainScope.launch {
            launch {
                sessionManager.sessionState.collectLatest { state ->
                    latestSessionState = state
                    refreshStatusUi()
                }
            }
            launch {
                sessionManager.isSharingScreen.collectLatest { sharing ->
                    isSharingScreenState = sharing
                    refreshStatusUi()
                }
            }
            launch {
                sessionManager.isScreenCapturePaused.collectLatest { paused ->
                    isCapturePausedState = paused
                    refreshStatusUi()
                }
            }
            launch {
                sessionManager.audioCaptureManager.isMuted.collectLatest { muted ->
                    isMicMutedState = muted
                    micButton?.text = if (muted) "Mic: MUTED" else "Mic: ON"
                    micButton?.setTextColor(
                        Color.parseColor(if (muted) "#F59E0B" else "#38BDF8")
                    )
                }
            }
            sessionManager.agentCoordinator?.let { coordinator ->
                launch {
                    coordinator.isTaskRunning.collectLatest { running ->
                        isAgentWorkingState = running
                        refreshStatusUi()
                    }
                }
                launch {
                    coordinator.executionState.collectLatest { execState ->
                        latestAgentState = execState
                        refreshStatusUi()
                    }
                }
                launch {
                    coordinator.pendingConfirmation.collectLatest { pending ->
                        hasPendingConfirmationState = pending != null
                        refreshStatusUi()
                    }
                }
            }
        }
    }

    private fun refreshStatusUi() {
        val arrow = if (isExpanded) "▴" else "▾"
        val label = when {
            hasPendingConfirmationState || latestAgentState == AgentExecutionState.WAITING_FOR_USER ->
                "Confirm Action $arrow"
            latestAgentState == AgentExecutionState.PLANNING -> "Planning... $arrow"
            latestAgentState == AgentExecutionState.OBSERVING -> "Observing... $arrow"
            latestAgentState == AgentExecutionState.RECOVERING -> "Recovering... $arrow"
            latestAgentState == AgentExecutionState.EXECUTING || isAgentWorkingState ->
                "Agent Working... $arrow"
            isSharingScreenState && isCapturePausedState -> "Screen Paused $arrow"
            latestSessionState == LiveSessionState.SPEAKING -> "Gemini Speaking $arrow"
            latestSessionState == LiveSessionState.THINKING -> "Thinking... $arrow"
            latestSessionState == LiveSessionState.RECONNECTING -> "Reconnecting... $arrow"
            isSharingScreenState -> "ScreenSight Live $arrow"
            else -> "Voice Live $arrow"
        }
        statusLabelView?.text = label

        val dotColor = when {
            latestSessionState == LiveSessionState.ERROR -> "#EF4444"
            hasPendingConfirmationState ||
                latestAgentState == AgentExecutionState.WAITING_FOR_USER ||
                latestAgentState == AgentExecutionState.RECOVERING -> "#F59E0B"
            latestAgentState == AgentExecutionState.PLANNING ||
                latestAgentState == AgentExecutionState.EXECUTING ||
                latestAgentState == AgentExecutionState.OBSERVING ||
                isAgentWorkingState -> "#38BDF8"
            latestSessionState == LiveSessionState.SPEAKING -> "#818CF8"
            latestSessionState == LiveSessionState.RECONNECTING ||
                latestSessionState == LiveSessionState.THINKING -> "#F59E0B"
            isSharingScreenState && isCapturePausedState -> "#F59E0B"
            isSharingScreenState -> "#10B981"
            else -> "#38BDF8"
        }
        statusDotView?.background = createRoundedBackground(
            fillColor = Color.parseColor(dotColor),
            strokeColor = Color.TRANSPARENT,
            cornerRadiusDp = 10
        )

        pauseButton?.let { btn ->
            when {
                !isSharingScreenState -> {
                    btn.text = "Share Screen"
                    btn.setTextColor(Color.parseColor("#10B981"))
                }
                isCapturePausedState -> {
                    btn.text = "Resume Screen"
                    btn.setTextColor(Color.parseColor("#10B981"))
                }
                else -> {
                    btn.text = "Pause Screen"
                    btn.setTextColor(Color.parseColor("#F59E0B"))
                }
            }
        }
    }

    private fun createActionChip(
        label: String,
        bgColorHex: String,
        textColorHex: String,
        onClick: () -> Unit
    ): TextView {
        return TextView(context).apply {
            text = label
            setTextColor(Color.parseColor(textColorHex))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.DEFAULT_BOLD
            background = createRoundedBackground(
                fillColor = Color.parseColor(bgColorHex),
                strokeColor = Color.parseColor("#334155"),
                cornerRadiusDp = 14
            )
            setPadding(dp(12), dp(8), dp(12), dp(8))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginEnd = dp(6)
            }
            setOnClickListener { onClick() }
        }
    }

    private fun createRoundedBackground(
        fillColor: Int,
        strokeColor: Int,
        cornerRadiusDp: Int
    ): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(cornerRadiusDp).toFloat()
            setColor(fillColor)
            if (strokeColor != Color.TRANSPARENT) {
                setStroke(dp(1), strokeColor)
            }
        }
    }

    fun hideOverlay() {
        stateJob?.cancel()
        stateJob = null
        rootView?.let { view ->
            runCatching { windowManager.removeView(view) }
        }
        rootView = null
        layoutParams = null
        statusDotView = null
        statusLabelView = null
        expandedPanel = null
        micButton = null
        pauseButton = null
        isExpanded = false
        isCapturePausedState = false
    }

    private fun dp(value: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            context.resources.displayMetrics
        ).toInt()
    }
}
