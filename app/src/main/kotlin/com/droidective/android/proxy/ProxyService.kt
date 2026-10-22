package com.droidective.android.proxy

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import com.droidective.android.MainActivity
import com.droidective.android.R
import com.droidective.android.droidective
import com.droidective.android.surface.Launch
import com.droidective.android.surface.Surfaces
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Keeps the debugging proxy running in the foreground.
 *
 * Not for show: without a foreground service OxygenOS freezes the app, and a frozen proxy is a
 * phone whose network has silently stopped. The notification is also the way out — Stop is there
 * whatever the phone is showing.
 */
class ProxyService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            running(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            } else {
                0
            },
        )
        if (intent?.action == ACTION_STOP) {
            job?.cancel()
            job = scope.launch { stopListening() }
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_GO) {
            // From the held notification: the app on screen is waiting.
            Holds.go(intent.getLongExtra(EXTRA_HOLD, -1))
            if (job != null) return START_STICKY
            // A notice left by a process Android ended: listen again, as a sticky restart would.
        }
        val point = intent?.getBooleanExtra(EXTRA_POINT, false) ?: false
        if (job == null) job = scope.launch { listen(point) }
        // Started again after Android ended the process, it listens again: the phone may still be
        // pointed here, and a phone pointed at nothing has no network at all.
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        TrafficProxy.shutDown()
        ProxyNotices.held(this, emptyList())
        Surfaces.refreshBehind(this)
        // Ended some other way than Stop: the phone still has to come off the proxy.
        if (TrafficProxy.refreshPointing(this)) {
            val context = applicationContext
            droidective.scope.launch { TrafficProxy.point(context, here = false) }
        }
        super.onDestroy()
    }

    private suspend fun listen(point: Boolean) {
        TrafficProxy.run(this)
        if (!TrafficProxy.running) {
            // The screen says why; a notification that stays up for a proxy that isn't would not.
            Surfaces.refreshBehind(this)
            stopSelf()
            return
        }
        if (point) TrafficProxy.point(this, here = true)
        // A Debugging proxy tile or widget slot shows whether it runs.
        Surfaces.refreshBehind(this)
        // Its first answer is nothing held, which also takes down a notice a process Android
        // ended left behind.
        scope.launch { Holds.held.collect { ProxyNotices.held(this@ProxyService, it) } }
        TrafficProxy.changes.collect {
            notify(running())
            delay(NOTIFY_GAP)
        }
    }

    /**
     * Takes the phone off the proxy while this is still a foreground service, so Android has no
     * reason to end the process before the setting is back the way it was.
     */
    private suspend fun stopListening() {
        TrafficProxy.shutDown()
        val released = TrafficProxy.point(this, here = false)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        if (!released) notify(stuck())
        stopSelf()
    }

    private fun notify(notification: Notification) = ProxyNotices.post(this, notification)

    private fun running(): Notification {
        val text =
            if (TrafficProxy.running) {
                "Listening on ${TrafficProxy.ADDRESS} · ${requests(TrafficProxy.flows.size())}"
            } else {
                "Starting"
            }
        return builder()
            .setContentTitle("Watching this phone's traffic")
            .setContentText(text)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Stop", stopIntent()).build())
            .build()
    }

    /** The setting could not be put back, which leaves the phone with no network. */
    private fun stuck(): Notification =
        builder()
            .setContentTitle("This phone is still pointed at the proxy")
            .setContentText("Apps can't reach the network until it's cleared. Tap to clear it.")
            .setAutoCancel(true)
            .build()

    private fun builder(): Notification.Builder = ProxyNotices.builder(this)

    private fun stopIntent(): PendingIntent =
        PendingIntent.getService(
            this,
            0,
            Intent(this, ProxyService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )

    private fun requests(count: Int): String = if (count == 1) "1 request" else "$count requests"

    companion object {
        internal const val ACTION_STOP = "com.droidective.android.action.STOP_TRAFFIC_PROXY"
        internal const val ACTION_GO = "com.droidective.android.action.LET_HELD_GO"
        internal const val EXTRA_HOLD = "com.droidective.android.extra.HOLD"
        internal const val EXTRA_POINT = "com.droidective.android.extra.POINT"
        private const val NOTIFICATION_ID = ProxyNotices.ID

        /** How often the count in the notification may change; it is a glance, not a feed. */
        private const val NOTIFY_GAP = 2_000L
    }
}

/** The proxy's notifications: the one its service keeps up, and the ones left behind. */
internal object ProxyNotices {
    const val ID = 7_430
    private const val CHANNEL = "traffic-proxy"
    private const val HELD_ID = 7_431
    private const val HELD_CHANNEL = "traffic-holds"

    /**
     * Says what is held at a breakpoint, or takes the notice down when nothing is. It pops up: the
     * person is in the app that's waiting, not in this one.
     */
    fun held(context: Context, holds: List<Hold>) {
        val manager = context.getSystemService(NotificationManager::class.java)
        val first = holds.firstOrNull()
        if (first == null) {
            manager.cancel(HELD_ID)
            return
        }
        manager.createNotificationChannel(
            NotificationChannel(
                    HELD_CHANNEL,
                    "Held at a breakpoint",
                    NotificationManager.IMPORTANCE_HIGH,
                )
                .apply {
                    description = "A request or answer waiting to be let go, changed or stopped"
                }
        )
        val flow = first.flow
        val what = if (first is Hold.Response) "The answer to " else ""
        val more = if (holds.size > 1) " and ${holds.size - 1} more" else ""
        val go =
            PendingIntent.getService(
                context,
                HELD_ID,
                Intent(context, ProxyService::class.java)
                    .setAction(ProxyService.ACTION_GO)
                    .putExtra(ProxyService.EXTRA_HOLD, flow.id),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        val notification =
            Notification.Builder(context, HELD_CHANNEL)
                .setSmallIcon(R.drawable.sym_pause)
                .setContentTitle("Held at a breakpoint")
                .setContentText("$what${flow.request.method} ${flow.url}$more")
                .setContentIntent(openTool(context))
                .addAction(Notification.Action.Builder(null, "Let it go", go).build())
                .setOnlyAlertOnce(true)
                .setOngoing(true)
                .build()
        post(context, HELD_ID, notification)
    }

    fun builder(context: Context): Notification.Builder {
        context
            .getSystemService(NotificationManager::class.java)
            .createNotificationChannel(
                NotificationChannel(CHANNEL, "Network Traffic", NotificationManager.IMPORTANCE_LOW)
                    .apply { description = "The debugging proxy, while it runs" }
            )
        return Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.sym_travel_explore)
            .setContentIntent(openTool(context))
            .setOnlyAlertOnce(true)
    }

    /** Network Traffic, opened from a notice. */
    private fun openTool(context: Context): PendingIntent =
        PendingIntent.getActivity(
            context,
            ID,
            Intent(context, MainActivity::class.java)
                .putExtra(Launch.EXTRA_DESTINATION, Launch.DESTINATION_TOOL)
                .putExtra(Launch.EXTRA_TOOL, TrafficProxy.TOOL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    /** Says the setting was put back, so nobody wonders why their proxy came off. */
    fun released(context: Context) {
        val text =
            "Network Traffic had stopped and left it pointed at the proxy, so apps had no network."
        post(
            context,
            builder(context)
                .setContentTitle("The phone's proxy setting was put back")
                .setContentText(text)
                .setStyle(Notification.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .build(),
        )
    }

    fun post(context: Context, notification: Notification) = post(context, ID, notification)

    private fun post(context: Context, id: Int, notification: Notification) {
        try {
            context.getSystemService(NotificationManager::class.java).notify(id, notification)
        } catch (_: SecurityException) {
            // Notifications are off; the screen shows the same.
        }
    }
}
