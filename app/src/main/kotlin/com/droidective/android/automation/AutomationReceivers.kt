package com.droidective.android.automation

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.droidective.android.droidective
import com.droidective.core.automations.Event
import kotlinx.coroutines.launch

/** Runs one firing for as long as it takes, including after a Wait. */
class AutomationWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val request = RunRequest.from(inputData) ?: return Result.failure()
        AutomationRunner(applicationContext).fire(request)
        return Result.success()
    }

    override suspend fun getForegroundInfo(): ForegroundInfo =
        AutomationNotifications.working(applicationContext)
}

/**
 * The phone finished starting, the app was updated, or the clock changed. Exported because Android
 * delivers these to it; they're protected broadcasts that only the system can send, and anything
 * else is ignored.
 */
class AutomationSystemReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val restarted = intent.action == Intent.ACTION_BOOT_COMPLETED
        if (!restarted && intent.action !in rescheduleOnly) return
        val pending = goAsync()
        context.droidective.scope.launch {
            try {
                // Alarms don't survive a restart or an update, and a clock change moves them.
                Automations.reschedule(context)
                if (restarted) Automations.onEvent(context, Event.Restarted)
            } finally {
                pending.finish()
            }
        }
    }

    private val rescheduleOnly =
        setOf(
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
        )
}

/** A schedule's alarm. Not exported: only the app's own PendingIntent reaches it. */
class AutomationAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra(EXTRA_ID) ?: return
        val pending = goAsync()
        context.droidective.scope.launch {
            try {
                val automation = context.droidective.automations.get(id)
                if (automation != null) {
                    Automations.runNow(context, id, automation.trigger.describe())
                }
                Automations.reschedule(context)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val EXTRA_ID = "com.droidective.android.extra.AUTOMATION_ID"
    }
}

/** A notification button that runs an automation. Not exported. */
class AutomationButtonReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra(EXTRA_ID) ?: return
        val from = intent.getStringExtra(EXTRA_FROM).orEmpty()
        Automations.runNow(context, id, "A button on “$from”’s notification")
    }

    companion object {
        const val EXTRA_ID = "com.droidective.android.extra.AUTOMATION_ID"
        const val EXTRA_FROM = "com.droidective.android.extra.FROM"
    }
}
