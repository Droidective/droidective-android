package com.droidective.android.screenshot

import android.app.ForegroundServiceStartNotAllowedException
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
import android.provider.Settings
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.droidective.android.MainActivity
import com.droidective.android.NoticeIds
import com.droidective.android.R
import com.droidective.android.droidective
import com.droidective.android.surface.Launch
import com.droidective.core.shell.Parse
import com.droidective.core.shell.ShellUnavailableException
import com.droidective.core.toggles.Screenshot
import com.droidective.platform.SharedStorage
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Where a screenshot to mark up is. */
sealed interface Shot {
    data class Counting(val left: Int) : Shot

    data object Taking : Shot

    data class Taken(val picture: File, val at: Long) : Shot

    data class Failed(val message: String) : Shot
}

/** The latest screenshot to mark up, which outlives the screen that asked for it. */
object Screenshots {
    const val TOOL = "screenshot"

    internal val current = MutableStateFlow<Shot?>(null)
    val state: StateFlow<Shot?> = current.asStateFlow()

    internal var delaySeconds = 0

    val busy: Boolean
        get() = current.value.let { it is Shot.Counting || it == Shot.Taking }

    /** Waits [delaySeconds], so the person can open what to capture, then takes it. */
    fun take(context: Context, delaySeconds: Int) {
        if (busy) return
        this.delaySeconds = delaySeconds
        current.value = Shot.Counting(delaySeconds)
        try {
            ContextCompat.startForegroundService(
                context,
                Intent(context, ScreenshotService::class.java),
            )
        } catch (e: IllegalStateException) {
            if (
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    e is ForegroundServiceStartNotAllowedException
            ) {
                current.value = Shot.Failed("Android wouldn't start the screenshot just now.")
            } else {
                throw e
            }
        }
    }

    /**
     * Lets go of the one being marked up, so the screen offers to take another. Its picture goes
     * too: a saved one was copied into Pictures, and one not saved was asked about first.
     */
    fun done() {
        if (busy) return
        (current.value as? Shot.Taken)?.picture?.delete()
        current.value = null
    }
}

/**
 * Takes one screenshot in the foreground: a countdown while the person opens what to capture, then
 * the shell's screencap into this app's own folder, where the editor can read it.
 */
class ScreenshotService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var running = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ServiceCompat.startForeground(
            this,
            NoticeIds.SCREENSHOT,
            notification(Screenshots.current.value ?: Shot.Taking),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            } else {
                0
            },
        )
        if (!running) {
            running = true
            scope.launch { finish(take(Screenshots.delaySeconds)) }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun take(delaySeconds: Int): Shot {
        for (left in delaySeconds downTo 1) {
            show(Shot.Counting(left))
            delay(SECOND)
        }
        show(Shot.Taking)
        val shell = droidective.access.shell() ?: return Shot.Failed("Shell isn't running.")
        val folder =
            SharedStorage.handover(this) ?: return Shot.Failed("There's no shared storage for it.")
        val now = System.currentTimeMillis()
        val picture = File(folder, "markup-$now.png")
        return try {
            val shot = shell.run(Screenshot.toFile(picture.path))
            if (shot.succeeded && picture.length() > 0) {
                Shot.Taken(picture, now)
            } else {
                Shot.Failed(Parse.failure(shot.stderr) ?: "screencap didn't save a picture.")
            }
        } catch (e: ShellUnavailableException) {
            Shot.Failed(Parse.failure(e.message.orEmpty()) ?: "The shell stopped responding.")
        }
    }

    private fun show(shot: Shot) {
        Screenshots.current.value = shot
        notify(notification(shot))
    }

    private fun finish(shot: Shot) {
        Screenshots.current.value = shot
        running = false
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_DETACH)
        notify(notification(shot))
        // Straight to the editor when Android lets an app open itself from the background, which
        // the floating controls' permission does; otherwise the notice is a tap away from it.
        if (shot is Shot.Taken && Settings.canDrawOverlays(this)) startActivity(editor())
        stopSelf()
    }

    private fun editor(): Intent =
        Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(Launch.EXTRA_DESTINATION, Launch.DESTINATION_TOOL)
            .putExtra(Launch.EXTRA_TOOL, Screenshots.TOOL)

    private fun notify(notification: Notification) {
        try {
            getSystemService(NotificationManager::class.java)
                .notify(NoticeIds.SCREENSHOT, notification)
        } catch (_: SecurityException) {
            // Notifications are off; the screen shows the same.
        }
    }

    private fun notification(shot: Shot): Notification {
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(
                NotificationChannel(CHANNEL, "Screenshot", NotificationManager.IMPORTANCE_LOW)
                    .apply { description = "A screenshot to mark up, and when it's ready" }
            )
        val open =
            PendingIntent.getActivity(
                this,
                NoticeIds.SCREENSHOT,
                editor(),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        val builder =
            Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.sym_screenshot)
                .setContentIntent(open)
                .setOnlyAlertOnce(true)
        when (shot) {
            is Shot.Counting ->
                builder
                    .setContentTitle("Screenshot in ${shot.left} s")
                    .setContentText("Open what to capture")
                    .setOngoing(true)
            Shot.Taking -> builder.setContentTitle("Taking the screenshot…").setOngoing(true)
            is Shot.Taken ->
                builder
                    .setContentTitle("Screenshot taken")
                    .setContentText("Tap to mark it up")
                    .setAutoCancel(true)
            is Shot.Failed ->
                builder
                    .setContentTitle("The screenshot didn't work")
                    .setContentText(shot.message)
                    .setStyle(Notification.BigTextStyle().bigText(shot.message))
                    .setAutoCancel(true)
        }
        return builder.build()
    }

    private companion object {
        const val CHANNEL = "screenshot"
        const val SECOND = 1_000L
    }
}
