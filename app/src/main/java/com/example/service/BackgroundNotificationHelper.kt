package com.example.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity

/**
 * Visible, user-transparent notifications for Android-compliant background agent tasks.
 *
 * Guarantees:
 * - Every background task displays a clear notification with an immediate "Cancel / বন্ধ করুন" action.
 * - Never executes silently or hides activity from the user.
 */
object BackgroundNotificationHelper {

    const val CHANNEL_ID = "screensight_bg_agent"
    const val CHANNEL_NAME = "ScreenSight Scheduled & Background Tasks"
    const val ONGOING_NOTIFICATION_ID_BASE = 4200
    const val RESULT_NOTIFICATION_ID_BASE = 4300

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Visible status and cancel controls for ScreenSight AI scheduled tasks and reminders."
            }
            manager.createNotificationChannel(channel)
        }
    }

    fun buildOngoingTaskNotification(
        context: Context,
        workUniqueName: String,
        scheduleId: String?,
        title: String,
        body: String
    ): Notification {
        ensureChannel(context)
        val openAppIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val openPending = PendingIntent.getActivity(
            context,
            workUniqueName.hashCode(),
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val cancelIntent = Intent(context, BackgroundTaskActionReceiver::class.java).apply {
            action = BackgroundTaskActionReceiver.ACTION_CANCEL_BACKGROUND_WORK
            putExtra(BackgroundTaskActionReceiver.EXTRA_WORK_NAME, workUniqueName)
            if (!scheduleId.isNullOrBlank()) {
                putExtra(BackgroundTaskActionReceiver.EXTRA_SCHEDULE_ID, scheduleId)
            }
        }
        val cancelPending = PendingIntent.getBroadcast(
            context,
            (workUniqueName + "_cancel").hashCode(),
            cancelIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(body)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(openPending)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Cancel / বন্ধ করুন",
                cancelPending
            )
            .build()
    }

    fun showVisibleNotification(
        context: Context,
        notificationId: Int,
        title: String,
        body: String
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        ensureChannel(context)
        val openAppIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val openPending = PendingIntent.getActivity(
            context,
            notificationId,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(body)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(openPending)
            .build()

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        runCatching { manager.notify(notificationId, notification) }
    }

    fun cancelNotification(context: Context, notificationId: Int) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        runCatching { manager.cancel(notificationId) }
    }
}
