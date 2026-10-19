package com.droidective.android.monkey

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.droidective.android.R
import com.droidective.android.crashes.CrashAlerts
import com.droidective.android.droidective
import com.droidective.core.apps.Monkey
import com.droidective.core.apps.MonkeyReport
import com.droidective.core.shell.ShellUnavailableException
import com.droidective.core.shell.Stoppable
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** One run: which app, how many events, from which seed, and whether it swipes. */
data class MonkeyPlan(val packageName: String, val count: Int, val seed: Long, val swipes: Boolean)

/** Where a Monkey run is. */
sealed interface MonkeyRun {
    data class Running(val plan: MonkeyPlan, val events: Int) : MonkeyRun

    data class Done(val report: MonkeyReport, val seed: Long) : MonkeyRun

    data class Failed(val message: String) : MonkeyRun
}

/**
 * The one Monkey run, held by the app rather than a screen: while Monkey runs it refuses to let any
 * other app's screen open, Droidective's included, so it's stopped from its notification. As root
 * it also outlives its stream, so Stop kills it where it runs ([Stoppable]).
 */
object MonkeyRuns {
    private const val CHANNEL = "monkey"
    private const val NOTIFICATION_ID = 7_401
    private const val PROGRESS_EVERY = 25

    private val current = MutableStateFlow<MonkeyRun?>(null)
    val state: StateFlow<MonkeyRun?> = current.asStateFlow()

    private var job: Job? = null
    private var pid: Int? = null
    @Volatile private var stopping = false

    fun start(context: Context, plan: MonkeyPlan) {
        if (job != null) return
        val app = context.droidective
        stopping = false
        current.value = MonkeyRun.Running(plan, 0)
        notify(context, plan, 0)
        job =
            app.scope.launch {
                try {
                    val result = run(context, plan)
                    // Killed by Stop, the stream just ends: that's a stop, not a clean run.
                    if (!stopping) current.value = result
                } finally {
                    pid = null
                    job = null
                    // Stopped: what it got to, and the seed, so the run can be repeated.
                    (current.value as? MonkeyRun.Running)?.let {
                        val after = if (it.events > 0) " after ${it.events} events" else ""
                        current.value = MonkeyRun.Failed("Stopped$after · seed ${plan.seed}")
                    }
                    context
                        .getSystemService(NotificationManager::class.java)
                        .cancel(NOTIFICATION_ID)
                }
            }
    }

    fun stop(context: Context) {
        val running = job ?: return
        stopping = true
        val monkey = pid
        context.droidective.scope.launch {
            if (monkey != null) {
                try {
                    context.droidective.access.shell()?.run(Stoppable.stop(monkey))
                } catch (_: ShellUnavailableException) {
                    // The shell went away, and Monkey with it.
                }
            }
            running.cancel()
        }
    }

    private suspend fun run(context: Context, plan: MonkeyPlan): MonkeyRun {
        val shell =
            context.droidective.access.shell() ?: return MonkeyRun.Failed("Shell isn't running.")
        val output = StringBuilder()
        var sent = 0
        val command = Monkey.command(plan.packageName, plan.count, plan.seed, plan.swipes)
        try {
            shell.stream(Stoppable.wrap(command)).collect { line ->
                Stoppable.pid(line)?.let {
                    pid = it
                    return@collect
                }
                output.appendLine(line)
                if (line.startsWith(":Sending")) {
                    sent++
                    if (sent % PROGRESS_EVERY == 0) {
                        current.value = MonkeyRun.Running(plan, sent)
                        notify(context, plan, sent)
                    }
                }
            }
        } catch (e: ShellUnavailableException) {
            return MonkeyRun.Failed(e.message ?: "The shell stopped responding.")
        }
        return MonkeyRun.Done(Monkey.report(output.toString()), plan.seed)
    }

    // A tap on the notification stops Monkey through a receiver, not an activity: Monkey refuses
    // to start any other app's activity while it runs, so a screen to stop it could never open.
    @SuppressLint("LaunchActivityFromNotification")
    private fun notify(context: Context, plan: MonkeyPlan, sent: Int) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Monkey Test", NotificationManager.IMPORTANCE_LOW).apply {
                description = "A Monkey run, with the button that stops it"
            }
        )
        val stop =
            PendingIntent.getBroadcast(
                context,
                0,
                Intent(context, MonkeyStopReceiver::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        val notification =
            NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.sym_pets)
                .setContentTitle(
                    "Monkey is testing ${CrashAlerts.label(context, plan.packageName)}"
                )
                // The whole notification stops it: an action shows only once it's expanded, and
                // Monkey keeps taking the screen back while someone reaches for it.
                .setContentText(
                    if (sent > 0) "$sent of ${plan.count} events · tap to stop"
                    else "${plan.count} events · tap to stop"
                )
                .setContentIntent(stop)
                // Through Shizuku, Monkey's output can arrive in bursts: no count yet isn't zero.
                .setProgress(plan.count, sent, sent == 0)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .addAction(0, "Stop", stop)
                .build()
        try {
            manager.notify(NOTIFICATION_ID, notification)
        } catch (_: SecurityException) {
            // Notifications are off; the Monkey Test screen says so before a run starts.
        }
    }
}

/** The notification's Stop. Not exported: only that PendingIntent reaches it. */
class MonkeyStopReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        MonkeyRuns.stop(context)
    }
}
