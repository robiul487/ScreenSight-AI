package com.example.agent

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.UUID

/**
 * Phase 3 Structured Physical-Device Test & Diagnostic Harness (Section 6).
 *
 * Defines the 10 mandatory real-device verification scenarios and records structured,
 * sanitized execution telemetry for every test run without ever fabricating physical-device passes.
 */
class PhysicalDeviceTestHarness {

    enum class DeviceTestScenario(
        val index: Int,
        val titleEn: String,
        val titleBn: String,
        val expectedVerification: String
    ) {
        OPEN_YOUTUBE(
            1,
            "Open YouTube",
            "YouTube খুলুন",
            "Foreground package becomes com.google.android.youtube with visible home UI."
        ),
        SEARCH_VIDEO(
            2,
            "Search for a specified video",
            "নির্দিষ্ট ভিডিও খুঁজুন",
            "YouTube search results list populates with matching video titles."
        ),
        SELECT_RESULT(
            3,
            "Select the correct result",
            "সঠিক ভিডিও নির্বাচন করুন",
            "Tapped grounded element_id opens the video player page."
        ),
        VERIFY_PLAYBACK(
            4,
            "Verify playback",
            "ভিডিও চলছে কিনা যাচাই করুন",
            "Screen snapshot confirms player controls (Pause/Subscribe/Like)."
        ),
        OPEN_SETTINGS_SECTION(
            5,
            "Open Settings & navigate to requested screen",
            "সেটিংস খুলে নির্দিষ্ট অপশনে যান",
            "Settings package active with target section visible."
        ),
        SET_ALARM(
            6,
            "Set an alarm",
            "অ্যালার্ম সেট করুন",
            "Android AlarmClock intent accepted and clock app confirms alarm."
        ),
        PREPARE_WHATSAPP_NO_SEND(
            7,
            "Prepare WhatsApp message without sending",
            "না পাঠিয়ে WhatsApp মেসেজ তৈরি করুন",
            "Requires confirmation first; opens draft without clicking Send."
        ),
        INTERRUPT_ACTIVE_TASK(
            8,
            "Interrupt active task via voice or Stop",
            "কথা বলে বা Stop দিয়ে কাজ থামান",
            "State transitions to CANCELLED immediately with clean cleanup."
        ),
        RECOVER_ELEMENT_NOT_FOUND(
            9,
            "Recover from element-not-found error",
            "বাটন না পেলে পুনরায় চেষ্টা",
            "Enters RECOVERING, retries up to maxRetriesPerAction, and adapts or fails cleanly."
        ),
        RECOVER_TASK_AFTER_RESTART(
            10,
            "Recover incomplete task after app restart",
            "অ্যাপ রিস্টার্টের পর অসমাপ্ত কাজ পুনরুদ্ধার",
            "Room checkpoint detected on startup; user prompted Resume/Cancel; sensitive steps never auto-replayed."
        )
    }

    enum class HarnessRunStatus {
        NOT_RUN,
        PASSED,
        FAILED
    }

    data class DeviceTestRunRecord(
        val recordId: String = UUID.randomUUID().toString().take(8),
        val scenario: DeviceTestScenario,
        val taskId: String,
        val agentState: AgentExecutionState,
        val toolName: String,
        val accessibilityResult: String,
        val preActionObservation: String,
        val postActionObservation: String,
        val verificationResult: String,
        val retryCount: Int,
        val executionDurationMs: Long,
        val failureReason: String? = null,
        val status: HarnessRunStatus,
        val executedOnPhysicalDevice: Boolean = false,
        val timestampMillis: Long = System.currentTimeMillis()
    )

    private val _records = MutableStateFlow<List<DeviceTestRunRecord>>(emptyList())
    val records: StateFlow<List<DeviceTestRunRecord>> = _records.asStateFlow()

    private val _activeScenario = MutableStateFlow<DeviceTestScenario?>(null)
    val activeScenario: StateFlow<DeviceTestScenario?> = _activeScenario.asStateFlow()

    fun setActiveScenario(scenario: DeviceTestScenario?) {
        _activeScenario.value = scenario
    }

    /**
     * Records real execution telemetry for a scenario run (sanitized via [AgentTaskLogger]).
     */
    fun recordRun(
        scenario: DeviceTestScenario,
        taskId: String,
        agentState: AgentExecutionState,
        toolName: String,
        accessibilityResult: String,
        preActionObservation: ScreenUiSnapshot?,
        postActionObservation: ScreenUiSnapshot?,
        verificationResult: String,
        retryCount: Int,
        executionDurationMs: Long,
        failureReason: String? = null,
        passed: Boolean,
        executedOnPhysicalDevice: Boolean
    ): DeviceTestRunRecord {
        val record = DeviceTestRunRecord(
            scenario = scenario,
            taskId = taskId,
            agentState = agentState,
            toolName = toolName,
            accessibilityResult = AgentTaskLogger.sanitizeForLog(accessibilityResult),
            preActionObservation = summarizeObservation(preActionObservation),
            postActionObservation = summarizeObservation(postActionObservation),
            verificationResult = AgentTaskLogger.sanitizeForLog(verificationResult),
            retryCount = retryCount.coerceAtLeast(0),
            executionDurationMs = executionDurationMs.coerceAtLeast(0L),
            failureReason = failureReason?.takeIf { it.isNotBlank() }?.let { AgentTaskLogger.sanitizeForLog(it) },
            status = if (passed) HarnessRunStatus.PASSED else HarnessRunStatus.FAILED,
            executedOnPhysicalDevice = executedOnPhysicalDevice
        )
        _records.update { list -> (list + record).takeLast(MAX_RECORDS) }
        return record
    }

    fun latestRecordFor(scenario: DeviceTestScenario): DeviceTestRunRecord? {
        return _records.value.lastOrNull { it.scenario == scenario }
    }

    fun clearRecords() {
        _records.value = emptyList()
        _activeScenario.value = null
    }

    fun buildDiagnosticSummary(): String {
        val current = _records.value
        val sb = StringBuilder()
        sb.append("ScreenSight AI — Physical Device Test Harness Summary\n")
        for (scenario in DeviceTestScenario.entries) {
            val rec = current.lastOrNull { it.scenario == scenario }
            if (rec == null) {
                sb.append("#${scenario.index} ${scenario.titleEn}: NOT_RUN\n")
            } else {
                val envTag = if (rec.executedOnPhysicalDevice) "DEVICE" else "JVM/SIM"
                sb.append("#${scenario.index} ${scenario.titleEn}: ${rec.status} [$envTag] ")
                    .append("task=${rec.taskId} state=${rec.agentState} tool=${rec.toolName} ")
                    .append("retries=${rec.retryCount} duration=${rec.executionDurationMs}ms")
                if (!rec.failureReason.isNullOrBlank()) {
                    sb.append(" failure=${rec.failureReason}")
                }
                sb.append('\n')
            }
        }
        return sb.toString()
    }

    private fun summarizeObservation(snapshot: ScreenUiSnapshot?): String {
        if (snapshot == null) return "none"
        return "obs=${snapshot.observationId} pkg=${snapshot.packageName.ifBlank { "unknown" }} " +
            "elements=${snapshot.elements.size} dim=${snapshot.screenWidthPx}x${snapshot.screenHeightPx}"
    }

    companion object {
        private const val MAX_RECORDS = 50
    }
}
