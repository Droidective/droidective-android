package com.droidective.android.automation

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import androidx.core.net.toUri
import androidx.work.ForegroundInfo
import com.droidective.android.NoticeIds
import com.droidective.android.R
import com.droidective.core.automations.Action
import com.droidective.core.automations.Automation

/** The notifications automations post: their own, the ones that need a tap, and the work one. */
internal object AutomationNotifications {
    private const val CHANNEL = "automations"
    private const val CHANNEL_WORKING = "automations-working"
    private const val WORKING_ID = NoticeIds.AUTOMATION_WORKING
    private const val MAX_BUTTONS = 3

    /** A Notify action: its title, text, and up to three buttons. */
    fun post(context: Context, automation: Automation, notify: Action.Notify) {
        val builder =
            builder(context)
                .setContentTitle(notify.title)
                .setContentText(notify.text.ifBlank { null })
                .setSubText(automation.name)
        notify.buttons.take(MAX_BUTTONS).forEachIndexed { index, button ->
            val pending =
                buttonIntent(context, automation, index, button.action) ?: return@forEachIndexed
            builder.addAction(
                Notification.Action.Builder(null as Icon?, button.label, pending).build()
            )
        }
        show(context, "automation:${automation.id}", builder.build())
    }

    /** An open the phone wouldn't allow from the background: tapping it opens [intent]. */
    fun postOpen(context: Context, description: String, intent: Intent) {
        val pending =
            PendingIntent.getActivity(
                context,
                description.hashCode(),
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        val notification =
            builder(context)
                .setContentTitle(description)
                .setContentText("Tap to open")
                .setContentIntent(pending)
                .setAutoCancel(true)
                .build()
        show(context, "open:$description", notification)
    }

    /** What Android shows while a run works in the foreground, on Android 11. */
    fun working(context: Context): ForegroundInfo {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_WORKING,
                "Running automations",
                NotificationManager.IMPORTANCE_MIN,
            )
        )
        val notification =
            Notification.Builder(context, CHANNEL_WORKING)
                .setSmallIcon(R.drawable.sym_bolt)
                .setContentTitle("Running an automation")
                .build()
        return ForegroundInfo(WORKING_ID, notification)
    }

    private fun buttonIntent(
        context: Context,
        automation: Automation,
        index: Int,
        action: Action,
    ): PendingIntent? {
        val code = "${automation.id}:$index".hashCode()
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        if (action is Action.RunAutomation) {
            val intent =
                Intent(context, AutomationButtonReceiver::class.java)
                    .setData("droidective://automation-button/$code".toUri())
                    .putExtra(AutomationButtonReceiver.EXTRA_ID, action.automationId)
                    .putExtra(AutomationButtonReceiver.EXTRA_FROM, automation.name)
            return PendingIntent.getBroadcast(context, code, intent, flags)
        }
        val open = AutomationActions.openIntent(context, action) ?: return null
        return PendingIntent.getActivity(
            context,
            code,
            open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            flags,
        )
    }

    private fun builder(context: Context): Notification.Builder {
        context
            .getSystemService(NotificationManager::class.java)
            .createNotificationChannel(
                NotificationChannel(CHANNEL, "Automations", NotificationManager.IMPORTANCE_DEFAULT)
                    .apply { description = "Notifications your automations post" }
            )
        return Notification.Builder(context, CHANNEL).setSmallIcon(R.drawable.sym_bolt)
    }

    private fun show(context: Context, tag: String, notification: Notification) {
        try {
            context.getSystemService(NotificationManager::class.java).notify(tag, 1, notification)
        } catch (_: SecurityException) {
            // Notifications are off for the app; History still records the run.
        }
    }
}
