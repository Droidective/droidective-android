package com.droidective.android.uiinspect

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
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.droidective.android.MainActivity
import com.droidective.android.R
import com.droidective.android.crashes.CrashAlerts
import com.droidective.android.droidective
import com.droidective.android.surface.Launch
import com.droidective.core.device.UiHierarchy
import com.droidective.core.device.UiNode
import com.droidective.core.shell.Parse
import com.droidective.core.shell.ShellUnavailableException
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
import kotlinx.coroutines.withContext

/** Where a capture of the screen's views is. */
sealed interface UiCapture {
    data class Counting(val left: Int) : UiCapture

    data object Capturing : UiCapture

    /** [screenshot] is null when the dump worked and the picture didn't. */
    data class Captured(
        val nodes: List<UiNode>,
        val screenshot: File?,
        val app: String?,
        val at: Long,
    ) : UiCapture

    data class Failed(val message: String) : UiCapture
}

/** The latest capture, which outlives the screen that asked for it. */
object UiCaptures {
    const val TOOL = "ui-inspector"

    internal val current = MutableStateFlow<UiCapture?>(null)
    val state: StateFlow<UiCapture?> = current.asStateFlow()

    internal var delaySeconds = 0

    val busy: Boolean
        get() = current.value.let { it is UiCapture.Counting || it == UiCapture.Capturing }

    /** Waits [delaySeconds], so the person can open the app to inspect, then captures. */
    fun capture(context: Context, delaySeconds: Int) {
        if (busy) return
        this.delaySeconds = delaySeconds
        current.value = UiCapture.Counting(delaySeconds)
        try {
            ContextCompat.startForegroundService(
                context,
                Intent(context, UiCaptureService::class.java),
            )
        } catch (e: IllegalStateException) {
            if (
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    e is ForegroundServiceStartNotAllowedException
            ) {
                current.value = UiCapture.Failed("Android wouldn't start the capture just now.")
            } else {
                throw e
            }
        }
    }
}

/**
 * Runs one capture in the foreground: a countdown, the dump and the screenshot take a few seconds
 * with the app in the background, and OxygenOS freezes background apps after about five.
 */
class UiCaptureService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var running = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification(UiCaptures.current.value ?: UiCapture.Capturing),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            } else {
                0
            },
        )
        if (!running) {
            running = true
            scope.launch { finish(capture(UiCaptures.delaySeconds)) }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun capture(delaySeconds: Int): UiCapture {
        for (left in delaySeconds downTo 1) {
            show(UiCapture.Counting(left))
            delay(SECOND)
        }
        show(UiCapture.Capturing)
        val shell = droidective.access.shell() ?: return UiCapture.Failed("Shell isn't running.")
        return try {
            val dump = shell.run(UiHierarchy.dump)
            val nodes = withContext(Dispatchers.Default) { UiHierarchy.parse(dump.stdout) }
            if (!dump.succeeded || nodes.isEmpty()) {
                return UiCapture.Failed(
                    UiHierarchy.failure(dump.exitCode, dump.stderr.ifBlank { dump.stdout })
                )
            }
            val picture = SharedStorage.handover(this)?.let { File(it, SCREENSHOT) }
            val shot = picture?.let { shell.run(UiHierarchy.screenshot(it.path)) }
            val app = nodes.firstOrNull { it.packageName.isNotEmpty() }?.packageName
            UiCapture.Captured(
                nodes,
                picture?.takeIf { shot?.succeeded == true && it.length() > 0 },
                app,
                System.currentTimeMillis(),
            )
        } catch (e: ShellUnavailableException) {
            UiCapture.Failed(Parse.failure(e.message.orEmpty()) ?: "The shell stopped responding.")
        }
    }

    private fun show(capture: UiCapture) {
        UiCaptures.current.value = capture
        notify(notification(capture))
    }

    private fun finish(capture: UiCapture) {
        UiCaptures.current.value = capture
        running = false
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_DETACH)
        notify(notification(capture))
        stopSelf()
    }

    private fun notify(notification: Notification) {
        try {
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
        } catch (_: SecurityException) {
            // Notifications are off; the screen shows the same.
        }
    }

    private fun notification(capture: UiCapture): Notification {
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(
                NotificationChannel(CHANNEL, "UI Inspector", NotificationManager.IMPORTANCE_LOW)
                    .apply { description = "A capture of the screen's views, and when it's ready" }
            )
        val open =
            PendingIntent.getActivity(
                this,
                NOTIFICATION_ID,
                Intent(this, MainActivity::class.java)
                    .putExtra(Launch.EXTRA_DESTINATION, Launch.DESTINATION_TOOL)
                    .putExtra(Launch.EXTRA_TOOL, UiCaptures.TOOL),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        val builder =
            Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.sym_account_tree)
                .setContentIntent(open)
                .setOnlyAlertOnce(true)
        when (capture) {
            is UiCapture.Counting ->
                builder
                    .setContentTitle("Capturing the screen in ${capture.left} s")
                    .setContentText("Open the app to inspect")
                    .setOngoing(true)
            UiCapture.Capturing -> builder.setContentTitle("Capturing the screen…").setOngoing(true)
            is UiCapture.Captured ->
                builder
                    .setContentTitle("Views captured")
                    .setContentText(
                        "${capture.nodes.size} views" +
                            (capture.app?.let { " in ${CrashAlerts.label(this, it)}" } ?: "") +
                            ". Tap to inspect them."
                    )
                    .setAutoCancel(true)
            is UiCapture.Failed ->
                builder
                    .setContentTitle("The capture didn't work")
                    .setContentText(capture.message)
                    .setStyle(Notification.BigTextStyle().bigText(capture.message))
                    .setAutoCancel(true)
        }
        return builder.build()
    }

    private companion object {
        const val CHANNEL = "ui-inspector"
        const val NOTIFICATION_ID = 7_430
        const val SECOND = 1_000L
        const val SCREENSHOT = "ui-inspector.png"
    }
}
