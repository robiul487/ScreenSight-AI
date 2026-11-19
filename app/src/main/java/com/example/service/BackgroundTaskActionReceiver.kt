package com.example.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.WorkManager
import com.example.ScreenSightApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Handles the user tapping "Cancel / বন্ধ করুন" directly from a background agent notification.
 * Immediately cancels the WorkManager job and any active in-process agent task.
 */
class BackgroundTaskActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ACTION_CANCEL_BACKGROUND_WORK) return

        val workName = intent.getStringExtra(EXTRA_WORK_NAME).orEmpty()
        val scheduleId = intent.getStringExtra(EXTRA_SCHEDULE_ID)

        runCatching {
            val wm = WorkManager.getInstance(context.applicationContext)
            if (workName.isNotBlank()) {
                wm.cancelUniqueWork(workName)
            }
            wm.cancelAllWorkByTag(AgentTaskScheduler.TAG_BACKGROUND_AGENT)
        }

        val app = context.applicationContext as? ScreenSightApp
        app?.container?.agentCoordinator?.cancelCurrentTask("Cancelled from notification")

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (!scheduleId.isNullOrBlank()) {
                    app?.container?.scheduledTaskRepository?.recordRunResult(
                        scheduleId = scheduleId,
                        runAtMillis = System.currentTimeMillis(),
                        resultSummary = "Cancelled by user from notification",
                        nextRunAtMillis = System.currentTimeMillis(),
                        stillEnabled = false
                    )
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        const val ACTION_CANCEL_BACKGROUND_WORK = "com.example.action.CANCEL_BACKGROUND_AGENT_WORK"
        const val EXTRA_WORK_NAME = "extra_work_name"
        const val EXTRA_SCHEDULE_ID = "extra_schedule_id"
    }
}
