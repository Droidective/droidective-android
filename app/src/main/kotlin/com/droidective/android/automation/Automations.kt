package com.droidective.android.automation

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.droidective.android.droidective
import com.droidective.core.automations.Cause
import com.droidective.core.automations.Clock
import com.droidective.core.automations.Event
import com.droidective.core.automations.Firing
import com.droidective.core.automations.Matcher
import com.droidective.core.automations.RecentWrites
import com.droidective.core.automations.Trigger
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.launch

/**
 * One firing of one automation, as the work that runs it carries it.
 *
 * @property cause what set it off, for History, such as "Every day at 23:30".
 * @property chain the automations that ran it, so a chain can't loop.
 * @property startAt the action to start from; a Wait carries on from the one after it.
 */
data class RunRequest(
    val automationId: String,
    val kind: Kind,
    val cause: String,
    val chain: Cause = Cause(),
    val startAt: Int = 0,
) {
    enum class Kind {
        Run,
        Begin,
        Finish,
    }

    fun toData(): Data =
        workDataOf(
            KEY_ID to automationId,
            KEY_KIND to kind.name,
            KEY_CAUSE to cause,
            KEY_CHAIN to chain.chain.toTypedArray(),
            KEY_START_AT to startAt,
        )

    companion object {
        private const val KEY_ID = "id"
        private const val KEY_KIND = "kind"
        private const val KEY_CAUSE = "cause"
        private const val KEY_CHAIN = "chain"
        private const val KEY_START_AT = "startAt"

        fun from(data: Data): RunRequest? {
            val id = data.getString(KEY_ID) ?: return null
            val kind =
                Kind.entries.firstOrNull { it.name == data.getString(KEY_KIND) } ?: return null
            return RunRequest(
                automationId = id,
                kind = kind,
                cause = data.getString(KEY_CAUSE).orEmpty(),
                chain = Cause(data.getNullableStringArray(KEY_CHAIN)?.filterNotNull().orEmpty()),
                startAt = data.getInt(KEY_START_AT, 0),
            )
        }

        fun of(firing: Firing, cause: String, chain: Cause = Cause()) =
            RunRequest(
                firing.automation.id,
                when (firing) {
                    is Firing.Run -> Kind.Run
                    is Firing.Begin -> Kind.Begin
                    is Firing.Finish -> Kind.Finish
                },
                cause,
                chain,
            )
    }
}

/**
 * The app's side of automations: it hands each firing to WorkManager, which keeps it alive past the
 * receiver that noticed it and through a Wait of up to an hour, and it keeps the alarms behind
 * schedules set.
 */
object Automations {
    /** The toggles runs just set, so a change they cause carries their chain. */
    val recentWrites = RecentWrites()

    const val CAUSE_MANUAL = "Run from Droidective"
    const val CAUSE_SHELL = "Run from the Mac or adb"
    private const val ALARM_PREFS = "automation-alarms"
    private const val KEY_SCHEDULED = "scheduled"
    private const val INEXACT_WINDOW_MILLIS = 10 * 60 * 1_000L

    /** Whether schedules fire on the minute: Alarms & reminders, which Android 12+ asks for. */
    fun exactAlarms(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

    /** Runs [automationId] once, the way a tap, a shortcut or the Mac does. */
    fun runNow(context: Context, automationId: String, cause: String) =
        enqueue(context, RunRequest(automationId, RunRequest.Kind.Run, cause))

    /** Something happened that may fire automations. */
    fun onEvent(context: Context, event: Event) {
        val all = context.droidective.automations.all()
        Matcher.onEvent(all, event).forEach {
            enqueue(context, RunRequest.of(it, event.describe()))
        }
    }

    fun enqueue(context: Context, request: RunRequest, delayMillis: Long = 0) {
        val work =
            OneTimeWorkRequestBuilder<AutomationWorker>()
                .setInputData(request.toData())
                .apply {
                    if (delayMillis > 0) setInitialDelay(delayMillis, TimeUnit.MILLISECONDS)
                    else setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                }
                .build()
        // Unique per firing, so two quick firings of one automation both run, in order.
        WorkManager.getInstance(context)
            .enqueueUniqueWork(
                "automation-${request.automationId}-${UUID.randomUUID()}",
                ExistingWorkPolicy.KEEP,
                work,
            )
    }

    /**
     * Sets an alarm for the next time of every enabled schedule and cancels the rest, then arms the
     * listeners the watched states need. Runs on every save, on every app start, after each alarm,
     * at boot, and when the clock or time zone changes.
     */
    fun reschedule(context: Context) {
        val alarms = context.getSystemService(AlarmManager::class.java)
        val prefs = context.getSharedPreferences(ALARM_PREFS, Context.MODE_PRIVATE)
        prefs.getStringSet(KEY_SCHEDULED, emptySet()).orEmpty().forEach { id ->
            alarms.cancel(alarmIntent(context, id))
        }
        val scheduled =
            context.droidective.automations
                .all()
                .filter { it.enabled }
                .mapNotNull { automation ->
                    val schedule =
                        (automation.trigger as? Trigger.On)?.event as? Event.Schedule
                            ?: return@mapNotNull null
                    val at =
                        Clock.next(schedule, LocalDateTime.now())
                            .atZone(ZoneId.systemDefault())
                            .toInstant()
                            .toEpochMilli()
                    setAlarm(context, at, alarmIntent(context, automation.id))
                    automation.id
                }
        prefs.edit { putStringSet(KEY_SCHEDULED, scheduled.toSet()) }
        // The states Android wakes the app for: their listeners, then one look at where they are.
        StateSignals.arm(context)
        AutomationService.sync(context)
        val app = context.droidective
        app.scope.launch { StateSignals.check(app) }
    }

    /** On the minute with Alarms & reminders; otherwise the narrowest window Android allows. */
    fun setAlarm(context: Context, atMillis: Long, pending: PendingIntent) {
        val alarms = context.getSystemService(AlarmManager::class.java)
        if (exactAlarms(context)) {
            alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pending)
        } else {
            alarms.setWindow(AlarmManager.RTC_WAKEUP, atMillis, INEXACT_WINDOW_MILLIS, pending)
        }
    }

    private fun alarmIntent(context: Context, automationId: String): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            0,
            Intent(context, AutomationAlarmReceiver::class.java)
                // One alarm per automation: the data makes each intent distinct.
                .setData("droidective://automation/$automationId".toUri())
                .putExtra(AutomationAlarmReceiver.EXTRA_ID, automationId),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
}
