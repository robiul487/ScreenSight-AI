package com.example

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.agent.AgentActionRequest
import com.example.agent.AgentActionResult
import com.example.agent.AgentActionType
import com.example.agent.AgentCoordinator
import com.example.agent.AgentExecutionConfig
import com.example.agent.AgentExecutionState
import com.example.agent.AgentPlanStep
import com.example.agent.AgentStepStatus
import com.example.agent.AgentTaskPlan
import com.example.agent.AgentToolRegistry
import com.example.agent.ElementInteractionValidator
import com.example.agent.GroundedUiElement
import com.example.agent.PhysicalDeviceTestHarness
import com.example.agent.ScreenUiSnapshot
import com.example.agent.UiBounds
import com.example.agent.VoiceConfirmationParser
import com.example.data.local.checkpoint.AgentCheckpointDatabase
import com.example.data.local.checkpoint.AgentTaskCheckpointRepository
import com.example.data.remote.BidiFunctionCall
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Phase 3 Reliability, Grounding, Voice Confirmation, Room Persistence & Harness Test Suite.
 *
 * NOTE: Runs on JVM + Robolectric with real in-memory Room SQLite. Physical-device Accessibility
 * interactions and live OEM app launches still require manual verification on an actual Android device.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Phase3ReliabilityTest {

    private lateinit var db: AgentCheckpointDatabase
    private lateinit var checkpointRepo: AgentTaskCheckpointRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(context, AgentCheckpointDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        checkpointRepo = AgentTaskCheckpointRepository(db.checkpointDao())
    }

    @After
    fun tearDown() {
        db.close()
    }

    // 1. Exact Verified Tool Count Resolution (24/25 discrepancy resolved from source)
    @Test
    fun verifiedToolRegistryCount_isExactly25() {
        val registry = AgentToolRegistry()
        val registered = registry.listRegisteredTools()
        val geminiDeclarations = registry.toGeminiLiveTools().first().functionDeclarations.orEmpty()
        assertEquals(25, registered.size)
        assertEquals(25, geminiDeclarations.size)
    }

    // 2. Grounded UI Bounds & Coordinate Validation
    @Test
    fun boundsValidation_acceptsValidAndRejectsOutOfRangeOrInverted() {
        val valid = UiBounds.fromPixels(100, 200, 400, 320, 1080, 2400)
        assertTrue(ElementInteractionValidator.validateBounds(valid, 1080, 2400))
        assertEquals(250f, valid.centerX, 0.01f)
        assertEquals(260f, valid.centerY, 0.01f)
        assertTrue(valid.normX1 in 0f..1f && valid.normY2 in 0f..1f)

        // Out-of-bounds pixels
        val outOfBounds = UiBounds(900, 200, 1200, 320, 0.8f, 0.1f, 1.0f, 0.2f)
        assertFalse(ElementInteractionValidator.validateBounds(outOfBounds, 1080, 2400))

        // Inverted box (x2 <= x1)
        val inverted = UiBounds(400, 200, 400, 320, 0.3f, 0.1f, 0.3f, 0.2f)
        assertFalse(ElementInteractionValidator.validateBounds(inverted, 1080, 2400))

        // Zero screen dimensions
        assertFalse(ElementInteractionValidator.validateBounds(valid, 0, 2400))
    }

    @Test
    fun coordinateValidation_handlesPixelNormalizedAndBoundsErrors() {
        val now = 100_000L
        val snap = sampleSnapshot(obsId = "obs_1", timestampMs = now - 1000L)

        // Valid normalized coordinates (0.5, 0.5) -> (540, 1200)
        val normOk = ElementInteractionValidator.validateCoordinateTarget(
            x = null, y = null, normX = 0.5f, normY = 0.5f,
            requestedObservationId = "obs_1", latestSnapshot = snap,
            screenWidthPx = 1080, screenHeightPx = 2400, nowMillis = now
        )
        assertTrue(normOk is ElementInteractionValidator.ValidationOutcome.ValidCoordinate)
        val coord = normOk as ElementInteractionValidator.ValidationOutcome.ValidCoordinate
        assertEquals(540f, coord.tapX, 1f)
        assertEquals(1200f, coord.tapY, 1f)
        assertTrue(coord.isNormalizedFallback)

        // Out-of-range pixel coordinate rejected
        val pxBad = ElementInteractionValidator.validateCoordinateTarget(
            x = 1500f, y = 400f, normX = null, normY = null,
            requestedObservationId = "obs_1", latestSnapshot = snap,
            screenWidthPx = 1080, screenHeightPx = 2400, nowMillis = now
        )
        assertTrue(pxBad is ElementInteractionValidator.ValidationOutcome.Rejected)
        assertEquals("INVALID_COORDINATES", (pxBad as ElementInteractionValidator.ValidationOutcome.Rejected).errorCode)

        // Out-of-range normalized coordinate rejected
        val normBad = ElementInteractionValidator.validateCoordinateTarget(
            x = null, y = null, normX = 1.25f, normY = 0.5f,
            requestedObservationId = "obs_1", latestSnapshot = snap,
            screenWidthPx = 1080, screenHeightPx = 2400, nowMillis = now
        )
        assertTrue(normBad is ElementInteractionValidator.ValidationOutcome.Rejected)
        assertEquals("INVALID_COORDINATES", (normBad as ElementInteractionValidator.ValidationOutcome.Rejected).errorCode)
    }

    // 3. Stale Observation & Missing/Disabled Element Reference Rejection
    @Test
    fun staleObservationAndInvalidElementReferences_areRejectedCleanly() {
        val now = 200_000L
        val freshSnap = sampleSnapshot(obsId = "obs_fresh", timestampMs = now - 2_000L)

        // Mismatched observation_id -> STALE_OBSERVATION
        val mismatch = ElementInteractionValidator.validateElementTarget(
            elementId = "el_0",
            requestedObservationId = "obs_old",
            latestSnapshot = freshSnap,
            nowMillis = now
        )
        assertTrue(mismatch is ElementInteractionValidator.ValidationOutcome.Rejected)
        assertEquals("STALE_OBSERVATION", (mismatch as ElementInteractionValidator.ValidationOutcome.Rejected).errorCode)

        // Expired timestamp -> STALE_OBSERVATION
        val expiredSnap = sampleSnapshot(obsId = "obs_expired", timestampMs = now - 60_000L)
        val expired = ElementInteractionValidator.validateElementTarget(
            elementId = "el_0",
            requestedObservationId = "obs_expired",
            latestSnapshot = expiredSnap,
            nowMillis = now
        )
        assertTrue(expired is ElementInteractionValidator.ValidationOutcome.Rejected)
        assertEquals("STALE_OBSERVATION", (expired as ElementInteractionValidator.ValidationOutcome.Rejected).errorCode)

        // Missing element_id -> ELEMENT_ID_NOT_FOUND
        val missing = ElementInteractionValidator.validateElementTarget(
            elementId = "el_99",
            requestedObservationId = "obs_fresh",
            latestSnapshot = freshSnap,
            nowMillis = now
        )
        assertTrue(missing is ElementInteractionValidator.ValidationOutcome.Rejected)
        assertEquals("ELEMENT_ID_NOT_FOUND", (missing as ElementInteractionValidator.ValidationOutcome.Rejected).errorCode)

        // Disabled element -> ELEMENT_DISABLED
        val disabled = ElementInteractionValidator.validateElementTarget(
            elementId = "el_1",
            requestedObservationId = "obs_fresh",
            latestSnapshot = freshSnap,
            nowMillis = now
        )
        assertTrue(disabled is ElementInteractionValidator.ValidationOutcome.Rejected)
        assertEquals("ELEMENT_DISABLED", (disabled as ElementInteractionValidator.ValidationOutcome.Rejected).errorCode)

        // Valid enabled element -> ValidElement with exact center
        val ok = ElementInteractionValidator.validateElementTarget(
            elementId = "el_0",
            requestedObservationId = "obs_fresh",
            latestSnapshot = freshSnap,
            nowMillis = now
        )
        assertTrue(ok is ElementInteractionValidator.ValidationOutcome.ValidElement)
        val validEl = ok as ElementInteractionValidator.ValidationOutcome.ValidElement
        assertEquals("Search", validEl.element.bestLabel)
    }

    // 4. Bengali & English Voice Confirmation Classification & Ambiguity Rejection
    @Test
    fun voiceConfirmationParser_classifiesBanglaEnglishAmbiguousAndNewCommands() {
        val approvals = listOf("হ্যাঁ", "ঠিক আছে", "করো", "হ্যাঁ করুন", "Yes", "Confirm", "Okay", "Go ahead")
        approvals.forEach { phrase ->
            assertEquals(
                "Expected APPROVE for \"$phrase\"",
                VoiceConfirmationParser.ConfirmationIntent.APPROVE,
                VoiceConfirmationParser.classify(phrase)
            )
        }

        val rejections = listOf("না", "বাতিল", "বাতিল করো", "থামো", "No", "Cancel", "Stop", "Do not")
        rejections.forEach { phrase ->
            assertEquals(
                "Expected REJECT for \"$phrase\"",
                VoiceConfirmationParser.ConfirmationIntent.REJECT,
                VoiceConfirmationParser.classify(phrase)
            )
        }

        val ambiguous = listOf("হ্যাঁ না", "হয়তো", "দাঁড়াও", "জানি না", "yes no", "maybe", "wait", "not sure", "hmm", "")
        ambiguous.forEach { phrase ->
            assertEquals(
                "Expected AMBIGUOUS for \"$phrase\"",
                VoiceConfirmationParser.ConfirmationIntent.AMBIGUOUS,
                VoiceConfirmationParser.classify(phrase)
            )
        }

        assertEquals(
            VoiceConfirmationParser.ConfirmationIntent.NEW_INSTRUCTION,
            VoiceConfirmationParser.classify("Open YouTube and play music")
        )
        assertEquals(
            VoiceConfirmationParser.ConfirmationIntent.NEW_INSTRUCTION,
            VoiceConfirmationParser.classify("ইউটিউব খোলো")
        )
    }

    // 5. Coordinator Voice Confirmation Binding, Ambiguity, Expiration & New-Instruction Cancellation
    @Test
    fun coordinatorVoiceConfirmation_bindsToActionIdRejectsAmbiguousAndHonorsBanglaYes() = runBlocking {
        var executedCall = false
        val registry = AgentToolRegistry { req, confirmed ->
            if (req.actionType == AgentActionType.CALL_TRUSTED_CONTACT) {
                executedCall = confirmed
                AgentActionResult(true, "DIALER_OPENED", "Opened dialer", "ডায়ালার খোলা হয়েছে।")
            } else {
                AgentActionResult(true, "OK", "Done", "হয়েছে")
            }
        }
        val coordinator = AgentCoordinator(
            geminiRepository = null,
            actionExecutor = null,
            settingsRepository = null,
            voiceFeedback = null,
            toolRegistry = registry,
            config = AgentExecutionConfig(confirmationTimeoutMs = 5_000L),
            screenSnapshotProvider = { sampleSnapshot("obs_a") },
            dispatcher = Dispatchers.Unconfined,
            checkpointRepository = checkpointRepo
        )

        val callDeferred = async {
            coordinator.processToolCallsSuspending(
                listOf(
                    BidiFunctionCall(
                        id = "call-voice-1",
                        name = "call_trusted_contact",
                        args = buildJsonObject { put("contact_query", JsonPrimitive("ছেলে")) }
                    )
                )
            )
        }

        // Wait briefly for confirmation prompt to become active
        var tries = 0
        while (coordinator.pendingConfirmation.value == null && tries < 40) {
            delay(15L)
            tries++
        }
        val pending = coordinator.pendingConfirmation.value
        assertNotNull(pending)
        assertEquals(AgentExecutionState.WAITING_FOR_USER, coordinator.executionState.value)

        // Wrong action ID must NOT approve
        assertFalse(coordinator.approvePendingConfirmation(expectedId = "wrong-action-id"))
        assertNotNull(coordinator.pendingConfirmation.value)

        // Ambiguous Bangla reply must NOT approve and must keep WAITING_FOR_USER
        val ambOutcome = coordinator.handleSpokenConfirmationUtterance("হয়তো দাঁড়াও")
        assertEquals(AgentCoordinator.VoiceConfirmationOutcome.AMBIGUOUS_KEPT_WAITING, ambOutcome)
        assertEquals(AgentExecutionState.WAITING_FOR_USER, coordinator.executionState.value)
        assertFalse(executedCall)

        // Clear Bangla approval "হ্যাঁ" approves exact pending action ID
        val okOutcome = coordinator.handleSpokenConfirmationUtterance("হ্যাঁ")
        assertEquals(AgentCoordinator.VoiceConfirmationOutcome.APPROVED, okOutcome)

        val responses = callDeferred.await()
        assertTrue(executedCall)
        assertEquals("true", responses.first().response["success"]?.toString())
    }

    @Test
    fun coordinatorVoiceConfirmation_newInstructionCancelsPendingConfirmation() = runBlocking {
        val registry = AgentToolRegistry { _, _ ->
            AgentActionResult(true, "OK", "Done", "হয়েছে")
        }
        val coordinator = AgentCoordinator(
            geminiRepository = null,
            actionExecutor = null,
            settingsRepository = null,
            voiceFeedback = null,
            toolRegistry = registry,
            config = AgentExecutionConfig(confirmationTimeoutMs = 5_000L),
            screenSnapshotProvider = { sampleSnapshot("obs_b") },
            dispatcher = Dispatchers.Unconfined
        )

        val callDeferred = async {
            coordinator.processToolCallsSuspending(
                listOf(
                    BidiFunctionCall(
                        id = "call-voice-2",
                        name = "prepare_message",
                        args = buildJsonObject {
                            put("recipient_query", JsonPrimitive("01711000000"))
                            put("message_text", JsonPrimitive("Hello"))
                        }
                    )
                )
            )
        }

        var tries = 0
        while (coordinator.pendingConfirmation.value == null && tries < 40) {
            delay(15L)
            tries++
        }
        assertNotNull(coordinator.pendingConfirmation.value)

        val outcome = coordinator.handleSpokenConfirmationUtterance("Open YouTube instead")
        assertEquals(AgentCoordinator.VoiceConfirmationOutcome.CANCELLED_FOR_NEW_INSTRUCTION, outcome)

        val responses = callDeferred.await()
        assertEquals("false", responses.first().response["success"]?.toString())
        assertEquals("\"USER_CANCELLED\"", responses.first().response["status"]?.toString())
    }

    @Test
    fun coordinatorVoiceConfirmation_expiredConfirmationIsRejected() = runBlocking {
        val registry = AgentToolRegistry { _, _ ->
            AgentActionResult(true, "OK", "Done", "হয়েছে")
        }
        val coordinator = AgentCoordinator(
            geminiRepository = null,
            actionExecutor = null,
            settingsRepository = null,
            voiceFeedback = null,
            toolRegistry = registry,
            config = AgentExecutionConfig(confirmationTimeoutMs = 2_000L),
            screenSnapshotProvider = { sampleSnapshot("obs_c") },
            dispatcher = Dispatchers.Unconfined
        )

        val callDeferred = async {
            coordinator.processToolCallsSuspending(
                listOf(
                    BidiFunctionCall(
                        id = "call-voice-exp",
                        name = "open_dialer",
                        args = buildJsonObject { put("phone_number", JsonPrimitive("01711223344")) }
                    )
                )
            )
        }

        var tries = 0
        while (coordinator.pendingConfirmation.value == null && tries < 40) {
            delay(15L)
            tries++
        }
        val pending = coordinator.pendingConfirmation.value!!
        // Simulate reply arriving after expiresAtMillis
        val expOutcome = coordinator.handleSpokenConfirmationUtterance(
            utterance = "Yes",
            nowMillis = pending.expiresAtMillis + 500L
        )
        assertEquals(AgentCoordinator.VoiceConfirmationOutcome.EXPIRED, expOutcome)
        val responses = callDeferred.await()
        assertEquals("false", responses.first().response["success"]?.toString())
    }

    // 6. Real Room Persistence, Privacy Scrubbing, Process Restart Recovery & Sensitive Non-Replay
    @Test
    fun roomCheckpointPersistence_scrubsSecretsAndNeverAutoReplaysSensitiveActionsOnRestart() = runBlocking {
        val syntheticKey = String(charArrayOf('A', 'I', 'z', 'a')) + "SyntheticCheckpointFixture0000000000"
        val planWithSensitiveStep = AgentTaskPlan(
            taskId = "task-restart-1",
            taskDescription = "Open YouTube then call son",
            steps = listOf(
                AgentPlanStep(
                    stepIndex = 0,
                    description = "Open YouTube",
                    toolName = "open_app",
                    status = AgentStepStatus.COMPLETED
                ),
                AgentPlanStep(
                    stepIndex = 1,
                    description = "Search relaxing Bangla song",
                    toolName = "search_in_app",
                    status = AgentStepStatus.PENDING
                ),
                AgentPlanStep(
                    stepIndex = 2,
                    description = "Call son",
                    toolName = "call_trusted_contact",
                    status = AgentStepStatus.PENDING
                )
            ),
            currentStepIndex = 1,
            totalToolStepsExecuted = 1
        )

        checkpointRepo.saveCheckpoint(
            taskId = "task-restart-1",
            rawUserRequest = "My password=supersecret otp=849201 key=$syntheticKey open youtube",
            state = AgentExecutionState.WAITING_FOR_ACTION,
            plan = planWithSensitiveStep,
            retryCount = 1,
            safeMetadata = mapOf(
                "last_tool" to "open_app",
                "message_text" to "Private secret message body",
                "password" to "topsecret123"
            ),
            lastError = "Minor error with pin=4321"
        )

        // Verify raw SQLite row contains ZERO secrets or private message bodies
        val rawEntity = db.checkpointDao().getById("task-restart-1")
        assertNotNull(rawEntity)
        assertFalse(rawEntity!!.sanitizedRequest.contains("supersecret"))
        assertFalse(rawEntity.sanitizedRequest.contains("849201"))
        assertFalse(rawEntity.sanitizedRequest.contains(syntheticKey))
        assertFalse(rawEntity.safeMetadataJson.contains("Private secret message body"))
        assertFalse(rawEntity.safeMetadataJson.contains("topsecret123"))
        assertFalse(rawEntity.sanitizedLastError.orEmpty().contains("4321"))
        assertTrue(rawEntity.containsSensitiveAction)

        // Simulate fresh app restart with a brand-new AgentCoordinator reading the same Room DB
        var anyToolAutoExecuted = false
        val restartedCoordinator = AgentCoordinator(
            geminiRepository = null,
            actionExecutor = null,
            settingsRepository = null,
            voiceFeedback = null,
            toolRegistry = AgentToolRegistry { _, _ ->
                anyToolAutoExecuted = true
                AgentActionResult(true, "OK", "Done", "হয়েছে")
            },
            dispatcher = Dispatchers.Unconfined,
            checkpointRepository = checkpointRepo
        )

        val incomplete = restartedCoordinator.inspectRecoverableCheckpoints()
        assertEquals(1, incomplete.size)
        assertFalse("Startup inspection must NEVER auto-execute tools", anyToolAutoExecuted)
        assertEquals(1, incomplete.first().remainingSafeSteps.size)
        assertEquals(1, incomplete.first().skippedSensitiveStepsCount)

        // Explicit user Resume restores ONLY the safe step and strips the sensitive call step
        val resumeOutcome = restartedCoordinator.resumeRecoverableCheckpoint("task-restart-1")
        assertTrue(resumeOutcome is AgentCoordinator.ResumeCheckpointOutcome.Resumed)
        val resumed = resumeOutcome as AgentCoordinator.ResumeCheckpointOutcome.Resumed
        assertEquals(1, resumed.skippedSensitiveCount)
        assertEquals(1, resumed.plan.steps.size)
        assertEquals("search_in_app", resumed.plan.steps.first().toolName)
        assertFalse("Resume must NOT auto-replay sensitive call", anyToolAutoExecuted)
    }

    @Test
    fun roomCheckpointRecovery_sensitiveOnlyTaskIsNeverAutoReplayed() = runBlocking {
        val sensitiveOnlyPlan = AgentTaskPlan(
            taskId = "task-sensitive-only",
            taskDescription = "Send WhatsApp message to brother",
            steps = listOf(
                AgentPlanStep(
                    stepIndex = 0,
                    description = "Prepare message",
                    toolName = "prepare_message",
                    status = AgentStepStatus.WAITING_CONFIRMATION
                )
            )
        )
        checkpointRepo.saveCheckpoint(
            taskId = "task-sensitive-only",
            rawUserRequest = "Send WhatsApp message to brother",
            state = AgentExecutionState.WAITING_FOR_USER,
            plan = sensitiveOnlyPlan,
            retryCount = 0
        )

        val restartedCoordinator = AgentCoordinator(
            geminiRepository = null,
            actionExecutor = null,
            settingsRepository = null,
            voiceFeedback = null,
            dispatcher = Dispatchers.Unconfined,
            checkpointRepository = checkpointRepo
        )
        assertEquals(1, restartedCoordinator.inspectRecoverableCheckpoints().size)

        val outcome = restartedCoordinator.resumeRecoverableCheckpoint("task-sensitive-only")
        assertTrue(outcome is AgentCoordinator.ResumeCheckpointOutcome.BlockedSensitiveOnly)
        assertTrue(restartedCoordinator.recoverableCheckpoints.value.isEmpty())
    }

    // 7. Duplicate Tool Call Protection + LastActionVerification + PhysicalDeviceTestHarness
    @Test
    fun duplicateToolCallIdIsIgnoredAndHarnessRecordsStructuredTelemetry() = runBlocking {
        var executions = 0
        val registry = AgentToolRegistry { _, _ ->
            executions++
            AgentActionResult(
                success = true,
                status = "APP_OPENED",
                summaryEn = "Opened YouTube.",
                summaryBn = "YouTube খুলে দিয়েছি।",
                details = mapOf("package_name" to "com.google.android.youtube", "interaction_tier" to "ANDROID_INTENT")
            )
        }
        val harness = PhysicalDeviceTestHarness()
        harness.setActiveScenario(PhysicalDeviceTestHarness.DeviceTestScenario.OPEN_YOUTUBE)

        var snapTick = 0
        val coordinator = AgentCoordinator(
            geminiRepository = null,
            actionExecutor = null,
            settingsRepository = null,
            voiceFeedback = null,
            toolRegistry = registry,
            screenSnapshotProvider = {
                snapTick++
                if (snapTick == 1) sampleSnapshot("obs_before", pkg = "com.android.launcher")
                else sampleSnapshot("obs_after", pkg = "com.google.android.youtube")
            },
            dispatcher = Dispatchers.Unconfined,
            testHarness = harness
        )

        val call = BidiFunctionCall(
            id = "dedup-call-42",
            name = "open_app",
            args = buildJsonObject { put("app_name", JsonPrimitive("YouTube")) }
        )

        val firstRes = coordinator.processToolCallsSuspending(listOf(call))
        val secondRes = coordinator.processToolCallsSuspending(listOf(call))

        assertEquals(1, executions)
        assertEquals("true", firstRes.first().response["success"]?.toString())
        assertEquals("false", secondRes.first().response["success"]?.toString())
        assertEquals("\"DUPLICATE_CALL_IGNORED\"", secondRes.first().response["status"]?.toString())

        val lastVerification = coordinator.lastActionVerification.value
        assertNotNull(lastVerification)
        assertTrue(lastVerification!!.verifiedSuccess)
        assertTrue(lastVerification.screenChanged)

        // Verify harness has 10 defined scenarios, recorded #1, and honestly reports #2..#10 as NOT_RUN
        assertEquals(10, PhysicalDeviceTestHarness.DeviceTestScenario.entries.size)
        val rec = harness.latestRecordFor(PhysicalDeviceTestHarness.DeviceTestScenario.OPEN_YOUTUBE)
        assertNotNull(rec)
        assertEquals(PhysicalDeviceTestHarness.HarnessRunStatus.PASSED, rec!!.status)
        assertFalse("JVM test must not falsely mark executedOnPhysicalDevice=true", rec.executedOnPhysicalDevice)
        val summary = harness.buildDiagnosticSummary()
        assertTrue(summary.contains("#2 Search for a specified video: NOT_RUN"))
    }

    private fun sampleSnapshot(
        obsId: String,
        pkg: String = "com.google.android.youtube",
        timestampMs: Long = System.currentTimeMillis()
    ): ScreenUiSnapshot {
        val el0 = GroundedUiElement(
            elementId = "el_0",
            text = "Search",
            contentDescription = "Search",
            className = "android.widget.ImageButton",
            clickable = true,
            enabled = true,
            bounds = UiBounds.fromPixels(900, 80, 1040, 200, 1080, 2400)
        )
        val el1 = GroundedUiElement(
            elementId = "el_1",
            text = "Disabled Action",
            className = "android.widget.Button",
            clickable = true,
            enabled = false,
            bounds = UiBounds.fromPixels(100, 400, 500, 520, 1080, 2400)
        )
        return ScreenUiSnapshot(
            observationId = obsId,
            packageName = pkg,
            timestampMillis = timestampMs,
            screenWidthPx = 1080,
            screenHeightPx = 2400,
            visibleTexts = listOf("Search", "Disabled Action"),
            clickableItems = listOf("Search"),
            elements = listOf(el0, el1)
        )
    }
}
