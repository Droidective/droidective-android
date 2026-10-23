package com.droidective.android.reactotron

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
import com.droidective.android.NoticeIds
import com.droidective.android.R
import com.droidective.android.surface.Launch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Keeps Reactotron listening while the person has it on. A React Native app reconnects whenever it
 * reloads, and a server that OxygenOS froze in the background would miss every one of them.
 */
class ReactotronService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ServiceCompat.startForeground(
            this,
            NoticeIds.REACTOTRON,
            notice(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            } else {
                0
            },
        )
        if (intent?.action == ACTION_STOP) {
            Reactotron.shutDown()
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        if (job == null) job = scope.launch { listen() }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        Reactotron.shutDown()
        super.onDestroy()
    }

    private suspend fun listen() {
        // Before an app connects: it is asked for the watched paths at once, and a state it
        // backs up needs somewhere to go.
        withContext(Dispatchers.IO) { Reactotron.load(this@ReactotronService) }
        if (!Reactotron.listen()) {
            // The screen says why; a notification for a server that isn't would not.
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        Reactotron.changes.collect {
            post(notice())
            delay(NOTIFY_GAP)
        }
    }

    private fun notice(): Notification {
        val apps = Reactotron.clients.value.values.map { it.name }.distinct()
        val text =
            when (apps.size) {
                0 -> "Waiting for an app on localhost:${port()}"
                1 -> "${apps.single()} connected"
                else -> "${apps.size} apps connected"
            }
        return builder(this)
            .setContentTitle("Reactotron is listening")
            .setContentText(text)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Stop", stopIntent()).build())
            .build()
    }

    private fun port(): Int =
        (Reactotron.state.value as? Reactotron.State.Listening)?.port ?: DEFAULT_PORT

    private fun post(notification: Notification) {
        try {
            getSystemService(NotificationManager::class.java)
                .notify(NoticeIds.REACTOTRON, notification)
        } catch (_: SecurityException) {
            // Notifications are off; the screen shows the same.
        }
    }

    private fun stopIntent(): PendingIntent =
        PendingIntent.getService(
            this,
            0,
            Intent(this, ReactotronService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )

    companion object {
        internal const val ACTION_STOP = "com.droidective.android.action.STOP_REACTOTRON"
        private const val CHANNEL = "reactotron"
        private const val DEFAULT_PORT = 9090

        /** How often the notification may change; it is a glance, not a feed. */
        private const val NOTIFY_GAP = 2_000L

        private fun builder(context: Context): Notification.Builder {
            context
                .getSystemService(NotificationManager::class.java)
                .createNotificationChannel(
                    NotificationChannel(CHANNEL, "Reactotron", NotificationManager.IMPORTANCE_LOW)
                        .apply { description = "Reactotron, while it listens" }
                )
            return Notification.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.sym_data_object)
                .setContentIntent(
                    PendingIntent.getActivity(
                        context,
                        NoticeIds.REACTOTRON,
                        Intent(context, MainActivity::class.java)
                            .putExtra(Launch.EXTRA_DESTINATION, Launch.DESTINATION_TOOL)
                            .putExtra(Launch.EXTRA_TOOL, Reactotron.TOOL),
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                    )
                )
                .setOnlyAlertOnce(true)
        }
    }
}
