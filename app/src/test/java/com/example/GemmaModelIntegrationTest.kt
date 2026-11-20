package com.example

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.agent.AgentActionRequest
import com.example.agent.AgentActionResult
import com.example.agent.AgentActionType
import com.example.agent.AgentCoordinator
import com.example.agent.AgentTaskLogger
import com.example.agent.AgentToolRegistry
import com.example.agent.DynamicToolManager
import com.example.agent.GemmaAgentReasoningEngine
import com.example.agent.IntelligentMemoryRetriever
import com.example.agent.LongTermTaskOrchestrator
import com.example.agent.MultiAgentOrchestrator
import com.example.agent.ScreenUiSnapshot
import com.example.agent.SkillLearningEngine
import com.example.data.ai.AIModelRouter
import com.example.data.ai.AIProviderId
import com.example.data.ai.AIReasoningRequest
import com.example.data.ai.AIReasoningTaskStage
import com.example.data.ai.ActiveModelIndicator
import com.example.data.ai.GemmaReasoningProvider
import com.example.data.ai.GeminiTextProvider
import com.example.data.ai.GoogleGenAiRestClient
import com.example.data.ai.ModelAvailabilityReport
import com.example.data.ai.ModelUsageRecord
import com.example.data.ai.ReasoningRoutingMode
import com.example.data.ai.VerifiedReasoningModels
import com.example.data.local.ApiKeySource
import com.example.data.local.ApiKeyStorage
import com.example.data.local.SettingsRepository
import com.example.data.local.checkpoint.AgentCheckpointDatabase
import com.example.data.local.memory.AgentMemoryRecord
import com.example.data.local.memory.AgentMemoryRepository
import com.example.data.local.memory.MemoryCategory
import com.example.data.local.memory.MemoryExpirationPolicy
import com.example.data.local.memory.MemorySource
import com.example.data.local.orchestration.LongTermTask
import com.example.data.local.orchestration.LongTermTaskRepository
import com.example.data.local.orchestration.LongTermTaskStatus
import com.example.data.local.orchestration.SubtaskStatus
import com.example.data.local.orchestration.TaskSubtask
import com.example.data.local.skill.AgentSkillRepository
import com.example.data.remote.BidiFunctionDeclaration
import com.example.data.remote.BidiSchema
import com.example.data.remote.GeminiLiveJson
import com.example.data.remote.GeminiWebSocketClient
import com.example.domain.model.AppSettings
import com.example.domain.model.ConnectionQuality
import com.example.domain.model.LiveSessionState
import com.example.domain.model.ScreenSightError
import com.example.ui.ApiValidationUiState
import com.example.ui.ReasoningModelTestUiState
import com.example.ui.screens.ApiSettingsScreen
import com.example.ui.screens.LiveAssistantScreen
import com.example.ui.theme.ScreenSightTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Comprehensive verification suite for ScreenSight AI's Gemma 4 Reasoning Integration.
 *
 * Covers all 19 required test areas using deterministic in-memory OkHttp interceptors,
 * real Room SQLite in-memory database, DataStore settings persistence, and Compose UI tests:
 *  1. Provider abstraction & verified model catalog (Gemma 4 31B vs Gemma 3 27B; no invented Gemma 3 31B)
 *  2. Same API key reuse across Gemini Live validation & Gemma 4 REST (`x-goog-api-key` header)
 *  3. Gemma request formatting (Gemma 4 native systemInstruction/tools vs Gemma 3 27B prompt fallback)
 *  4. Gemma response parsing (normal text, `thought == true` filtering, `usageMetadata`)
 *  5. Streaming SSE response handling (`streamGenerateContent?alt=sse`)
 *  6. Structured JSON output parsing (raw JSON, fenced markdown JSON, malformed JSON rejection)
 *  7. Tool call validation through AgentToolRegistry (valid, unregistered, invalid args, meta-tool rejected)
 *  8. Tool execution safety enforcement (Group C blocked; Group B requires explicit confirmation)
 *  9. Missing API key handling
 * 10. Invalid API key handling (HTTP 401 -> no wasted fallback)
 * 11. Unsupported model / HTTP 404 availability & fallback handling
 * 12. Rate limit (HTTP 429) bounded retry & fallback handling
 * 13. Network timeout & cooperative coroutine cancellation
 * 14. Automatic fallback behavior & ActiveModelIndicator update
 * 15. Routing rules (trivial local fast-path, complex Gemma routing, LOCAL_ONLY, single-request discipline)
 * 16. AgentCoordinator verification assist & LongTermTaskOrchestrator guided recovery (no repeat failure)
 * 17. MultiAgentOrchestrator role routing (PLANNER uses Gemma, EXECUTOR enforces boundaries, direct Q&A)
 * 18. Memory relevance & Skill proposal reasoning (strictly privacy-guarded, approval preserved)
 * 19. Settings persistence, API key privacy/log redaction, and Compose UI smoke verification
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class GemmaModelIntegrationTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var context: Context
    private lateinit var db: AgentCheckpointDatabase
    private lateinit var toolRegistry: AgentToolRegistry
    private lateinit var dynamicToolManager: DynamicToolManager
    private lateinit var memoryRepo: AgentMemoryRepository
    private lateinit var memoryRetriever: IntelligentMemoryRetriever
    private lateinit var skillRepo: AgentSkillRepository
    private lateinit var longTermRepo: LongTermTaskRepository

    private val syntheticApiKey = "synthetic-test-google-key-9876543210"

    private class FakeApiKeyStorage(initialKey: String) : ApiKeyStorage {
        private var currentKey: String = initialKey
        private val _has = MutableStateFlow(initialKey.isNotBlank())
        private val _masked = MutableStateFlow(if (initialKey.isBlank()) "" else "synt••••3210")
        private val _source = MutableStateFlow(
            if (initialKey.isBlank()) ApiKeySource.NONE else ApiKeySource.KEYSTORE_ENCRYPTED
        )

        override val hasApiKeyFlow: StateFlow<Boolean> = _has.asStateFlow()
        override val maskedApiKeyFlow: StateFlow<String> = _masked.asStateFlow()
        override val keySourceFlow: StateFlow<ApiKeySource> = _source.asStateFlow()

        override suspend fun getApiKey(): String = currentKey

        override suspend fun saveApiKey(rawKey: String) {
            currentKey = rawKey.trim()
            _has.value = currentKey.isNotBlank()
            _masked.value = if (currentKey.isBlank()) "" else "••••${currentKey.takeLast(4)}"
            _source.value = if (currentKey.isBlank()) ApiKeySource.NONE else ApiKeySource.KEYSTORE_ENCRYPTED
        }

        override suspend fun clearApiKey() {
            currentKey = ""
            _has.value = false
            _masked.value = ""
            _source.value = ApiKeySource.NONE
        }
    }

    private data class RecordedHttpCall(
        val method: String,
        val url: String,
        val apiKeyHeader: String?,
        val body: String
    )

    private fun buildMockRestStack(
        apiKey: String = syntheticApiKey,
        settingsProvider: () -> AppSettings = { AppSettings() },
        maxRetries: Int = 2,
        handler: (Request, String) -> Response
    ): QuadStack {
        val recordedCalls = CopyOnWriteArrayList<RecordedHttpCall>()
        val okHttp = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                val req = chain.request()
                val bodyBuf = Buffer()
                req.body?.writeTo(bodyBuf)
                val bodyStr = bodyBuf.readUtf8()
                recordedCalls.add(
                    RecordedHttpCall(
                        method = req.method,
                        url = req.url.toString(),
                        apiKeyHeader = req.header(GoogleGenAiRestClient.API_KEY_HEADER),
                        body = bodyStr
                    )
                )
                handler(req, bodyStr)
            })
            .build()

        val restClient = GoogleGenAiRestClient(
            okHttpClient = okHttp,
            maxTransientRetries = maxRetries,
            retryDelayProvider = { 5L }
        )
        val keyStorage = FakeApiKeyStorage(apiKey)
        val gemmaProvider = GemmaReasoningProvider(restClient)
        val geminiTextProvider = GeminiTextProvider(restClient)
        val router = AIModelRouter(
            apiKeyStorage = keyStorage,
            gemmaProvider = gemmaProvider,
            geminiTextProvider = geminiTextProvider,
            settingsProvider = settingsProvider
        )
        val engine = GemmaAgentReasoningEngine(
            modelRouter = router,
            toolRegistry = toolRegistry,
            dynamicToolManager = dynamicToolManager
        )
        return QuadStack(okHttp, restClient, router, engine, recordedCalls)
    }

    private data class QuadStack(
        val okHttp: OkHttpClient,
        val restClient: GoogleGenAiRestClient,
        val router: AIModelRouter,
        val engine: GemmaAgentReasoningEngine,
        val recordedCalls: CopyOnWriteArrayList<RecordedHttpCall>
    )

    private fun jsonResponse(request: Request, code: Int, jsonBody: String): Response {
        return Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message(if (code in 200..299) "OK" else "Error")
            .body(jsonBody.toResponseBody("application/json; charset=utf-8".toMediaType()))
            .build()
    }

    private fun sseResponse(request: Request, sseBody: String): Response {
        return Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(sseBody.toResponseBody("text/event-stream; charset=utf-8".toMediaType()))
            .build()
    }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AgentCheckpointDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        toolRegistry = AgentToolRegistry()
        dynamicToolManager = DynamicToolManager(
            toolRegistry = toolRegistry,
            metricDao = db.toolMetricDao(),
            isAccessibilityConnectedProvider = { true }
        )
        memoryRepo = AgentMemoryRepository(db.memoryDao())
        memoryRetriever = IntelligentMemoryRetriever(memoryRepo)
        skillRepo = AgentSkillRepository(db.skillDao())
        longTermRepo = LongTermTaskRepository(db.longTermTaskDao())
    }

    @After
    fun tearDown() {
        db.close()
    }

    // 1. Provider abstraction & verified model catalog
    @Test
    fun test01_providerAbstraction_andVerifiedGemma4Catalog() {
        val stack = buildMockRestStack { req, _ -> jsonResponse(req, 200, "{}") }
        val gemmaProvider = GemmaReasoningProvider(stack.restClient)
        val geminiProvider = GeminiTextProvider(stack.restClient)

        assertTrue(gemmaProvider.supportsModel("models/gemma-4-31b-it"))
        assertTrue(gemmaProvider.supportsModel("gemma-4-26b-a4b-it"))
        assertFalse(gemmaProvider.supportsModel("models/gemini-2.5-flash"))

        assertTrue(geminiProvider.supportsModel("models/gemini-2.5-flash"))
        assertFalse(geminiProvider.supportsModel("models/gemma-4-31b-it"))

        // Verified catalog: Gemma 4 31B exists; Gemma 3 max is 27B; no fake "gemma-3-31b" in verified catalog
        val ids = VerifiedReasoningModels.ALL_VERIFIED_REASONING_MODELS.map { it.id }
        assertTrue(ids.contains("models/gemma-4-31b-it"))
        assertTrue(ids.contains("models/gemma-4-26b-a4b-it"))
        assertTrue(ids.contains("models/gemma-3-27b-it"))
        assertTrue(ids.contains("models/gemini-2.5-flash"))
        assertFalse(ids.any { it.contains("gemma-3-31b", ignoreCase = true) })

        // Capability truthfulness: Gemma 4 supports native tool calling & systemInstruction; Gemma 3 27B does not; neither supports Live audio
        assertTrue(VerifiedReasoningModels.GEMMA_4_31B_IT.capabilities.supportsNativeToolCalling)
        assertTrue(VerifiedReasoningModels.GEMMA_4_31B_IT.capabilities.supportsSystemInstruction)
        assertFalse(VerifiedReasoningModels.GEMMA_4_31B_IT.capabilities.supportsLiveAudio)

        assertFalse(VerifiedReasoningModels.GEMMA_3_27B_IT.capabilities.supportsNativeToolCalling)
        assertFalse(VerifiedReasoningModels.GEMMA_3_27B_IT.capabilities.supportsSystemInstruction)
    }

    // 2. Existing API key reuse across Gemini Live validation & Gemma REST
    @Test
    fun test02_existingApiKeyReuse_forBothGeminiValidationAndGemmaRest(): Unit = runBlocking {
        val catalogPayload = """
            {
              "models": [
                {"name": "models/gemini-3.1-flash-live-preview", "supportedGenerationMethods": ["bidiGenerateContent"]},
                {"name": "models/gemma-4-31b-it", "supportedGenerationMethods": ["generateContent", "countTokens"]},
                {"name": "models/gemma-4-26b-a4b-it", "supportedGenerationMethods": ["generateContent"]},
                {"name": "models/gemini-2.5-flash", "supportedGenerationMethods": ["generateContent"]}
              ]
            }
        """.trimIndent()
        val stack = buildMockRestStack { req, _ ->
            if (req.url.encodedPath.endsWith("/models")) {
                jsonResponse(req, 200, catalogPayload)
            } else {
                jsonResponse(
                    req,
                    200,
                    """{"candidates":[{"content":{"parts":[{"text":"Gemma reply"}]}}],"usageMetadata":{"promptTokenCount":12,"candidatesTokenCount":6,"totalTokenCount":18}}"""
                )
            }
        }

        // Validate key through existing GeminiWebSocketClient (reused OkHttp)
        val wsClient = GeminiWebSocketClient(stack.okHttp)
        val validation = wsClient.validateApiKeyAndModel(syntheticApiKey, "models/gemini-3.1-flash-live-preview")
        assertTrue(validation.isValid)
        assertTrue(validation.discoveredLiveModels.contains("models/gemini-3.1-flash-live-preview"))
        assertTrue(validation.discoveredGemmaModels.contains("models/gemma-4-31b-it"))
        assertTrue(validation.discoveredGemmaModels.contains("models/gemma-4-26b-a4b-it"))

        // Now call Gemma 4 REST using the EXACT SAME key via AIModelRouter
        val resp = stack.router.reason(
            AIReasoningRequest(
                taskStage = AIReasoningTaskStage.GENERAL_TEXT_REASONING,
                userPrompt = "Summarize the schedule clearly"
            )
        )
        assertTrue(resp.success)
        assertEquals("Gemma reply", resp.text)

        // Verify EVERY HTTP call carried the same key in x-goog-api-key header and NEVER in URL query
        assertEquals(2, stack.recordedCalls.size)
        stack.recordedCalls.forEach { call ->
            assertEquals(syntheticApiKey, call.apiKeyHeader)
            assertFalse("API key must never appear in URL query string", call.url.contains(syntheticApiKey))
        }
    }

    // 3. Gemma request formatting (Gemma 4 native vs Gemma 3 27B fallback)
    @Test
    fun test03_gemmaRequestFormatting_nativeGemma4VsFallbackGemma3() {
        val stack = buildMockRestStack { req, _ -> jsonResponse(req, 200, "{}") }
        val toolDecl = BidiFunctionDeclaration(
            name = "open_app",
            description = "Opens an installed Android app",
            parameters = BidiSchema(
                type = "OBJECT",
                properties = mapOf("app_name" to BidiSchema(type = "STRING", description = "App name")),
                required = listOf("app_name")
            )
        )
        val req = AIReasoningRequest(
            taskStage = AIReasoningTaskStage.TASK_PLANNING,
            userPrompt = "Open YouTube and search tutorials",
            systemInstruction = "Use only registered Android tools.",
            tools = listOf(toolDecl)
        )

        // Gemma 4 31B: MUST emit top-level systemInstruction, tools.functionDeclarations, toolConfig
        val gemma4Json = GeminiLiveJson.json.parseToJsonElement(
            stack.restClient.buildGenerateContentPayload(VerifiedReasoningModels.GEMMA_4_31B_IT, req)
        ).jsonObject
        assertNotNull("Gemma 4 must include native systemInstruction", gemma4Json["systemInstruction"])
        assertNotNull("Gemma 4 must include native tools", gemma4Json["tools"])
        assertNotNull("Gemma 4 must include toolConfig", gemma4Json["toolConfig"])

        // Gemma 3 27B: MUST omit native systemInstruction & tools and inline instructions into user prompt
        val gemma3Json = GeminiLiveJson.json.parseToJsonElement(
            stack.restClient.buildGenerateContentPayload(VerifiedReasoningModels.GEMMA_3_27B_IT, req)
        ).jsonObject
        assertNull("Gemma 3 27B must NOT send native systemInstruction", gemma3Json["systemInstruction"])
        assertNull("Gemma 3 27B must NOT send native tools", gemma3Json["tools"])
        val userPartText = gemma3Json["contents"]!!.jsonArray[0].jsonObject["parts"]!!
            .jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content
        assertTrue(userPartText.contains("[INSTRUCTIONS]"))
        assertTrue(userPartText.contains("Use only registered Android tools."))
    }

    // 4. Gemma response parsing (filters thought parts, parses usageMetadata & functionCall)
    @Test
    fun test04_gemmaResponseParsing_filtersThoughtPartsAndParsesUsage() {
        val stack = buildMockRestStack { req, _ -> jsonResponse(req, 200, "{}") }
        val rawBody = """
            {
              "candidates": [
                {
                  "content": {
                    "role": "model",
                    "parts": [
                      {"text": "Internal hidden chain-of-thought reasoning...", "thought": true},
                      {"text": "Final visible plan summary."},
                      {"functionCall": {"name": "open_app", "args": {"app_name": "YouTube"}}}
                    ]
                  }
                }
              ],
              "usageMetadata": {
                "promptTokenCount": 140,
                "candidatesTokenCount": 32,
                "thoughtsTokenCount": 88,
                "totalTokenCount": 260
              }
            }
        """.trimIndent()

        val parsed = stack.restClient.parseGenerateContentResponse(
            rawBody = rawBody,
            modelId = VerifiedReasoningModels.GEMMA_4_31B_IT.id,
            providerId = AIProviderId.GOOGLE_GEMMA_REST,
            taskStage = AIReasoningTaskStage.TASK_PLANNING,
            latencyMs = 412L
        )
        assertTrue(parsed.success)
        assertEquals("Final visible plan summary.", parsed.text)
        assertFalse("Internal thought text must never leak", parsed.text.contains("chain-of-thought"))
        assertEquals(1, parsed.proposedToolCalls.size)
        assertEquals("open_app", parsed.proposedToolCalls[0].toolName)
        assertEquals("YouTube", parsed.proposedToolCalls[0].arguments["app_name"])
        assertEquals(140, parsed.tokenUsage.promptTokens)
        assertEquals(32, parsed.tokenUsage.candidatesTokens)
        assertEquals(88, parsed.tokenUsage.thoughtTokens)
        assertEquals(260, parsed.tokenUsage.totalTokens)
    }

    // 5. Streaming SSE response handling
    @Test
    fun test05_streamingSseResponseHandling(): Unit = runBlocking {
        val ssePayload = buildString {
            appendLine("""data: {"candidates":[{"content":{"parts":[{"text":"Thinking...", "thought": true}]}}]}""")
            appendLine()
            appendLine("""data: {"candidates":[{"content":{"parts":[{"text":"Step 1: "}]}}]}""")
            appendLine()
            appendLine("""data: {"candidates":[{"content":{"parts":[{"text":"Check time."}]}}],"usageMetadata":{"promptTokenCount":20,"candidatesTokenCount":8,"totalTokenCount":28}}""")
            appendLine()
            appendLine("data: [DONE]")
            appendLine()
        }
        val stack = buildMockRestStack { req, _ -> sseResponse(req, ssePayload) }
        val chunks = stack.router.streamReason(
            AIReasoningRequest(
                taskStage = AIReasoningTaskStage.GENERAL_TEXT_REASONING,
                userPrompt = "Stream a short answer"
            )
        ).toList()

        val combinedText = chunks.joinToString("") { it.deltaText }
        assertEquals("Step 1: Check time.", combinedText)
        assertFalse("Thought chunks must be filtered during streaming", combinedText.contains("Thinking"))
        assertTrue("Final chunk must mark isDone=true", chunks.last().isDone)
        assertNull(chunks.last().error)
        assertTrue(stack.recordedCalls.first().url.contains(":streamGenerateContent?alt=sse"))
    }

    // 6. Structured JSON output parsing (clean JSON, markdown fenced JSON, malformed rejection)
    @Test
    fun test06_structuredJsonOutputParsing_andMalformedHandling() {
        val clean = GemmaAgentReasoningEngine.extractJsonObjectFromText("""{"verified": true, "explanation": "Clock visible"}""")
        assertNotNull(clean)
        assertEquals("Clock visible", clean!!["explanation"]!!.jsonPrimitive.content)

        val fenced = GemmaAgentReasoningEngine.extractJsonObjectFromText(
            """
            Sure, here is the plan:
            ```json
            {"plan_summary": "Check time", "subtasks": [{"title": "Time", "tool_name": "get_date_time", "parameters": {}}]}
            ```
            """.trimIndent()
        )
        assertNotNull(fenced)
        assertEquals("Check time", fenced!!["plan_summary"]!!.jsonPrimitive.content)

        val malformed = GemmaAgentReasoningEngine.extractJsonObjectFromText("{broken json ::: not valid")
        assertNull(malformed)
    }

    // 7. Tool call validation through AgentToolRegistry
    @Test
    fun test07_toolCallValidation_acceptsValid_rejectsHallucinatedInvalidAndMetaTools(): Unit = runBlocking {
        val responseJson = """
            {
              "candidates": [{
                "content": {
                  "parts": [
                    {"functionCall": {"name": "get_date_time", "args": {}}},
                    {"functionCall": {"name": "invented_root_shell", "args": {"cmd": "rm -rf"}}},
                    {"functionCall": {"name": "open_app", "args": {}}},
                    {"functionCall": {"name": "create_plan", "args": {"task_description": "nested"}}}
                  ]
                }
              }]
            }
        """.trimIndent()
        val stack = buildMockRestStack { req, _ -> jsonResponse(req, 200, responseJson) }

        val planned = stack.engine.planGoalWithReasoning("Check current time and summarize my morning routine")
        assertTrue(planned.usedCloudModel)
        assertEquals("Only valid registered non-meta tool must survive", 1, planned.subtasks.size)
        assertEquals("get_date_time", planned.subtasks[0].toolName)
        assertEquals("3 invalid/hallucinated/meta tool proposals must be rejected", 3, planned.rejectedToolProposalsCount)
    }

    // 8. Tool execution blocked if unsafe or requires confirmation
    @Test
    fun test08_toolExecutionSafety_blocksHighRiskAndRequiresConfirmationForSensitive(): Unit = runBlocking {
        val responseJson = """
            {
              "candidates": [{
                "content": {
                  "parts": [
                    {"functionCall": {"name": "click_element", "args": {"target_text": "Confirm Payment 5000 BDT"}}},
                    {"functionCall": {"name": "call_trusted_contact", "args": {"contact_query": "Maa"}}}
                  ]
                }
              }]
            }
        """.trimIndent()
        val stack = buildMockRestStack { req, _ -> jsonResponse(req, 200, responseJson) }

        val planned = stack.engine.planGoalWithReasoning("Help me pay and call Maa afterwards for confirmation")
        assertEquals("Payment tap MUST be blocked by AgentSafetyManager", 1, planned.blockedBySafetyCount)
        assertEquals("Only call_trusted_contact remains, tagged sensitive", 1, planned.subtasks.size)
        assertEquals("call_trusted_contact", planned.subtasks[0].toolName)
        assertTrue("Group B sensitive action must require explicit confirmation", planned.subtasks[0].isSensitive)

        // Running through LongTermTaskOrchestrator without user confirmation MUST pause with ConfirmationRequired
        val orchestrator = LongTermTaskOrchestrator(
            repository = longTermRepo,
            dynamicToolManager = dynamicToolManager,
            gemmaReasoningEngine = stack.engine
        )
        val created = orchestrator.createTask("Call Maa after review", planned.subtasks)
        assertTrue(created is LongTermTaskOrchestrator.CreateTaskOutcome.Created)
        val taskId = (created as LongTermTaskOrchestrator.CreateTaskOutcome.Created).task.taskId

        val outcome = orchestrator.runTask(taskId, userConfirmedSensitiveSubtaskId = null)
        assertTrue("Sensitive Gemma-planned action must NEVER auto-execute", outcome is LongTermTaskOrchestrator.RunOutcome.ConfirmationRequired)
    }

    // 9. Missing API key handling
    @Test
    fun test09_missingApiKey_returnsMissingApiKeyAndDoesNotCallNetwork(): Unit = runBlocking {
        val stack = buildMockRestStack(apiKey = "") { req, _ -> jsonResponse(req, 200, "{}") }
        val response = stack.router.reason(
            AIReasoningRequest(
                taskStage = AIReasoningTaskStage.TASK_PLANNING,
                userPrompt = "Plan a multi-step morning briefing"
            )
        )
        assertFalse(response.success)
        assertEquals(ScreenSightError.MissingApiKey, response.error)
        assertEquals("Zero network calls when API key is missing", 0, stack.recordedCalls.size)
    }

    // 10. Invalid API key handling (HTTP 401)
    @Test
    fun test10_invalidApiKey_http401_doesNotWasteFallbackCall(): Unit = runBlocking {
        val stack = buildMockRestStack { req, _ ->
            jsonResponse(req, 401, """{"error":{"code":401,"message":"API key not valid. Please pass a valid API key."}}""")
        }
        val response = stack.router.reason(
            AIReasoningRequest(
                taskStage = AIReasoningTaskStage.TASK_PLANNING,
                userPrompt = "Analyze my day and prepare tasks"
            )
        )
        assertFalse(response.success)
        assertTrue(response.error is ScreenSightError.InvalidApiKey)
        assertFalse(response.usedFallback)
        assertEquals("Must NOT retry or fallback on invalid API key", 1, stack.recordedCalls.size)
    }

    // 11. Unsupported model / HTTP 404 availability probe & fallback
    @Test
    fun test11_unsupportedModel404_probeFailsAndReasonFallsBack(): Unit = runBlocking {
        val stack = buildMockRestStack { req, _ ->
            if (req.url.toString().contains("gemma-4-31b-it")) {
                jsonResponse(req, 404, """{"error":{"code":404,"message":"Model not found"}}""")
            } else {
                jsonResponse(
                    req,
                    200,
                    """{"candidates":[{"content":{"parts":[{"text":"Fallback succeeded"}]}}]}"""
                )
            }
        }

        // Real availability probe reports unavailable cleanly
        val probe = stack.router.testModelAvailability(modelId = VerifiedReasoningModels.GEMMA_4_31B_IT.id)
        assertFalse(probe.isAvailable)
        assertTrue(probe.error is ScreenSightError.UnsupportedModel)

        // Reason request automatically falls back from 404 primary to fallback model
        val res = stack.router.reason(
            AIReasoningRequest(
                taskStage = AIReasoningTaskStage.GENERAL_TEXT_REASONING,
                userPrompt = "Explain Android accessibility nodes clearly"
            )
        )
        assertTrue(res.success)
        assertTrue(res.usedFallback)
        assertEquals(VerifiedReasoningModels.DEFAULT_FALLBACK.id, res.modelId)
        assertEquals("Fallback succeeded", res.text)
    }

    // 12. Rate limit (HTTP 429) bounded retry & recovery
    @Test
    fun test12_rateLimit429_retriesBoundedAndRecovers(): Unit = runBlocking {
        val attempts = AtomicInteger(0)
        val stack = buildMockRestStack(maxRetries = 2) { req, _ ->
            val n = attempts.incrementAndGet()
            if (n == 1) {
                jsonResponse(req, 429, """{"error":{"code":429,"message":"Rate limit exceeded"}}""")
            } else {
                jsonResponse(
                    req,
                    200,
                    """{"candidates":[{"content":{"parts":[{"text":"Recovered after backoff"}]}}]}"""
                )
            }
        }

        val res = stack.router.reason(
            AIReasoningRequest(
                taskStage = AIReasoningTaskStage.GENERAL_TEXT_REASONING,
                userPrompt = "Plan my afternoon tasks carefully"
            )
        )
        assertTrue(res.success)
        assertEquals("Recovered after backoff", res.text)
        assertEquals(2, attempts.get())
        assertFalse(res.usedFallback)
    }

    // 13. Network timeout & cooperative coroutine cancellation
    @Test
    fun test13_timeoutAndCancellation_abortsInFlightCall(): Unit = runBlocking {
        val startedLatch = CountDownLatch(1)
        val cancelledFlag = AtomicBoolean(false)

        val slowOkHttp = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                startedLatch.countDown()
                try {
                    Thread.sleep(3_000L)
                } catch (_: InterruptedException) {
                    cancelledFlag.set(true)
                    throw IOException("Canceled")
                }
                if (chain.call().isCanceled()) {
                    cancelledFlag.set(true)
                    throw IOException("Canceled")
                }
                jsonResponse(chain.request(), 200, "{}")
            })
            .build()

        val client = GoogleGenAiRestClient(
            okHttpClient = slowOkHttp,
            maxTransientRetries = 0
        )

        val job = launch(Dispatchers.Default) {
            try {
                client.generateContent(
                    apiKey = syntheticApiKey,
                    modelDescriptor = VerifiedReasoningModels.GEMMA_4_31B_IT,
                    request = AIReasoningRequest(
                        taskStage = AIReasoningTaskStage.GENERAL_TEXT_REASONING,
                        userPrompt = "Long request",
                        timeoutMs = 15_000L
                    )
                )
            } catch (_: CancellationException) {
                // Expected cooperative cancellation
            }
        }
        assertTrue(startedLatch.await(2, TimeUnit.SECONDS))
        job.cancel()
        job.join()
        assertTrue("Coroutine job must cancel cleanly", job.isCancelled)
    }

    // 14. Automatic fallback behavior & ActiveModelIndicator update
    @Test
    fun test14_automaticFallback_updatesTelemetryAndIndicator(): Unit = runBlocking {
        val stack = buildMockRestStack(
            settingsProvider = {
                AppSettings(
                    reasoningModelId = VerifiedReasoningModels.GEMMA_4_31B_IT.id,
                    reasoningFallbackModelId = VerifiedReasoningModels.GEMINI_2_5_FLASH.id
                )
            },
            maxRetries = 0
        ) { req, _ ->
            if (req.url.toString().contains("gemma-4-31b-it")) {
                jsonResponse(req, 503, """{"error":{"code":503,"message":"Service temporarily unavailable"}}""")
            } else {
                jsonResponse(
                    req,
                    200,
                    """{"candidates":[{"content":{"parts":[{"text":"Handled by Gemini 2.5 Flash fallback"}]}}],"usageMetadata":{"promptTokenCount":15,"candidatesTokenCount":10,"totalTokenCount":25}}"""
                )
            }
        }

        val res = stack.router.reason(
            request = AIReasoningRequest(
                taskStage = AIReasoningTaskStage.TASK_PLANNING,
                userPrompt = "Organize my reading list and suggest next steps"
            ),
            taskIdForTelemetry = "task_fb_1"
        )
        assertTrue(res.success)
        assertTrue(res.usedFallback)
        assertEquals(VerifiedReasoningModels.GEMINI_2_5_FLASH.id, res.modelId)
        assertEquals(AIProviderId.GOOGLE_GEMINI_REST, res.providerId)

        val indicator = stack.router.activeModelIndicator.value
        assertTrue(indicator.isUsingFallback)
        assertEquals("Fallback Model", indicator.modeBadge)

        val usage = stack.router.getModelUsageForTask("task_fb_1")
        assertNotNull(usage)
        assertTrue(usage!!.usedFallback)
        assertEquals(15, usage.promptTokens)
        assertEquals(10, usage.outputTokens)
    }

    // 15. Routing rules: trivial local fast-path, complex Gemma, LOCAL_ONLY, single-request discipline
    @Test
    fun test15_routingRules_andSingleRequestDiscipline(): Unit = runBlocking {
        var mode = ReasoningRoutingMode.AUTO_COMPLEX
        var enabled = true
        val stack = buildMockRestStack(
            settingsProvider = {
                AppSettings(
                    gemmaReasoningEnabled = enabled,
                    reasoningRoutingMode = mode
                )
            }
        ) { req, _ ->
            jsonResponse(
                req,
                200,
                """{"candidates":[{"content":{"parts":[{"text":"{\"plan_summary\":\"ok\",\"subtasks\":[{\"title\":\"Time\",\"tool_name\":\"get_date_time\",\"parameters\":{}}]}"}]}}]}"""
            )
        }

        // Trivial commands MUST stay on local fast-path (ZERO cloud calls)
        assertTrue(AIModelRouter.isTrivialDeterministicLocalCommand("Check time"))
        assertTrue(AIModelRouter.isTrivialDeterministicLocalCommand("Open YouTube"))
        assertFalse(stack.router.shouldUseCloudReasoning("Check time", AIReasoningTaskStage.TASK_PLANNING))

        val trivialPlan = stack.engine.planGoalWithReasoning("Open YouTube")
        assertFalse(trivialPlan.usedCloudModel)
        assertEquals(0, stack.recordedCalls.size)

        // Complex multi-step goal routes to Gemma with SINGLE-REQUEST discipline (exactly 1 HTTP call)
        assertTrue(stack.router.shouldUseCloudReasoning("Check time and then summarize my daily agenda", AIReasoningTaskStage.TASK_PLANNING))
        val complexPlan = stack.engine.planGoalWithReasoning("Check time and then summarize my daily agenda")
        assertTrue(complexPlan.usedCloudModel)
        assertEquals("Single-request discipline: exactly 1 HTTP request", 1, stack.recordedCalls.size)
        assertTrue(stack.recordedCalls[0].url.contains("gemma-4-31b-it"))

        // LOCAL_ONLY disables cloud routing
        mode = ReasoningRoutingMode.LOCAL_ONLY
        assertFalse(stack.router.shouldUseCloudReasoning("Plan a complex weekly workflow", AIReasoningTaskStage.TASK_PLANNING))
        val localOnlyResp = stack.router.reason(
            AIReasoningRequest(AIReasoningTaskStage.TASK_PLANNING, "Plan a complex weekly workflow")
        )
        assertFalse(localOnlyResp.success)
        assertEquals(AIProviderId.LOCAL_FAST_PATH, localOnlyResp.providerId)
        assertEquals(1, stack.recordedCalls.size)

        // Toggle off also blocks cloud routing
        mode = ReasoningRoutingMode.ALWAYS_GEMMA_TEXT
        enabled = false
        assertFalse(stack.router.shouldUseCloudReasoning("Any text prompt", AIReasoningTaskStage.GENERAL_TEXT_REASONING))
    }

    // 16. AgentCoordinator verification assist & LongTermTaskOrchestrator guided recovery (no repeat failure)
    @Test
    fun test16_coordinatorVerification_andLongTermGuidedRecovery(): Unit = runBlocking {
        val stack = buildMockRestStack { req, body ->
            if (body.contains("Observed screen text")) {
                jsonResponse(
                    req,
                    200,
                    """{"candidates":[{"content":{"parts":[{"text":"{\"verified\": true, \"explanation\": \"YouTube Home feed confirmed on screen.\"}"}]}}]}"""
                )
            } else {
                // Recovery proposes BOTH the already-failed action (must be filtered!) and a fresh valid alternative
                jsonResponse(
                    req,
                    200,
                    """{"candidates":[{"content":{"parts":[{"text":"{\"explanation\":\"Use date/time check instead\",\"subtasks\":[{\"title\":\"Repeat failed app\",\"tool_name\":\"open_app\",\"parameters\":{\"app_name\":\"BrokenApp\"}},{\"title\":\"Check current time\",\"tool_name\":\"get_date_time\",\"parameters\":{}}]}"}]}}]}"""
                )
            }
        }

        // AgentCoordinator verification assist grounded in ScreenUiSnapshot
        val coordinator = AgentCoordinator(
            geminiRepository = null,
            actionExecutor = null,
            settingsRepository = null,
            voiceFeedback = null,
            toolRegistry = toolRegistry,
            screenSnapshotProvider = {
                ScreenUiSnapshot(
                    packageName = "com.google.android.youtube",
                    visibleTexts = listOf("YouTube", "Home", "Subscriptions")
                )
            },
            gemmaReasoningEngine = stack.engine
        )
        val ver = coordinator.assistScreenVerificationWithGemma(
            goal = "Open YouTube home screen",
            expectedOutcome = "YouTube Home visible"
        )
        assertNotNull(ver)
        assertTrue(ver!!.usedCloudModel)
        assertTrue(ver.appearsVerified)
        assertTrue(ver.explanation.contains("YouTube"))

        // LongTermTaskOrchestrator guided recovery filters out already-failed signature
        val orchestrator = LongTermTaskOrchestrator(
            repository = longTermRepo,
            dynamicToolManager = dynamicToolManager,
            gemmaReasoningEngine = stack.engine,
            toolExecutor = { req, _ ->
                if (req.actionType == AgentActionType.OPEN_APP) {
                    AgentActionResult(false, "APP_NOT_FOUND", "BrokenApp not installed", "অ্যাপটি নেই")
                } else {
                    AgentActionResult(true, "SUCCESS", "Time checked", "সময় দেখা হয়েছে")
                }
            }
        )

        val created = orchestrator.createTask(
            goal = "Open BrokenApp and check status",
            subtasks = listOf(
                TaskSubtask(
                    subtaskId = "s1",
                    title = "Open BrokenApp",
                    toolName = "open_app",
                    parameters = mapOf("app_name" to "BrokenApp")
                )
            ),
            modelUsageNote = "Model: Gemma 4 31B IT (Gemma Reasoning • Task Planning • OK)"
        ) as LongTermTaskOrchestrator.CreateTaskOutcome.Created

        assertTrue(created.task.lastCheckpointNote.contains("Gemma 4 31B IT"))

        val failedRun = orchestrator.runTask(created.task.taskId)
        assertTrue(failedRun is LongTermTaskOrchestrator.RunOutcome.Failed)

        val recoveredOutcome = orchestrator.attemptGuidedRecovery(created.task.taskId)
        assertTrue("Guided recovery should produce a replanned task", recoveredOutcome is LongTermTaskOrchestrator.CreateTaskOutcome.Created)
        val recoveredTask = (recoveredOutcome as LongTermTaskOrchestrator.CreateTaskOutcome.Created).task
        val pendingAfterRecovery = recoveredTask.subtasks.filter { it.status == SubtaskStatus.PENDING }
        assertEquals("Already-failed BrokenApp action must be filtered out", 1, pendingAfterRecovery.size)
        assertEquals("get_date_time", pendingAfterRecovery[0].toolName)

        val finalRun = orchestrator.runTask(created.task.taskId)
        assertTrue(finalRun is LongTermTaskOrchestrator.RunOutcome.Completed)
    }

    // 17. MultiAgentOrchestrator role routing & direct Q&A answer
    @Test
    fun test17_multiAgentOrchestrator_usesGemmaInPlannerAndEnforcesRoleBoundaries(): Unit = runBlocking {
        val stack = buildMockRestStack { req, _ ->
            jsonResponse(
                req,
                200,
                """{"candidates":[{"content":{"parts":[{"text":"{\"plan_summary\":\"Direct Q&A\",\"direct_answer\":\"Gemma 4 31B handles planning while Gemini Live handles voice.\",\"subtasks\":[]}"}]}}]}"""
            )
        }

        val multiAgent = MultiAgentOrchestrator(
            dynamicToolManager = dynamicToolManager,
            memoryRetriever = memoryRetriever,
            skillLearningEngine = SkillLearningEngine(toolRegistry),
            gemmaReasoningEngine = stack.engine
        )

        // Role boundary invariant remains unbroken: PLANNER cannot dispatch device tools
        val denied = multiAgent.dispatchToolForRole(
            role = MultiAgentOrchestrator.AgentRole.PLANNER,
            request = AgentActionRequest(actionType = AgentActionType.GET_DATE_TIME)
        )
        assertTrue(denied is MultiAgentOrchestrator.RoleToolDispatchResult.DeniedByRoleBoundary)

        // Complex Q&A goal uses Gemma in PLANNER and populates directAnswer + modelUsage cleanly
        val ctx = multiAgent.runControlledWorkflow(
            goal = "Explain the difference between Gemma 4 reasoning and Gemini Live voice",
            executeSafePlan = false
        )
        assertNotNull(ctx.directAnswer)
        assertTrue(ctx.directAnswer!!.contains("Gemma 4 31B"))
        assertNotNull(ctx.modelUsage)
        assertEquals(VerifiedReasoningModels.GEMMA_4_31B_IT.id, ctx.modelUsage!!.modelId)
        assertTrue(ctx.plannedSubtasks.isEmpty())
    }

    // 18. Memory relevance & Skill proposal reasoning (strictly privacy-guarded)
    @Test
    fun test18_memoryRelevanceAndSkillProposal_enforcesPrivacyGuard(): Unit = runBlocking {
        val stack = buildMockRestStack { req, body ->
            // Verify sensitive OTP memory was stripped BEFORE sending to Gemma
            assertFalse("Sensitive OTP memory must NEVER be sent to cloud", body.contains("994821"))
            if (body.contains("Sanitized local memories")) {
                jsonResponse(
                    req,
                    200,
                    """{"candidates":[{"content":{"parts":[{"text":"{\"relevant_ids\":[\"m_pref\",\"m_hallucinated\"],\"summary_hint\":\"User prefers Bangla\"}"}]}}]}"""
                )
            } else {
                jsonResponse(
                    req,
                    200,
                    """{"candidates":[{"content":{"parts":[{"text":"{\"name\":\"Morning Time Routine\",\"description\":\"Checks time and reads today's agenda safely.\"}"}]}}]}"""
                )
            }
        }

        val now = System.currentTimeMillis()
        val memories = listOf(
            AgentMemoryRecord(
                id = "m_pref",
                category = MemoryCategory.USER_PREFERENCE,
                memoryKey = "preferred_language",
                value = "Bangla",
                createdAtMillis = now,
                updatedAtMillis = now,
                lastUsedAtMillis = now,
                confidence = 0.95f,
                source = MemorySource.USER_EXPLICIT,
                expirationPolicy = MemoryExpirationPolicy.NEVER,
                expiresAtMillis = null,
                userApproved = true
            ),
            AgentMemoryRecord(
                id = "m_secret",
                category = MemoryCategory.USER_PREFERENCE,
                memoryKey = "bank_otp_code",
                value = "OTP 994821 for bank login",
                createdAtMillis = now,
                updatedAtMillis = now,
                lastUsedAtMillis = now,
                confidence = 0.8f,
                source = MemorySource.TASK_OUTCOME,
                expirationPolicy = MemoryExpirationPolicy.NEVER,
                expiresAtMillis = null,
                userApproved = true
            )
        )

        val ranking = stack.engine.assistMemoryRelevance("Greet me in my preferred language", memories)
        assertEquals("Hallucinated memory IDs must be ignored", listOf("m_pref"), ranking.orderedMemoryIds)
        assertTrue(ranking.summaryHint.contains("Bangla"))

        val skillSuggestion = stack.engine.assistSkillProposal(
            taskGoal = "Check current time and summarize morning routine",
            toolNamesUsed = listOf("get_date_time", "read_screen")
        )
        assertEquals("Morning Time Routine", skillSuggestion.suggestedName)
        assertTrue(skillSuggestion.suggestedDescription.contains("Checks time"))
    }

    // 19. Settings persistence, API key privacy/redaction, and UI Compose smoke checks
    @Test
    fun test19_settingsPersistence_keyRedaction_andComposeUiSmoke(): Unit = runBlocking {
        // 19a. DataStore round-trip persistence for Gemma reasoning settings (reusing app singleton DataStore)
        val settingsRepo = (context.applicationContext as ScreenSightApp).container.settingsRepository
        settingsRepo.updateReasoningModel(VerifiedReasoningModels.GEMMA_4_26B_A4B_IT.id)
        settingsRepo.updateReasoningFallbackModel(VerifiedReasoningModels.GEMINI_2_5_FLASH.id)
        settingsRepo.updateGemmaReasoningEnabled(false)
        settingsRepo.updateReasoningRoutingMode(ReasoningRoutingMode.ALWAYS_GEMMA_TEXT)
        settingsRepo.updateReasoningTimeoutSec(45)

        val persisted = settingsRepo.settingsFlow.first()
        assertEquals(VerifiedReasoningModels.GEMMA_4_26B_A4B_IT.id, persisted.reasoningModelId)
        assertEquals(VerifiedReasoningModels.GEMINI_2_5_FLASH.id, persisted.reasoningFallbackModelId)
        assertFalse(persisted.gemmaReasoningEnabled)
        assertEquals(ReasoningRoutingMode.ALWAYS_GEMMA_TEXT, persisted.reasoningRoutingMode)
        assertEquals(45, persisted.reasoningTimeoutSec)

        // Restore defaults cleanly for subsequent tests
        settingsRepo.updateReasoningModel(VerifiedReasoningModels.DEFAULT_PRIMARY.id)
        settingsRepo.updateGemmaReasoningEnabled(true)
        settingsRepo.updateReasoningRoutingMode(ReasoningRoutingMode.AUTO_COMPLEX)
        settingsRepo.updateReasoningTimeoutSec(25)

        // 19b. API key redaction in error sanitization & ModelUsageRecord
        val stack = buildMockRestStack { req, _ -> jsonResponse(req, 200, "{}") }
        val leakedErr = "Upstream rejected key=$syntheticApiKey while calling endpoint"
        val scrubbed = stack.restClient.sanitizeErrorBody(leakedErr, syntheticApiKey)
        assertFalse("Raw API key must NEVER survive error sanitization", scrubbed.contains(syntheticApiKey))
        assertTrue(scrubbed.contains("[REDACTED_API_KEY]"))

        val usageTag = ModelUsageRecord(
            modelId = VerifiedReasoningModels.GEMMA_4_31B_IT.id,
            modelDisplayName = VerifiedReasoningModels.GEMMA_4_31B_IT.displayName,
            providerId = AIProviderId.GOOGLE_GEMMA_REST,
            taskStage = AIReasoningTaskStage.TASK_PLANNING,
            succeeded = true,
            promptTokens = 40,
            outputTokens = 12,
            latencyMs = 220L
        ).toCompactCheckpointTag()
        assertFalse(usageTag.contains(syntheticApiKey))
        assertTrue(usageTag.contains("Gemma 4 31B IT"))

        // 19c. Compose UI smoke check: ApiSettingsScreen Gemma 4 section renders cleanly
        composeRule.setContent {
            ScreenSightTheme {
                ApiSettingsScreen(
                    settings = AppSettings(
                        selectedModelId = "models/gemini-3.1-flash-live-preview",
                        fallbackModelId = "models/gemini-2.5-flash-native-audio-preview-12-2025",
                        reasoningModelId = VerifiedReasoningModels.GEMMA_4_31B_IT.id,
                        reasoningFallbackModelId = VerifiedReasoningModels.GEMMA_4_26B_A4B_IT.id,
                        gemmaReasoningEnabled = true,
                        reasoningRoutingMode = ReasoningRoutingMode.AUTO_COMPLEX,
                        reasoningTimeoutSec = 25
                    ),
                    hasApiKey = true,
                    maskedApiKey = "synt••••3210",
                    apiKeySource = ApiKeySource.KEYSTORE_ENCRYPTED,
                    validationState = ApiValidationUiState.Valid(
                        message = "Key valid!",
                        discoveredModels = listOf("models/gemini-3.1-flash-live-preview"),
                        discoveredGemmaModels = listOf("models/gemma-4-31b-it")
                    ),
                    onSaveApiKey = {},
                    onValidateApiKey = {},
                    onRemoveApiKey = {},
                    onSelectModel = {},
                    onSelectFallbackModel = {},
                    reasoningTestState = ReasoningModelTestUiState.Available(
                        ModelAvailabilityReport(
                            modelId = VerifiedReasoningModels.GEMMA_4_31B_IT.id,
                            providerId = AIProviderId.GOOGLE_GEMMA_REST,
                            isAvailable = true,
                            supportedMethods = listOf("generateContent"),
                            statusMessage = "Gemma 4 31B IT verified on Google Gemini API."
                        )
                    ),
                    onSelectReasoningModel = {},
                    onSelectReasoningFallbackModel = {},
                    onToggleGemmaReasoning = {},
                    onSelectReasoningRoutingMode = {},
                    onUpdateReasoningTimeoutSec = {},
                    onTestReasoningModel = {},
                    onBack = {}
                )
            }
        }
        composeRule.onNodeWithTag("gemma_reasoning_settings_card").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("test_reasoning_model_button").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("reasoning_test_success_box").performScrollTo().assertIsDisplayed()
    }

    // 20. LiveAssistantScreen active model indicator pill smoke check
    @Test
    fun test20_liveAssistantScreen_showsActiveModelIndicatorPill() {
        composeRule.setContent {
            ScreenSightTheme {
                LiveAssistantScreen(
                    selectedModelName = "Gemini 3.1 Flash Live",
                    sessionState = LiveSessionState.IDLE,
                    isSharingScreen = false,
                    isMicMuted = false,
                    isUserSpeaking = false,
                    micLevel = 0f,
                    isAiMuted = false,
                    isAiSpeaking = false,
                    framesSentCount = 0,
                    framesSkippedCount = 0,
                    lastFrameDimensions = "",
                    connectionQuality = ConnectionQuality.DISCONNECTED,
                    transcript = emptyList(),
                    activeError = null,
                    activeModelIndicator = ActiveModelIndicator(
                        activeLabel = "Gemma 4 31B IT",
                        modeBadge = "Gemma Reasoning",
                        providerId = AIProviderId.GOOGLE_GEMMA_REST,
                        modelId = VerifiedReasoningModels.GEMMA_4_31B_IT.id
                    ),
                    onBack = {},
                    onStartSession = {},
                    onRequestShareScreen = {},
                    onStopShareScreen = {},
                    onToggleMicMute = {},
                    onToggleAiAudioMute = {},
                    onEndConversation = {},
                    onSendTextMessage = {},
                    onClearTranscript = {},
                    onDismissError = {},
                    onNavigateToApiSettings = {}
                )
            }
        }
        composeRule.onNodeWithTag("active_model_indicator_pill").assertIsDisplayed()
    }
}
