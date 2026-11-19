package com.example

import com.example.agent.AgentActionRequest
import com.example.agent.AgentActionResult
import com.example.agent.AgentActionType
import com.example.agent.AgentCoordinator
import com.example.agent.AgentExecutionConfig
import com.example.agent.AgentExecutionState
import com.example.agent.AgentOutcomeEvaluator
import com.example.agent.AgentSafetyManager
import com.example.agent.AgentStepStatus
import com.example.agent.AgentTaskLogger
import com.example.agent.AgentToolRegistry
import com.example.agent.AgentToolsSchema
import com.example.agent.ScreenUiSnapshot
import com.example.data.remote.BidiFunctionCall
import com.example.data.remote.BidiFunctionResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 2 Autonomous Agent Execution Engine — Unit & Mock-Tool Integration Tests.
 *
 * Covers:
 * 1. Centralized AgentToolRegistry & JSON schema generation
 * 2. Local tool argument validation (rejecting unknown tools and invalid parameters)
 * 3. Task plan generation, parsing, and runtime adaptation (create_task_plan / update_task_plan)
 * 4. Explicit observable AgentExecutionState machine transitions
 * 5. Post-action observation & AgentOutcomeEvaluator (screen hash & package verification)
 * 6. Bounded retry & error recovery (RECOVERING state + recovery exhaustion)
 * 7. Tool timeout enforcement & max-steps infinite-loop guardrail
 * 8. Safety manager 3-tier policy enforcement (Low / Confirmation / High-Risk Block)
 * 9. Task cancellation, barge-in interruption, and Gemini toolCallCancellation
 * 10. Privacy-safe AgentTaskLogger redaction
 * 11. Full multi-step autonomous mock-tool integration loop ("Open YouTube -> Play Bengali song")
 */
class AgentExecutionEngineTest {

    private fun fnCall(id: String, name: String, vararg pairs: Pair<String, String>): BidiFunctionCall {
        return BidiFunctionCall(
            id = id,
            name = name,
            args = buildJsonObject {
                pairs.forEach { (k, v) -> put(k, JsonPrimitive(v)) }
            }
        )
    }

    private fun snapshot(pkg: String, text: String): ScreenUiSnapshot {
        return ScreenUiSnapshot(
            packageName = pkg,
            screenWidthPx = 1080,
            screenHeightPx = 2400,
            visibleTexts = listOf(text),
            clickableItems = listOf(text)
        )
    }

    private fun okResult(
        status: String = "OK",
        summaryEn: String = "Done",
        summaryBn: String = "সম্পন্ন",
        details: Map<String, String> = emptyMap()
    ) = AgentActionResult(
        success = true,
        status = status,
        summaryEn = summaryEn,
        summaryBn = summaryBn,
        details = details
    )

    private fun failResult(
        status: String,
        summaryEn: String,
        summaryBn: String = "কাজটি করা যায়নি।",
        recoverable: Boolean = false
    ) = AgentActionResult(
        success = false,
        status = status,
        summaryEn = summaryEn,
        summaryBn = summaryBn,
        recoverable = recoverable
    )

    @Test
    fun `tool registry exposes unique schemas and rejects unknown tools`() {
        val registry = AgentToolRegistry()
        val allTools = registry.listRegisteredTools()
        assertTrue("Expected at least 20 registered tools", allTools.size >= 20)

        // All names must be unique
        val uniqueNames = allTools.map { it.name }.toSet()
        assertEquals(allTools.size, uniqueNames.size)

        // Gemini Live schema export matches registry exactly
        val bidiTools = AgentToolsSchema.buildAgentTools(registry)
        val declNames = bidiTools.flatMap { it.functionDeclarations.orEmpty() }.map { it.name }
        assertEquals(uniqueNames, declNames.toSet())

        // Unknown / hallucinated tool is rejected locally
        val (unknownTool, validation) = registry.validateCall("hack_device_root", emptyMap())
        assertEquals(null, unknownTool)
        assertFalse(validation.isValid)
        assertEquals("UNSUPPORTED_ACTION", validation.errorCode)

        // Common alias resolves cleanly
        val (tapTool, tapValidation) = registry.validateCall("tap", mapOf("target_text" to "Play"))
        assertNotNull(tapTool)
        assertEquals(AgentActionType.CLICK_ELEMENT, tapTool!!.actionType)
        assertTrue(tapValidation.isValid)
    }

    @Test
    fun `tool argument validation rejects invalid parameters locally before execution`() {
        val registry = AgentToolRegistry()

        assertFalse(registry.validateCall("open_app", emptyMap()).second.isValid)
        assertFalse(registry.validateCall("click_element", emptyMap()).second.isValid)
        assertTrue(registry.validateCall("click_element", mapOf("x" to "540", "y" to "1200")).second.isValid)
        assertFalse(registry.validateCall("click_element", mapOf("x" to "-10", "y" to "200")).second.isValid)
        assertFalse(registry.validateCall("set_alarm", mapOf("hour_24" to "25", "minute" to "0")).second.isValid)
        assertFalse(registry.validateCall("set_alarm", mapOf("hour_24" to "7", "minute" to "75")).second.isValid)
        assertTrue(registry.validateCall("set_alarm", mapOf("hour_24" to "6", "minute" to "30")).second.isValid)
        assertFalse(registry.validateCall("set_volume", mapOf("direction" to "explode")).second.isValid)
        assertFalse(registry.validateCall("open_url", mapOf("url" to "javascript:alert(1)")).second.isValid)
        assertTrue(registry.validateCall("open_url", mapOf("url" to "https://www.youtube.com")).second.isValid)
        assertFalse(registry.validateCall("wait_for_ui", mapOf("timeout_ms" to "99999")).second.isValid)
        assertFalse(registry.validateCall("create_task_plan", mapOf("task_description" to "Test")).second.isValid)
    }

    @Test
    fun `plan parsing and runtime plan adaptation preserve completed steps`() = runBlocking {
        val parsed = AgentCoordinator.parsePlanSteps(
            rawSteps = "open_app:YouTube | search_in_app:Bangla music | click_element:Play | verify_screen:Pause",
            expectedOutcome = "Video is playing"
        )
        assertEquals(4, parsed.size)
        assertEquals("open_app", parsed[0].toolName)
        assertEquals("search_in_app", parsed[1].toolName)
        assertEquals("click_element", parsed[2].toolName)
        assertEquals("verify_screen", parsed[3].toolName)
        assertEquals("Video is playing", parsed.last().expectedOutcome)

        val registry = AgentToolRegistry(defaultActionExecutor = { req, _ ->
            okResult(summaryEn = "Done ${req.actionType.toolName}")
        })
        val coordinator = AgentCoordinator(
            geminiRepository = null,
            actionExecutor = null,
            settingsRepository = null,
            voiceFeedback = null,
            toolRegistry = registry,
            screenSnapshotProvider = { snapshot("com.google.android.youtube", "YouTube Home") },
            activePackageProvider = { "com.google.android.youtube" },
            toolResponseSender = {},
            dispatcher = Dispatchers.Unconfined
        )

        coordinator.onUserRequestReceived("Play Bengali music on YouTube")
        assertEquals(AgentExecutionState.UNDERSTANDING, coordinator.executionState.value)

        coordinator.processToolCallsSuspending(
            listOf(
                fnCall(
                    "p1",
                    "create_task_plan",
                    "task_description" to "Play Bengali music on YouTube",
                    "steps" to "open_app:YouTube | search_in_app:Bengali music"
                )
            )
        )
        assertEquals(AgentExecutionState.PLANNING, coordinator.executionState.value)
        assertEquals(2, coordinator.activePlan.value?.steps?.size)

        // Execute step 1
        coordinator.processToolCallsSuspending(
            listOf(fnCall("s1", "open_app", "app_name" to "YouTube"))
        )
        assertEquals(1, coordinator.activePlan.value?.completedStepsCount)

        // Dynamically adapt remaining plan
        coordinator.processToolCallsSuspending(
            listOf(
                fnCall(
                    "p2",
                    "update_task_plan",
                    "reason" to "Search button visible instead of direct search",
                    "remaining_steps" to "click_element:Search | type_text:Bengali music | click_element:Play"
                )
            )
        )
        val adapted = coordinator.activePlan.value
        assertNotNull(adapted)
        assertEquals(4, adapted!!.steps.size)
        assertEquals(AgentStepStatus.COMPLETED, adapted.steps[0].status)
        assertEquals("click_element", adapted.steps[1].toolName)
    }

    @Test
    fun `retry handling recovers from transient failure and stops after maxRetriesPerAction`() = runBlocking {
        var clickAttempts = 0
        var currentSnap = snapshot("com.google.android.youtube", "Loading feed")

        val registry = AgentToolRegistry(defaultActionExecutor = { req, _ ->
            if (req.actionType == AgentActionType.CLICK_ELEMENT) {
                clickAttempts++
                if (clickAttempts == 1) {
                    failResult(
                        status = "ELEMENT_NOT_FOUND",
                        summaryEn = "Play button not visible yet.",
                        recoverable = true
                    )
                } else {
                    currentSnap = snapshot("com.google.android.youtube", "Playing Bengali Song • Pause")
                    okResult(status = "CLICKED", summaryEn = "Tapped Play button.")
                }
            } else {
                okResult()
            }
        })

        val logger = AgentTaskLogger()
        val coordinator = AgentCoordinator(
            geminiRepository = null,
            actionExecutor = null,
            settingsRepository = null,
            voiceFeedback = null,
            toolRegistry = registry,
            taskLogger = logger,
            config = AgentExecutionConfig(maxRetriesPerAction = 2, recoveryBackoffMs = 5L),
            screenSnapshotProvider = { currentSnap },
            activePackageProvider = { currentSnap.packageName },
            toolResponseSender = {},
            dispatcher = Dispatchers.Unconfined
        )

        val responses = coordinator.processToolCallsSuspending(
            listOf(fnCall("c1", "click_element", "target_text" to "Play"))
        )
        assertEquals(2, clickAttempts)
        assertEquals("true", responses.first().response["success"]?.jsonPrimitive?.content)
        assertTrue(logger.recentEntries.value.any { it.type == AgentTaskLogger.LogEventType.RETRY })
        assertTrue(coordinator.taskSteps.value.any { it.retryCount == 1 && it.status == AgentStepStatus.COMPLETED })

        // Verify recovery exhaustion stops cleanly after maxRetriesPerAction (1 initial + 2 retries = 3)
        var failingAttempts = 0
        val alwaysFailRegistry = AgentToolRegistry(defaultActionExecutor = { _, _ ->
            failingAttempts++
            failResult(
                status = "ELEMENT_NOT_FOUND",
                summaryEn = "Missing button",
                recoverable = true
            )
        })
        val failingCoordinator = AgentCoordinator(
            geminiRepository = null,
            actionExecutor = null,
            settingsRepository = null,
            voiceFeedback = null,
            toolRegistry = alwaysFailRegistry,
            config = AgentExecutionConfig(maxRetriesPerAction = 2, recoveryBackoffMs = 5L),
            screenSnapshotProvider = { currentSnap },
            activePackageProvider = { currentSnap.packageName },
            toolResponseSender = {},
            dispatcher = Dispatchers.Unconfined
        )
        val failResponses = failingCoordinator.processToolCallsSuspending(
            listOf(fnCall("c2", "click_element", "target_text" to "Nonexistent"))
        )
        assertEquals(3, failingAttempts)
        assertEquals("false", failResponses.first().response["success"]?.jsonPrimitive?.content)
    }

    @Test
    fun `tool timeout guardrail returns ACTION_TIMEOUT without hanging`() = runBlocking {
        val slowRegistry = AgentToolRegistry(defaultActionExecutor = { _, _ ->
            delay(500L)
            okResult(summaryEn = "Too slow")
        })
        val coordinator = AgentCoordinator(
            geminiRepository = null,
            actionExecutor = null,
            settingsRepository = null,
            voiceFeedback = null,
            toolRegistry = slowRegistry,
            config = AgentExecutionConfig(
                defaultToolTimeoutMs = 40L,
                maxRetriesPerAction = 0,
                recoveryBackoffMs = 5L
            ),
            screenSnapshotProvider = { null },
            activePackageProvider = { "" },
            toolResponseSender = {},
            dispatcher = Dispatchers.Unconfined
        )

        val responses = coordinator.processToolCallsSuspending(
            listOf(fnCall("t1", "scroll_screen", "direction" to "down"))
        )
        assertEquals("false", responses.first().response["success"]?.jsonPrimitive?.content)
        assertEquals("ACTION_TIMEOUT", responses.first().response["status"]?.jsonPrimitive?.content)
    }

    @Test
    fun `max steps guardrail prevents infinite autonomous execution loops`() = runBlocking {
        val registry = AgentToolRegistry(defaultActionExecutor = { _, _ ->
            okResult(status = "SCROLLED", summaryEn = "Scrolled")
        })
        val coordinator = AgentCoordinator(
            geminiRepository = null,
            actionExecutor = null,
            settingsRepository = null,
            voiceFeedback = null,
            toolRegistry = registry,
            config = AgentExecutionConfig(maxStepsPerTask = 2, maxRetriesPerAction = 0),
            screenSnapshotProvider = { snapshot("com.app", "Feed") },
            activePackageProvider = { "com.app" },
            toolResponseSender = {},
            dispatcher = Dispatchers.Unconfined
        )

        coordinator.onUserRequestReceived("Keep scrolling forever")
        coordinator.processToolCallsSuspending(listOf(fnCall("1", "scroll_screen", "direction" to "down")))
        coordinator.processToolCallsSuspending(listOf(fnCall("2", "scroll_screen", "direction" to "down")))
        val third = coordinator.processToolCallsSuspending(listOf(fnCall("3", "scroll_screen", "direction" to "down")))

        assertEquals("false", third.first().response["success"]?.jsonPrimitive?.content)
        assertEquals("MAX_STEPS_EXCEEDED", third.first().response["status"]?.jsonPrimitive?.content)
        assertEquals(AgentExecutionState.FAILED, coordinator.executionState.value)
    }

    @Test
    fun `safety manager enforces high-risk block and confirmation gate for sensitive actions`() = runBlocking {
        // High-risk financial action blocked
        val paymentDecision = AgentSafetyManager.evaluate(
            AgentActionRequest(
                actionType = AgentActionType.CLICK_ELEMENT,
                parameters = mapOf("target_text" to "Send Money bKash Confirm")
            ),
            activePackageName = "com.bKash.customerapp"
        )
        assertTrue(paymentDecision.isBlocked)

        // Sensitive communication action pauses in WAITING_FOR_USER and executes only after user approves
        var dialExecuted = false
        val registry = AgentToolRegistry(defaultActionExecutor = { _, confirmed ->
            dialExecuted = confirmed
            if (confirmed) {
                okResult(status = "DIALER_OPENED", summaryEn = "Opened dialer")
            } else {
                failResult(status = "UNCONFIRMED", summaryEn = "Not confirmed")
            }
        })
        val coordinator = AgentCoordinator(
            geminiRepository = null,
            actionExecutor = null,
            settingsRepository = null,
            voiceFeedback = null,
            toolRegistry = registry,
            config = AgentExecutionConfig(confirmationTimeoutMs = 5_000L),
            screenSnapshotProvider = { null },
            activePackageProvider = { "" },
            toolResponseSender = {},
            dispatcher = Dispatchers.Default
        )

        val deferredResp = async {
            coordinator.processToolCallsSuspending(
                listOf(fnCall("call1", "open_dialer", "phone_number" to "+8801711000111"))
            )
        }

        // Wait until confirmation prompt is active
        var waited = 0
        while (coordinator.pendingConfirmation.value == null && waited < 40) {
            delay(25L)
            waited++
        }
        assertNotNull(coordinator.pendingConfirmation.value)
        assertEquals(AgentExecutionState.WAITING_FOR_USER, coordinator.executionState.value)

        coordinator.approvePendingConfirmation()
        val resp = deferredResp.await()
        assertTrue(dialExecuted)
        assertEquals("true", resp.first().response["success"]?.jsonPrimitive?.content)
    }

    @Test
    fun `barge-in interruption and toolCallCancellation stop active tasks cleanly`() = runBlocking {
        val registry = AgentToolRegistry(defaultActionExecutor = { _, _ -> okResult() })
        val coordinator = AgentCoordinator(
            geminiRepository = null,
            actionExecutor = null,
            settingsRepository = null,
            voiceFeedback = null,
            toolRegistry = registry,
            config = AgentExecutionConfig(confirmationTimeoutMs = 10_000L),
            screenSnapshotProvider = { null },
            activePackageProvider = { "" },
            toolResponseSender = {},
            dispatcher = Dispatchers.Default
        )

        val taskJob = async {
            coordinator.processToolCallsSuspending(
                listOf(fnCall("msg1", "prepare_message", "recipient_query" to "Son", "message_text" to "Hello"))
            )
        }

        var waited = 0
        while (coordinator.pendingConfirmation.value == null && waited < 40) {
            delay(25L)
            waited++
        }
        assertNotNull(coordinator.pendingConfirmation.value)

        // Simulate user voice barge-in interruption
        coordinator.onUserInterruption()
        val responses = taskJob.await()
        assertEquals("USER_CANCELLED", responses.first().response["status"]?.jsonPrimitive?.content)
        assertEquals(AgentExecutionState.CANCELLED, coordinator.executionState.value)
        assertFalse(coordinator.isTaskRunning.value)
        assertEquals(null, coordinator.pendingConfirmation.value)

        // Simulate Gemini toolCallCancellation for a future call ID
        coordinator.onGeminiToolCallsCancelled(listOf("cancelled-99"))
        val cancelledResp = coordinator.processToolCallsSuspending(
            listOf(fnCall("cancelled-99", "get_date_time"))
        )
        assertEquals("CANCELLED", cancelledResp.first().response["status"]?.jsonPrimitive?.content)
    }

    @Test
    fun `outcome evaluator detects foreground app mismatch and screen transitions`() {
        val beforeHome = snapshot("com.android.launcher", "Home")
        val afterWrongApp = snapshot("com.android.settings", "Wi-Fi Settings")
        val afterYouTube = snapshot("com.google.android.youtube", "YouTube Home")

        val openReq = AgentActionRequest(
            actionType = AgentActionType.OPEN_APP,
            parameters = mapOf("app_name" to "YouTube")
        )
        val rawOk = okResult(
            status = "APP_OPENED",
            summaryEn = "Opened YouTube.",
            details = mapOf("package" to "com.google.android.youtube")
        )

        val mismatchEval = AgentOutcomeEvaluator.evaluate(openReq, rawOk, beforeHome, afterWrongApp)
        assertFalse("Foreground package mismatch must fail verification", mismatchEval.verifiedSuccess)
        assertTrue(mismatchEval.shouldRetry)
        assertTrue(mismatchEval.recoveryHint.isNotBlank())

        val verifiedEval = AgentOutcomeEvaluator.evaluate(openReq, rawOk, beforeHome, afterYouTube)
        assertTrue(verifiedEval.verifiedSuccess)
        assertTrue(verifiedEval.screenChanged)
    }

    @Test
    fun `task logger scrubs API keys, OTPs, and private message bodies`() {
        val syntheticKey = "AIza" + "SyDummyKeyExample1234567890abcdefgh"
        val scrubbed = AgentTaskLogger.sanitizeForLog("Key=$syntheticKey OTP is 482910 password=mysecret")
        assertFalse(scrubbed.contains(syntheticKey))
        assertFalse(scrubbed.contains("482910"))
        assertFalse(scrubbed.contains("mysecret"))
        assertTrue(scrubbed.contains("[REDACTED_API_KEY]"))
        assertTrue(scrubbed.contains("[REDACTED"))

        val sanitizedParams = AgentTaskLogger.sanitizeParams(
            mapOf("recipient_query" to "Father", "message_text" to "My secret code is 998877")
        )
        assertEquals("Father", sanitizedParams["recipient_query"])
        assertEquals("[REDACTED]", sanitizedParams["message_text"])
    }

    @Test
    fun `end-to-end mock-tool autonomous multi-step loop completes YouTube playback workflow`() = runBlocking {
        var currentSnap = snapshot("com.android.launcher", "Home")
        val sentResponses = mutableListOf<BidiFunctionResponse>()

        val registry = AgentToolRegistry(defaultActionExecutor = { req, _ ->
            when (req.actionType) {
                AgentActionType.OPEN_APP -> {
                    currentSnap = snapshot("com.google.android.youtube", "YouTube Home • Search")
                    okResult(
                        status = "APP_OPENED",
                        summaryEn = "Opened YouTube.",
                        summaryBn = "YouTube খুলেছি।",
                        details = mapOf("package" to "com.google.android.youtube")
                    )
                }
                AgentActionType.SEARCH_TEXT -> {
                    currentSnap = snapshot("com.google.android.youtube", "Bengali Song Official Video • Play")
                    okResult(
                        status = "SEARCH_OPENED",
                        summaryEn = "Searched for Bengali song in YouTube.",
                        summaryBn = "YouTube-এ বাংলা গান খুঁজেছি।"
                    )
                }
                AgentActionType.CLICK_ELEMENT -> {
                    currentSnap = snapshot("com.google.android.youtube", "Now Playing • Pause")
                    okResult(
                        status = "CLICKED",
                        summaryEn = "Tapped Bengali Song video.",
                        summaryBn = "ভিডিও চালু করেছি।"
                    )
                }
                AgentActionType.VERIFY_SCREEN -> {
                    val found = currentSnap.visibleTexts.any {
                        it.contains(req.parameters["expected_text"].orEmpty(), ignoreCase = true)
                    }
                    if (found) {
                        okResult(status = "VERIFIED", summaryEn = "Playback verified.")
                    } else {
                        failResult(status = "NOT_FOUND", summaryEn = "Not found.")
                    }
                }
                else -> okResult()
            }
        })

        val logger = AgentTaskLogger()
        val coordinator = AgentCoordinator(
            geminiRepository = null,
            actionExecutor = null,
            settingsRepository = null,
            voiceFeedback = null,
            toolRegistry = registry,
            taskLogger = logger,
            screenSnapshotProvider = { currentSnap },
            activePackageProvider = { currentSnap.packageName },
            toolResponseSender = { sentResponses.addAll(it) },
            dispatcher = Dispatchers.Unconfined
        )

        // Step 0: User speaks request
        coordinator.onUserRequestReceived("Open YouTube and play a Bengali song")
        assertEquals(AgentExecutionState.UNDERSTANDING, coordinator.executionState.value)

        // Step 1: Gemini creates plan
        coordinator.processToolCallsSuspending(
            listOf(
                fnCall(
                    "plan1",
                    "create_task_plan",
                    "task_description" to "Open YouTube and play a Bengali song",
                    "steps" to "open_app:YouTube | search_in_app:Bengali song | click_element:Play | verify_screen:Pause",
                    "expected_outcome" to "Pause"
                )
            )
        )
        assertEquals(AgentExecutionState.PLANNING, coordinator.executionState.value)

        // Step 2: Open YouTube
        coordinator.processToolCallsSuspending(listOf(fnCall("s1", "open_app", "app_name" to "YouTube")))
        assertEquals(AgentExecutionState.WAITING_FOR_ACTION, coordinator.executionState.value)

        // Step 3: Search Bengali song
        coordinator.processToolCallsSuspending(
            listOf(fnCall("s2", "search_in_app", "query" to "Bengali song", "app_name" to "YouTube"))
        )

        // Step 4: Tap video
        coordinator.processToolCallsSuspending(listOf(fnCall("s3", "click_element", "target_text" to "Play")))

        // Step 5: Verify playback
        coordinator.processToolCallsSuspending(listOf(fnCall("s4", "verify_screen", "expected_text" to "Pause")))

        // Step 6: Complete task
        coordinator.processToolCallsSuspending(
            listOf(
                fnCall(
                    "done1",
                    "complete_task",
                    "status" to "completed",
                    "summary_en" to "Playing a Bengali song on YouTube.",
                    "summary_bn" to "YouTube-এ বাংলা গান চালু করে দিয়েছি।"
                )
            )
        )

        assertEquals(AgentExecutionState.COMPLETED, coordinator.executionState.value)
        assertEquals(4, coordinator.activePlan.value?.completedStepsCount)
        assertEquals(4, coordinator.activePlan.value?.totalToolStepsExecuted)
        assertEquals(6, sentResponses.size)
        assertTrue(sentResponses.all { it.response["success"]?.jsonPrimitive?.content == "true" })
        assertEquals(AgentExecutionState.COMPLETED, logger.records.value.last().finalState)
    }
}
