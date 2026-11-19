package com.example.agent

import com.example.data.remote.BidiFunctionDeclaration
import com.example.data.remote.BidiSchema
import com.example.data.remote.BidiTool

/**
 * Centralized Android Agent Tool Registry (Phase 2, Sections 4 & 5).
 *
 * Guarantees that Gemini Live can ONLY invoke tools that are genuinely implemented,
 * registered, locally validated, and permission-checked.
 *
 * Also supports registering mock/test tools in JVM unit and integration tests.
 */
class AgentToolRegistry(
    private val defaultActionExecutor: (suspend (AgentActionRequest, Boolean) -> AgentActionResult)? = null
) {

    enum class RequiredPermission {
        NONE,
        ACCESSIBILITY_SERVICE,
        ALARM_INTENT,
        CONTACTS_OPTIONAL
    }

    data class RegisteredTool(
        val actionType: AgentActionType,
        val name: String = actionType.toolName,
        val description: String,
        val parametersSchema: BidiSchema,
        val requiredPermissions: Set<RequiredPermission> = setOf(RequiredPermission.NONE),
        val timeoutMs: Long = 12_000L,
        val supportsRetry: Boolean = false,
        val validator: (Map<String, String>) -> ToolValidationResult = { ToolValidationResult.VALID },
        val executor: (suspend (AgentActionRequest, Boolean) -> AgentActionResult)? = null
    )

    private val toolsByName = LinkedHashMap<String, RegisteredTool>()

    init {
        registerDefaultTools()
    }

    fun registerTool(tool: RegisteredTool) {
        toolsByName[tool.name.lowercase()] = tool
    }

    fun getTool(toolName: String): RegisteredTool? {
        val direct = toolsByName[toolName.trim().lowercase()]
        if (direct != null) return direct
        val mapped = AgentActionType.fromToolName(toolName) ?: return null
        return toolsByName[mapped.toolName.lowercase()]
    }

    fun listRegisteredTools(): List<RegisteredTool> = toolsByName.values.toList()

    /**
     * Strictly validates tool existence and argument structure locally BEFORE safety/execution.
     */
    fun validateCall(toolName: String, params: Map<String, String>): Pair<RegisteredTool?, ToolValidationResult> {
        val cleanName = toolName.trim()
        if (cleanName.isEmpty()) {
            return null to ToolValidationResult.invalid(
                code = "INVALID_ARGUMENTS",
                en = "Tool call is missing a tool name.",
                bn = "সঠিক টুলের নাম পাওয়া যায়নি।"
            )
        }
        val tool = getTool(cleanName)
            ?: return null to ToolValidationResult.invalid(
                code = "UNSUPPORTED_ACTION",
                en = "Tool \"$cleanName\" is not registered or supported on this device.",
                bn = "\"$cleanName\" কাজটি এই অ্যাপে সাপোর্টেড নয়।"
            )

        // Check declared required fields from JSON schema
        val missingRequired = tool.parametersSchema.required.orEmpty().filter { key ->
            params[key]?.trim().isNullOrEmpty()
        }
        if (missingRequired.isNotEmpty()) {
            val missing = missingRequired.joinToString(", ")
            return tool to ToolValidationResult.invalid(
                code = "INVALID_ARGUMENTS",
                en = "Missing required argument(s) for ${tool.name}: $missing",
                bn = "প্রয়োজনীয় তথ্য পাওয়া যায়নি ($missing)।"
            )
        }

        val customValidation = tool.validator(params)
        if (!customValidation.isValid) {
            return tool to customValidation
        }
        return tool to ToolValidationResult.VALID
    }

    suspend fun executeRegisteredTool(
        tool: RegisteredTool,
        request: AgentActionRequest,
        userConfirmed: Boolean
    ): AgentActionResult {
        val handler = tool.executor ?: defaultActionExecutor
            ?: return AgentActionResult(
                success = false,
                status = "NO_EXECUTOR",
                summaryEn = "No execution backend is configured for ${tool.name}.",
                summaryBn = "এই কাজের এক্সিকিউটর কনফিগার করা নেই।"
            )
        return handler(request, userConfirmed)
    }

    /**
     * Generates the official Gemini Live (`BidiGenerateContent`) `tools` list exclusively
     * from genuinely registered tools.
     */
    fun toGeminiLiveTools(): List<BidiTool> {
        val declarations = toolsByName.values.map { tool ->
            BidiFunctionDeclaration(
                name = tool.name,
                description = tool.description,
                parameters = tool.parametersSchema
            )
        }
        return listOf(BidiTool(functionDeclarations = declarations))
    }

    private fun registerDefaultTools() {
        // --- 1. Planning & Autonomous Lifecycle Tools ---
        registerTool(
            RegisteredTool(
                actionType = AgentActionType.CREATE_PLAN,
                description = "Creates or registers an ordered multi-step task plan before executing a complex smartphone task (e.g., open app -> search -> pick result -> verify).",
                parametersSchema = BidiSchema(
                    type = "OBJECT",
                    properties = mapOf(
                        "task_description" to BidiSchema(type = "STRING", description = "Short summary of the user's goal."),
                        "steps" to BidiSchema(type = "STRING", description = "Pipe-separated or numbered list of planned steps (e.g., 'open_app:YouTube | search_in_app:Bangla music | click_element:first video | verify_screen:playing')."),
                        "expected_outcome" to BidiSchema(type = "STRING", description = "How completion will be verified.")
                    ),
                    required = listOf("task_description", "steps")
                ),
                timeoutMs = 3_000L
            )
        )

        registerTool(
            RegisteredTool(
                actionType = AgentActionType.UPDATE_PLAN,
                description = "Dynamically adjusts the remaining task plan when screen observation shows an unexpected UI state or recoverable error.",
                parametersSchema = BidiSchema(
                    type = "OBJECT",
                    properties = mapOf(
                        "reason" to BidiSchema(type = "STRING", description = "Why the plan is changing based on screen observation."),
                        "remaining_steps" to BidiSchema(type = "STRING", description = "Updated pipe-separated remaining steps.")
                    ),
                    required = listOf("reason", "remaining_steps")
                ),
                timeoutMs = 3_000L
            )
        )

        registerTool(
            RegisteredTool(
                actionType = AgentActionType.COMPLETE_TASK,
                description = "Marks the current multi-step task as COMPLETED or FAILED after verifying the final screen state.",
                parametersSchema = BidiSchema(
                    type = "OBJECT",
                    properties = mapOf(
                        "status" to BidiSchema(type = "STRING", description = "'completed' or 'failed'", enum = listOf("completed", "failed")),
                        "summary_en" to BidiSchema(type = "STRING", description = "Concise final outcome summary in English."),
                        "summary_bn" to BidiSchema(type = "STRING", description = "Short, natural final outcome summary in Bangla.")
                    ),
                    required = listOf("status", "summary_bn")
                ),
                timeoutMs = 3_000L
            )
        )

        // --- 2. Screen & App Observation Tools ---
        registerTool(
            RegisteredTool(
                actionType = AgentActionType.GET_SCREEN_STATE,
                description = "Returns structured grounded Accessibility UI elements (element_id, label, class, clickable/scrollable/editable/enabled flags, pixel & normalized 0.0-1.0 bounding boxes) plus observation_id, active package, and screen dimensions. Passwords/OTPs are redacted.",
                parametersSchema = BidiSchema(type = "OBJECT", properties = emptyMap()),
                requiredPermissions = setOf(RequiredPermission.ACCESSIBILITY_SERVICE),
                timeoutMs = 5_000L,
                supportsRetry = true
            )
        )

        registerTool(
            RegisteredTool(
                actionType = AgentActionType.GET_CURRENT_APP,
                description = "Returns the package name and label of the currently foregrounded Android application.",
                parametersSchema = BidiSchema(type = "OBJECT", properties = emptyMap()),
                requiredPermissions = setOf(RequiredPermission.ACCESSIBILITY_SERVICE),
                timeoutMs = 4_000L
            )
        )

        registerTool(
            RegisteredTool(
                actionType = AgentActionType.READ_SCREEN,
                description = "Reads visible non-sensitive text, clickable buttons, and input fields from the active Android screen via AccessibilityService. Passwords and OTPs are automatically redacted.",
                parametersSchema = BidiSchema(
                    type = "OBJECT",
                    properties = mapOf(
                        "user_authorized_private_read" to BidiSchema(
                            type = "STRING",
                            description = "Set to 'true' ONLY if the user explicitly asked to read private messages (like WhatsApp/SMS)."
                        )
                    )
                ),
                requiredPermissions = setOf(RequiredPermission.ACCESSIBILITY_SERVICE),
                timeoutMs = 6_000L,
                supportsRetry = true
            )
        )

        registerTool(
            RegisteredTool(
                actionType = AgentActionType.VERIFY_SCREEN,
                description = "Captures a fresh Accessibility UI snapshot and checks whether expected_outcome is visible on screen.",
                parametersSchema = BidiSchema(
                    type = "OBJECT",
                    properties = mapOf(
                        "expected_outcome" to BidiSchema(type = "STRING", description = "Keyword or UI indicator expected on screen (e.g., 'Pause', 'Subscribe', 'Search results').")
                    )
                ),
                requiredPermissions = setOf(RequiredPermission.ACCESSIBILITY_SERVICE),
                timeoutMs = 6_000L,
                supportsRetry = true
            )
        )

        registerTool(
            RegisteredTool(
                actionType = AgentActionType.WAIT_FOR_UI,
                description = "Waits for an app or screen transition to finish loading (optionally until expected_text appears) and returns a fresh Accessibility UI snapshot.",
                parametersSchema = BidiSchema(
                    type = "OBJECT",
                    properties = mapOf(
                        "expected_text" to BidiSchema(type = "STRING", description = "Optional text or package fragment expected to appear."),
                        "timeout_ms" to BidiSchema(type = "STRING", description = "Optional timeout in ms (300 to 8000, default 3000).")
                    )
                ),
                requiredPermissions = setOf(RequiredPermission.ACCESSIBILITY_SERVICE),
                timeoutMs = 10_000L,
                validator = { params ->
                    val rawTimeout = params["timeout_ms"]?.trim()
                    if (!rawTimeout.isNullOrEmpty()) {
                        val ms = rawTimeout.toLongOrNull()
                        if (ms == null || ms !in 100L..10_000L) {
                            return@RegisteredTool ToolValidationResult.invalid(
                                "INVALID_ARGUMENTS",
                                "timeout_ms must be a number between 100 and 10000.",
                                "অপেক্ষার সময় ১০০ থেকে ১০০০০ মিলিসেকেন্ডের মধ্যে হতে হবে।"
                            )
                        }
                    }
                    ToolValidationResult.VALID
                }
            )
        )

        // --- 3. App Launching & Official Android Intents ---
        registerTool(
            RegisteredTool(
                actionType = AgentActionType.OPEN_APP,
                description = "Opens an installed Android application by English or Bangla name (e.g., YouTube, WhatsApp, Facebook, Chrome, Maps, Settings) using PackageManager.",
                parametersSchema = BidiSchema(
                    type = "OBJECT",
                    properties = mapOf(
                        "app_name" to BidiSchema(type = "STRING", description = "Application name in English or Bangla."),
                        "reason" to BidiSchema(type = "STRING", description = "Short reason for opening the app.")
                    ),
                    required = listOf("app_name")
                ),
                timeoutMs = 8_000L,
                supportsRetry = true
            )
        )

        registerTool(
            RegisteredTool(
                actionType = AgentActionType.OPEN_SETTINGS,
                description = "Opens Android system Settings or a specific non-security settings screen (wifi, bluetooth, display, sound, battery, accessibility, apps, date).",
                parametersSchema = BidiSchema(
                    type = "OBJECT",
                    properties = mapOf(
                        "section" to BidiSchema(type = "STRING", description = "Optional section: general, wifi, bluetooth, display, sound, battery, accessibility, apps, date.")
                    )
                ),
                timeoutMs = 6_000L
            )
        )

        registerTool(
            RegisteredTool(
                actionType = AgentActionType.OPEN_URL,
                description = "Opens a valid http/https web URL in the default browser via ACTION_VIEW.",
                parametersSchema = BidiSchema(
                    type = "OBJECT",
                    properties = mapOf(
                        "url" to BidiSchema(type = "STRING", description = "Full web URL to open.")
                    ),
                    required = listOf("url")
                ),
                timeoutMs = 6_000L,
                validator = { params ->
                    val url = params["url"]?.trim().orEmpty()
                    if (url.contains(" ") || url.startsWith("javascript:", ignoreCase = true) || url.startsWith("file:", ignoreCase = true)) {
                        ToolValidationResult.invalid(
                            "INVALID_URL",
                            "Unsupported or unsafe URL scheme.",
                            "এই ওয়েব লিংকটি নিরাপদ বা সঠিক নয়।"
                        )
                    } else {
                        ToolValidationResult.VALID
                    }
                }
            )
        )

        registerTool(
            RegisteredTool(
                actionType = AgentActionType.SEARCH_TEXT,
                description = "Searches for content in an app. For YouTube, opens YouTube search directly via deep link; for Chrome/Google opens web search; for other open apps uses AccessibilityService search input.",
                parametersSchema = BidiSchema(
                    type = "OBJECT",
                    properties = mapOf(
                        "query" to BidiSchema(type = "STRING", description = "Search query in Bangla or English."),
                        "app_name" to BidiSchema(type = "STRING", description = "Optional target app name, e.g., 'YouTube' or 'Chrome'.")
                    ),
                    required = listOf("query")
                ),
                timeoutMs = 10_000L,
                supportsRetry = true
            )
        )

        registerTool(
            RegisteredTool(
                actionType = AgentActionType.SET_VOLUME,
                description = "Adjusts Android media playback volume safely (up, down, mute, max, or set with percent 0-100).",
                parametersSchema = BidiSchema(
                    type = "OBJECT",
                    properties = mapOf(
                        "direction" to BidiSchema(type = "STRING", description = "up, down, mute, max, or set", enum = listOf("up", "down", "mute", "max", "set")),
                        "percent" to BidiSchema(type = "STRING", description = "Target percentage 0 to 100 when direction is 'set'.")
                    ),
                    required = listOf("direction")
                ),
                timeoutMs = 4_000L,
                validator = { params ->
                    val dir = params["direction"]?.trim()?.lowercase().orEmpty()
                    val allowed = setOf("up", "increase", "higher", "down", "decrease", "lower", "mute", "silent", "max", "full", "set", "বাড়াও", "বাড়াও", "কমাও", "বন্ধ")
                    if (dir !in allowed) {
                        return@RegisteredTool ToolValidationResult.invalid(
                            "INVALID_ARGUMENTS",
                            "Invalid volume direction: $dir",
                            "ভলিউম নির্দেশনাটি সঠিক নয়।"
                        )
                    }
                    if (dir == "set") {
                        val pct = params["percent"]?.trim()?.toIntOrNull()
                        if (pct == null || pct !in 0..100) {
                            return@RegisteredTool ToolValidationResult.invalid(
                                "INVALID_ARGUMENTS",
                                "percent must be between 0 and 100 when direction is 'set'.",
                                "ভলিউমের পরিমাণ ০ থেকে ১০০-এর মধ্যে হতে হবে।"
                            )
                        }
                    }
                    ToolValidationResult.VALID
                }
            )
        )

        registerTool(
            RegisteredTool(
                actionType = AgentActionType.SET_ALARM,
                description = "Creates an alarm using the official Android AlarmClock Intent. Ask the user to clarify AM/PM if time is ambiguous.",
                parametersSchema = BidiSchema(
                    type = "OBJECT",
                    properties = mapOf(
                        "hour_24" to BidiSchema(type = "STRING", description = "Hour in 24-hour format (0 to 23)."),
                        "minute" to BidiSchema(type = "STRING", description = "Minute (0 to 59)."),
                        "label" to BidiSchema(type = "STRING", description = "Optional alarm label.")
                    ),
                    required = listOf("hour_24", "minute")
                ),
                requiredPermissions = setOf(RequiredPermission.ALARM_INTENT),
                timeoutMs = 6_000L,
                validator = { params ->
                    val hour = params["hour_24"]?.trim()?.toIntOrNull()
                    val minute = params["minute"]?.trim()?.toIntOrNull()
                    if (hour == null || hour !in 0..23 || minute == null || minute !in 0..59) {
                        ToolValidationResult.invalid(
                            "INVALID_TIME",
                            "Alarm hour_24 must be 0..23 and minute must be 0..59.",
                            "অ্যালার্মের সময়টি সঠিক নয়। অনুগ্রহ করে ঠিক কয়টায় অ্যালার্ম দেবেন তা বলুন।"
                        )
                    } else {
                        ToolValidationResult.VALID
                    }
                }
            )
        )

        registerTool(
            RegisteredTool(
                actionType = AgentActionType.GET_DATE_TIME,
                description = "Returns the exact current local date, day of week, and time from the Android device clock.",
                parametersSchema = BidiSchema(type = "OBJECT", properties = emptyMap()),
                timeoutMs = 3_000L
            )
        )

        // --- 4. UI Interaction Tools (AccessibilityService) ---
        registerTool(
            RegisteredTool(
                actionType = AgentActionType.CLICK_ELEMENT,
                description = "Taps a visible UI element using priority order: (1) grounded element_id + observation_id from get_screen_state or target_text Accessibility node, (2) element bounding-box center, (3) validated pixel (x,y) or normalized (norm_x,norm_y in 0.0-1.0) coordinates.",
                parametersSchema = BidiSchema(
                    type = "OBJECT",
                    properties = mapOf(
                        "element_id" to BidiSchema(type = "STRING", description = "Preferred stable element ID from latest get_screen_state (e.g., 'el_3')."),
                        "observation_id" to BidiSchema(type = "STRING", description = "Observation ID returned by get_screen_state to prevent stale taps."),
                        "target_text" to BidiSchema(type = "STRING", description = "Visible label or content description of the UI element to tap."),
                        "occurrence_index" to BidiSchema(type = "STRING", description = "Optional 0-based index when multiple items match."),
                        "x" to BidiSchema(type = "STRING", description = "Optional screen X pixel coordinate fallback."),
                        "y" to BidiSchema(type = "STRING", description = "Optional screen Y pixel coordinate fallback."),
                        "norm_x" to BidiSchema(type = "STRING", description = "Optional normalized X coordinate (0.0 to 1.0) fallback."),
                        "norm_y" to BidiSchema(type = "STRING", description = "Optional normalized Y coordinate (0.0 to 1.0) fallback."),
                        "reason" to BidiSchema(type = "STRING", description = "Why this element is being tapped.")
                    )
                ),
                requiredPermissions = setOf(RequiredPermission.ACCESSIBILITY_SERVICE),
                timeoutMs = 8_000L,
                supportsRetry = true,
                validator = { params ->
                    val hasElementId = !params["element_id"]?.trim().isNullOrEmpty()
                    val hasText = !params["target_text"]?.trim().isNullOrEmpty() || !params["text"]?.trim().isNullOrEmpty()
                    val xVal = params["x"]?.trim()?.toFloatOrNull()
                    val yVal = params["y"]?.trim()?.toFloatOrNull()
                    val normX = params["norm_x"]?.trim()?.toFloatOrNull()
                    val normY = params["norm_y"]?.trim()?.toFloatOrNull()
                    val hasPixelCoords = xVal != null && yVal != null && xVal >= 0f && yVal >= 0f
                    val hasNormCoords = normX != null && normY != null && normX in 0f..1f && normY in 0f..1f
                    if ((params.containsKey("norm_x") || params.containsKey("norm_y")) && !hasNormCoords) {
                        ToolValidationResult.invalid(
                            "INVALID_COORDINATES",
                            "norm_x and norm_y must both be numbers between 0.0 and 1.0.",
                            "ট্যাপের অবস্থান ০.০ থেকে ১.০-এর মধ্যে হতে হবে।"
                        )
                    } else if (!hasElementId && !hasText && !hasPixelCoords && !hasNormCoords) {
                        ToolValidationResult.invalid(
                            "INVALID_ARGUMENTS",
                            "click_element requires element_id, target_text, pixel x/y, or normalized norm_x/norm_y.",
                            "কোন বাটনে ক্লিক করতে হবে তা পাওয়া যায়নি।"
                        )
                    } else {
                        ToolValidationResult.VALID
                    }
                }
            )
        )

        registerTool(
            RegisteredTool(
                actionType = AgentActionType.LONG_PRESS,
                description = "Performs a long press on an accessible UI element using grounded element_id + observation_id, target_text, or validated coordinates.",
                parametersSchema = BidiSchema(
                    type = "OBJECT",
                    properties = mapOf(
                        "element_id" to BidiSchema(type = "STRING", description = "Preferred stable element ID from latest get_screen_state."),
                        "observation_id" to BidiSchema(type = "STRING", description = "Observation ID returned by get_screen_state."),
                        "target_text" to BidiSchema(type = "STRING", description = "Visible label of the UI element to long press."),
                        "x" to BidiSchema(type = "STRING", description = "Optional pixel X fallback."),
                        "y" to BidiSchema(type = "STRING", description = "Optional pixel Y fallback."),
                        "norm_x" to BidiSchema(type = "STRING", description = "Optional normalized X (0.0-1.0) fallback."),
                        "norm_y" to BidiSchema(type = "STRING", description = "Optional normalized Y (0.0-1.0) fallback.")
                    )
                ),
                requiredPermissions = setOf(RequiredPermission.ACCESSIBILITY_SERVICE),
                timeoutMs = 8_000L,
                supportsRetry = true,
                validator = { params ->
                    val hasElementId = !params["element_id"]?.trim().isNullOrEmpty()
                    val hasText = !params["target_text"]?.trim().isNullOrEmpty()
                    val xVal = params["x"]?.trim()?.toFloatOrNull()
                    val yVal = params["y"]?.trim()?.toFloatOrNull()
                    val normX = params["norm_x"]?.trim()?.toFloatOrNull()
                    val normY = params["norm_y"]?.trim()?.toFloatOrNull()
                    val hasCoords = (xVal != null && yVal != null && xVal >= 0f && yVal >= 0f) ||
                        (normX != null && normY != null && normX in 0f..1f && normY in 0f..1f)
                    if (!hasElementId && !hasText && !hasCoords) {
                        ToolValidationResult.invalid(
                            "INVALID_ARGUMENTS",
                            "long_press_element requires element_id, target_text, or valid coordinates.",
                            "কোন আইটেমে চেপে ধরতে হবে তা পাওয়া যায়নি।"
                        )
                    } else {
                        ToolValidationResult.VALID
                    }
                }
            )
        )

        registerTool(
            RegisteredTool(
                actionType = AgentActionType.SCROLL,
                description = "Scrolls the currently visible scrollable list or page up or down.",
                parametersSchema = BidiSchema(
                    type = "OBJECT",
                    properties = mapOf(
                        "direction" to BidiSchema(
                            type = "STRING",
                            description = "Scroll direction: 'down' or 'up'.",
                            enum = listOf("down", "up")
                        )
                    ),
                    required = listOf("direction")
                ),
                requiredPermissions = setOf(RequiredPermission.ACCESSIBILITY_SERVICE),
                timeoutMs = 6_000L,
                supportsRetry = true
            )
        )

        registerTool(
            RegisteredTool(
                actionType = AgentActionType.SWIPE,
                description = "Performs a directional touch swipe gesture (up, down, left, right) on the active screen via AccessibilityService gesture dispatch.",
                parametersSchema = BidiSchema(
                    type = "OBJECT",
                    properties = mapOf(
                        "direction" to BidiSchema(
                            type = "STRING",
                            description = "Swipe direction: 'up', 'down', 'left', or 'right'.",
                            enum = listOf("up", "down", "left", "right")
                        )
                    ),
                    required = listOf("direction")
                ),
                requiredPermissions = setOf(RequiredPermission.ACCESSIBILITY_SERVICE),
                timeoutMs = 6_000L,
                supportsRetry = true,
                validator = { params ->
                    val dir = params["direction"]?.trim()?.lowercase().orEmpty()
                    if (dir !in setOf("up", "down", "left", "right")) {
                        ToolValidationResult.invalid(
                            "INVALID_ARGUMENTS",
                            "Swipe direction must be up, down, left, or right.",
                            "সোয়াইপের দিক সঠিক নয়।"
                        )
                    } else {
                        ToolValidationResult.VALID
                    }
                }
            )
        )

        registerTool(
            RegisteredTool(
                actionType = AgentActionType.TYPE_TEXT,
                description = "Types text into a visible non-password text field (by grounded element_id + observation_id, field_hint, or focused field) and optionally submits it.",
                parametersSchema = BidiSchema(
                    type = "OBJECT",
                    properties = mapOf(
                        "text" to BidiSchema(type = "STRING", description = "The text to enter."),
                        "element_id" to BidiSchema(type = "STRING", description = "Optional grounded element_id of the editable text field."),
                        "observation_id" to BidiSchema(type = "STRING", description = "Optional observation_id from get_screen_state."),
                        "field_hint" to BidiSchema(type = "STRING", description = "Optional hint or label of the target text field."),
                        "submit" to BidiSchema(type = "STRING", description = "Set to 'true' to press IME Enter/Search after typing.")
                    ),
                    required = listOf("text")
                ),
                requiredPermissions = setOf(RequiredPermission.ACCESSIBILITY_SERVICE),
                timeoutMs = 8_000L,
                supportsRetry = true
            )
        )

        registerTool(
            RegisteredTool(
                actionType = AgentActionType.NAVIGATE_BACK,
                description = "Navigates back to the previous Android screen using AccessibilityService.",
                parametersSchema = BidiSchema(type = "OBJECT", properties = emptyMap()),
                requiredPermissions = setOf(RequiredPermission.ACCESSIBILITY_SERVICE),
                timeoutMs = 5_000L
            )
        )

        registerTool(
            RegisteredTool(
                actionType = AgentActionType.NAVIGATE_HOME,
                description = "Returns to the Android Home screen.",
                parametersSchema = BidiSchema(type = "OBJECT", properties = emptyMap()),
                requiredPermissions = setOf(RequiredPermission.ACCESSIBILITY_SERVICE),
                timeoutMs = 5_000L
            )
        )

        // --- 5. Sensitive Communication Tools (Group B — Always Requires Confirmation) ---
        registerTool(
            RegisteredTool(
                actionType = AgentActionType.CALL_TRUSTED_CONTACT,
                description = "Looks up a trusted family contact by name, Bangla nickname (e.g., ছেলে, বড় ভাই, স্ত্রী), or relationship and requests user confirmation before opening the Phone Dialer (ACTION_DIAL).",
                parametersSchema = BidiSchema(
                    type = "OBJECT",
                    properties = mapOf(
                        "contact_query" to BidiSchema(type = "STRING", description = "Contact name or Bangla relationship/nickname.")
                    ),
                    required = listOf("contact_query")
                ),
                requiredPermissions = setOf(RequiredPermission.CONTACTS_OPTIONAL),
                timeoutMs = 8_000L
            )
        )

        registerTool(
            RegisteredTool(
                actionType = AgentActionType.OPEN_DIALER,
                description = "Requests user confirmation and opens the Android Phone Dialer (ACTION_DIAL) pre-filled with the specified phone number.",
                parametersSchema = BidiSchema(
                    type = "OBJECT",
                    properties = mapOf(
                        "phone_number" to BidiSchema(type = "STRING", description = "Phone number to place in the dialer."),
                        "display_name" to BidiSchema(type = "STRING", description = "Optional recipient name.")
                    ),
                    required = listOf("phone_number")
                ),
                timeoutMs = 6_000L,
                validator = { params ->
                    val digits = params["phone_number"].orEmpty().count { it.isDigit() }
                    if (digits < 3) {
                        ToolValidationResult.invalid(
                            "INVALID_NUMBER",
                            "Phone number must contain at least 3 digits.",
                            "সঠিক ফোন নম্বর পাওয়া যায়নি।"
                        )
                    } else {
                        ToolValidationResult.VALID
                    }
                }
            )
        )

        registerTool(
            RegisteredTool(
                actionType = AgentActionType.PREPARE_MESSAGE,
                description = "Prepares an SMS or WhatsApp message to a trusted contact or number and asks user confirmation before opening the messaging app. Never sends silently.",
                parametersSchema = BidiSchema(
                    type = "OBJECT",
                    properties = mapOf(
                        "recipient_query" to BidiSchema(type = "STRING", description = "Trusted contact name/nickname or phone number."),
                        "message_text" to BidiSchema(type = "STRING", description = "Message body dictated by the user."),
                        "channel" to BidiSchema(type = "STRING", description = "Preferred app: 'whatsapp' or 'sms'.")
                    ),
                    required = listOf("recipient_query", "message_text")
                ),
                timeoutMs = 8_000L
            )
        )
    }
}
