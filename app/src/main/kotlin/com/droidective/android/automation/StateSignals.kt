package com.droidective.android.automation

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.droidective.android.droidective
import com.droidective.android.surface.ToggleTaps
import com.droidective.core.automations.Clock
import com.droidective.core.automations.Firing
import com.droidective.core.automations.Matcher
import com.droidective.core.automations.State
import com.droidective.core.automations.StateChange
import com.droidective.core.automations.StateWatch
import com.droidective.core.toggles.Toggles
import com.droidective.platform.StateReader
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The states Android wakes the app for: a Bluetooth device connecting or disconnecting, and a time
 * window opening and closing. Each signal re-reads every one of them, so the answer never depends
 * on what a single broadcast said, and the last readings are kept in a file because the process can
 * die between one signal and the next.
 */
internal object StateSignals {
    private const val SETTLE_WORK = "automation-state-settle"
    private const val BLUETOOTH_WORK = "automation-state-bluetooth"

    /** An audio profile connects a few seconds after the link does, so look again after this. */
    private const val BLUETOOTH_RECHECK_MILLIS = 8_000L

    private val checking = Mutex()

    /**
     * Reads every watched state, or just [only], and fires the automations their changes set off.
     * Android's signals and the automation service's both come here.
     */
    suspend fun check(context: Context, only: Set<State>? = null) = checking.withLock {
        val app = context.droidective
        val automations = app.automations.all()
        val watched = Matcher.watched(automations)
        val watch = StateWatch(app.ledger.watch()).apply { retain(watched) }
        val reader =
            StateReader(
                context,
                shell = { app.access.shell() },
                toggleState = { id -> Toggles.byId(id)?.let { ToggleTaps.stateOf(context, it) } },
            )
        val now = System.currentTimeMillis()
        val changes = watch.due(now).toMutableList()
        for (state in only?.intersect(watched) ?: watched) {
            val holds = reader.holds(state) ?: continue
            watch.observe(state, holds, now)?.let(changes::add)
        }
        app.ledger.saveWatch(watch.snapshot())
        watch.nextDue()?.let { recheck(context, SETTLE_WORK, it - now) }
        for (change in changes) {
            val chain = Automations.recentWrites.causeOf(change, now)
            Matcher.onChange(automations, change).forEach { firing ->
                Automations.enqueue(context, RunRequest.of(firing, cause(firing, change), chain))
            }
        }
    }

    /** A Bluetooth link came or went: look now, and again once its audio profile has caught up. */
    fun bluetoothChanged(context: Context) {
        recheck(context, BLUETOOTH_WORK, BLUETOOTH_RECHECK_MILLIS)
    }

    /** Sets the alarm for the next time-window edge, or cancels it when no window is watched. */
    fun arm(context: Context) {
        val watched = Matcher.watched(context.droidective.automations.all())
        val alarms = context.getSystemService(AlarmManager::class.java)
        val timeIntent = signalIntent(context, "time")
        alarms.cancel(timeIntent)
        val now = LocalDateTime.now()
        watched
            .filterIsInstance<State.TimeBetween>()
            .minOfOrNull { Clock.nextChange(it, now) }
            ?.let { next ->
                val at = next.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                Automations.setAlarm(context, at, timeIntent)
            }
    }

    private fun cause(firing: Firing, change: StateChange): String {
        val trigger = firing.automation.trigger.describe()
        return when (firing) {
            is Firing.Begin ->
                if (change.initial) "$trigger: already true when it was checked"
                else "$trigger: started"
            is Firing.Finish -> "$trigger: ended"
            is Firing.Run -> trigger
        }
    }

    private fun recheck(context: Context, name: String, delayMillis: Long) {
        val work =
            OneTimeWorkRequestBuilder<StateCheckWorker>()
                .setInitialDelay(delayMillis.coerceAtLeast(0), TimeUnit.MILLISECONDS)
                .build()
        WorkManager.getInstance(context).enqueueUniqueWork(name, ExistingWorkPolicy.REPLACE, work)
    }

    /** The intent the time alarm sends. */
    private fun signalIntent(context: Context, kind: String): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            0,
            Intent(context, StateSignalReceiver::class.java)
                .setData("droidective://state-signal/$kind".toUri()),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
}

/** A time window's edge. Not exported: only the app's own alarm reaches it. */
class StateSignalReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val app = context.droidective
        app.scope.launch {
            try {
                StateSignals.check(app)
                // An edge has passed; set the alarm for the next one.
                if (intent.data?.lastPathSegment == "time") StateSignals.arm(app)
            } finally {
                pending.finish()
            }
        }
    }
}

/**
 * A Bluetooth device connected or disconnected. Exported because the system sends these; they're
 * protected broadcasts no app can fake, and the receiver only ever re-reads the truth.
 */
class BluetoothSignalReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in actions) return
        val pending = goAsync()
        val app = context.droidective
        app.scope.launch {
            try {
                StateSignals.check(app)
                StateSignals.bluetoothChanged(app)
            } finally {
                pending.finish()
            }
        }
    }

    private val actions =
        setOf(
            "android.bluetooth.device.action.ACL_CONNECTED",
            "android.bluetooth.device.action.ACL_DISCONNECTED",
        )
}

/** A delayed look at the watched states: a change finishing its settle time, or Bluetooth. */
class StateCheckWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        StateSignals.check(applicationContext)
        return Result.success()
    }
}
