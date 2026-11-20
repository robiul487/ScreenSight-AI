package com.example.assistant

import android.app.assist.AssistContent
import android.app.assist.AssistStructure
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.service.voice.VoiceInteractionSession
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.RequiresApi
import com.example.MainActivity
import com.example.ScreenSightApp

/**
 * Lightweight, responsive Android [VoiceInteractionSession] sheet for Neya.
 *
 * Uses native Android Views inside the VoiceInteractionSession window so there are zero
 * Compose-in-Window lifecycle crashes, zero duplicate AgentCoordinators, and zero forced
 * MediaProjection prompts.
 *
 * Connects directly to the single existing [NeyaAssistantController], which routes into
 * existing [com.example.service.SessionManager], [com.example.agent.AgentCoordinator],
 * [com.example.agent.MultiAgentOrchestrator], and [com.example.agent.LongTermTaskOrchestrator].
 */
class NeyaVoiceInteractionSession(
    context: Context
) : VoiceInteractionSession(context) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var statusTextView: TextView? = null
    private var responseTextView: TextView? = null
    private var commandInput: EditText? = null
    private var liveVoiceButton: Button? = null

    private val appContainer
        get() = (context.applicationContext as? ScreenSightApp)?.container

    private val controller: NeyaAssistantController?
        get() = appContainer?.neyaAssistantController

    override fun onCreateContentView(): View {
        val density = context.resources.displayMetrics.density
        fun dp(value: Int): Int = (value * density).toInt()

        val root = FrameLayout(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.parseColor("#66000000"))
            setOnClickListener { hide() }
        }

        val sheetBackground = GradientDrawable().apply {
            setColor(Color.parseColor("#111827"))
            cornerRadii = floatArrayOf(
                dp(24).toFloat(), dp(24).toFloat(),
                dp(24).toFloat(), dp(24).toFloat(),
                0f, 0f, 0f, 0f
            )
            setStroke(dp(1), Color.parseColor("#2A3B55"))
        }

        val sheet = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = sheetBackground
            setPadding(dp(20), dp(18), dp(20), dp(24))
            isClickable = true
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM
            )
        }

        // Header Row: Title + Close button
        val headerRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val titleCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val titleView = TextView(context).apply {
            text = "Neya • ScreenSight AI"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            typeface = Typeface.DEFAULT_BOLD
        }

        val statusView = TextView(context).apply {
            text = "Ready • System Digital Assistant"
            setTextColor(Color.parseColor("#93C5FD"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        }
        statusTextView = statusView

        titleCol.addView(titleView)
        titleCol.addView(statusView)

        val closeBtn = createStyledButton(
            label = "Close",
            bgColor = Color.parseColor("#1F2937"),
            textColor = Color.parseColor("#E5E7EB"),
            dp = ::dp
        ).apply {
            contentDescription = "Close Neya assistant"
            setOnClickListener { hide() }
        }

        headerRow.addView(titleCol)
        headerRow.addView(closeBtn)
        sheet.addView(headerRow)

        // Response / status feedback banner
        val respView = TextView(context).apply {
            text = "Ask Neya anything, run a device command, or connect to Gemini Live voice."
            setTextColor(Color.parseColor("#D1D5DB"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(0, dp(12), 0, dp(12))
        }
        responseTextView = respView
        sheet.addView(respView)

        // Command input row
        val inputRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(12)
            }
        }

        val inputBg = GradientDrawable().apply {
            setColor(Color.parseColor("#1F2937"))
            cornerRadius = dp(14).toFloat()
            setStroke(dp(1), Color.parseColor("#374151"))
        }

        val edit = EditText(context).apply {
            hint = "Tell Neya what to do..."
            setHintTextColor(Color.parseColor("#9CA3AF"))
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            background = inputBg
            setPadding(dp(14), dp(12), dp(14), dp(12))
            minHeight = dp(48)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            imeOptions = EditorInfo.IME_ACTION_SEND
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(8)
            }
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEND) {
                    dispatchCommandInput()
                    true
                } else {
                    false
                }
            }
        }
        commandInput = edit

        val runBtn = createStyledButton(
            label = "Run",
            bgColor = Color.parseColor("#38BDF8"),
            textColor = Color.parseColor("#082F49"),
            dp = ::dp
        ).apply {
            contentDescription = "Run command with Neya"
            setOnClickListener { dispatchCommandInput() }
        }

        inputRow.addView(edit)
        inputRow.addView(runBtn)
        sheet.addView(inputRow)

        // Action Buttons Row: Voice with Gemini Live + Open Full App
        val actionsRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val voiceBtn = createStyledButton(
            label = "Live Voice",
            bgColor = Color.parseColor("#2563EB"),
            textColor = Color.WHITE,
            dp = ::dp
        ).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(8)
            }
            contentDescription = "Start or focus Gemini Live voice session"
            setOnClickListener {
                val msg = controller?.startOrFocusLiveVoiceFromAssistant()
                    ?: "ScreenSight AI engine is initializing..."
                responseTextView?.text = msg
                refreshHeaderTelemetry()
            }
        }
        liveVoiceButton = voiceBtn

        val openAppBtn = createStyledButton(
            label = "Open ScreenSight",
            bgColor = Color.parseColor("#1E293B"),
            textColor = Color.parseColor("#E2E8F0"),
            dp = ::dp
        ).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            contentDescription = "Open full ScreenSight AI app"
            setOnClickListener { openFullApp() }
        }

        actionsRow.addView(voiceBtn)
        actionsRow.addView(openAppBtn)
        sheet.addView(actionsRow)

        root.addView(sheet)
        refreshHeaderTelemetry()
        return root
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        controller?.onAssistantSessionShown(AssistantInvocationSource.VOICE_INTERACTION_SESSION)
        refreshHeaderTelemetry()
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    override fun onHandleAssist(state: AssistState) {
        super.onHandleAssist(state)
        // Intentionally do not log or persist AssistStructure text to protect passwords/PII.
        refreshHeaderTelemetry()
    }

    @Deprecated("Deprecated in Android API 29 in favor of AssistState overload")
    @Suppress("DEPRECATION")
    override fun onHandleAssist(
        data: Bundle?,
        structure: AssistStructure?,
        content: AssistContent?
    ) {
        super.onHandleAssist(data, structure, content)
        refreshHeaderTelemetry()
    }

    override fun onHide() {
        // Never cancel unrelated running autonomous tasks or user-owned Gemini Live sessions on hide.
        controller?.onAssistantSessionHidden(stopLiveIfOwnedBySession = false)
        super.onHide()
    }

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        controller?.onAssistantSessionHidden(stopLiveIfOwnedBySession = false)
        statusTextView = null
        responseTextView = null
        commandInput = null
        liveVoiceButton = null
        super.onDestroy()
    }

    private fun dispatchCommandInput() {
        val text = commandInput?.text?.toString()?.trim().orEmpty()
        if (text.isEmpty()) return
        commandInput?.setText("")
        responseTextView?.text = "Processing \"$text\"..."
        controller?.submitAssistantCommandAsync(text) { result ->
            val display = when (result) {
                is AssistantCommandResult.SentToLiveSession ->
                    "Sent to active Gemini Live session."
                is AssistantCommandResult.AnsweredDirectly ->
                    "[${result.modelBadge}] ${result.answer}"
                is AssistantCommandResult.ExecutedTask ->
                    result.summary
                is AssistantCommandResult.StartedQuickAction ->
                    result.summary
                is AssistantCommandResult.BlockedOrFailed ->
                    result.reason
                AssistantCommandResult.EmptyInput ->
                    "Enter a command for Neya."
            }
            mainHandler.post {
                responseTextView?.text = display
                refreshHeaderTelemetry()
            }
        }
    }

    private fun openFullApp() {
        val intent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_ASSIST
            putExtra(NeyaAssistantController.EXTRA_OPEN_ASSISTANT_CONSOLE, true)
            putExtra(
                NeyaAssistantController.EXTRA_ASSISTANT_SOURCE,
                AssistantInvocationSource.VOICE_INTERACTION_SESSION.name
            )
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        runCatching {
            startAssistantActivity(intent)
        }.onFailure {
            runCatching { context.startActivity(intent) }
        }
        hide()
    }

    private fun refreshHeaderTelemetry() {
        val container = appContainer
        val liveState = container?.sessionManager?.sessionState?.value
        val isLiveActive = liveState?.isActive == true
        val isTaskRunning = container?.agentCoordinator?.isTaskRunning?.value == true

        statusTextView?.text = when {
            isLiveActive -> "Gemini Live Active (${liveState.displayName})"
            isTaskRunning -> "Autonomous Task Running • Ready for commands"
            else -> "Ready • Local Agent & Gemma Reasoning"
        }
        liveVoiceButton?.text = if (isLiveActive) "Live Active" else "Live Voice"
    }

    private fun createStyledButton(
        label: String,
        bgColor: Int,
        textColor: Int,
        dp: (Int) -> Int
    ): Button {
        val drawable = GradientDrawable().apply {
            setColor(bgColor)
            cornerRadius = dp(12).toFloat()
        }
        return Button(context).apply {
            text = label
            isAllCaps = false
            setTextColor(textColor)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.DEFAULT_BOLD
            background = drawable
            minHeight = dp(48)
            minWidth = dp(48)
            setPadding(dp(16), dp(10), dp(16), dp(10))
        }
    }
}
