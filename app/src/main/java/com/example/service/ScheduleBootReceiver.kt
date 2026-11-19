package com.example.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.ScreenSightApp
import com.example.data.local.checkpoint.AgentCheckpointDatabase
import com.example.data.local.schedule.ScheduledTaskRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Restores locally saved, user-approved scheduled tasks after device reboot,
 * app package replacement, or system time/timezone changes.
 */
class ScheduleBootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action !in SUPPORTED_ACTIONS) return

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val appContainer = (context.applicationContext as? ScreenSightApp)?.container
                val scheduler = appContainer?.taskScheduler ?: run {
                    val db = AgentCheckpointDatabase.getInstance(context.applicationContext)
                    AgentTaskScheduler(
                        context = context.applicationContext,
                        repository = ScheduledTaskRepository(db.scheduledTaskDao())
                    )
                }
                scheduler.restoreAllSchedulesAfterReboot()
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        private val SUPPORTED_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED
        )
    }
}
