package com.droidective.android.performance

import android.annotation.SuppressLint
import android.app.ForegroundServiceStartNotAllowedException
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.TextView
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.droidective.android.NoticeIds
import com.droidective.android.R
import com.droidective.android.crashes.CrashAlerts
import com.droidective.android.droidective
import com.droidective.android.surface.Launch
import com.droidective.android.surface.Surfaces
import com.droidective.core.apps.AppActions
import com.droidective.core.device.Performance
import com.droidective.core.device.PerformanceHud
import com.droidective.core.device.PerformanceTracker
import com.droidective.core.layout.Target
import com.droidective.core.shell.ShellUnavailableException
import com.droidective.platform.ForegroundApp
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The Performance Monitor's numbers over every other app: CPU, the frames of the app in front,
 * memory and the hottest part, once a second while the screen is on. Drag it anywhere; a tap opens
 * the Performance Monitor. Its notification stops it.
 */
class PerformanceOverlayService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var view: TextView? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            } else {
                0
            },
        )
        if (intent?.action == ACTION_STOP || !Settings.canDrawOverlays(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (view == null) {
            view = show()
            running.value = true
            Surfaces.refreshBehind(this)
            scope.launch { measure() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        view?.let { runCatching { getSystemService(WindowManager::class.java).removeView(it) } }
        view = null
        running.value = false
        Surfaces.refreshBehind(this)
        super.onDestroy()
    }

    private suspend fun measure() {
        val tracker = PerformanceTracker()
        val power = getSystemService(PowerManager::class.java)
        var watching: String? = null
        while (scope.isActive) {
            if (!power.isInteractive) {
                delay(TICK_MILLIS)
                continue
            }
            val front = ForegroundApp.fromUsage(this)?.takeIf(AppActions::isPackageName)
            if (front != watching) {
                tracker.reset()
                watching = front
            }
            val output = sample(front)
            val reading = output?.let {
                val sample = withContext(Dispatchers.Default) { Performance.parse(it) }
                tracker.next(sample, SystemClock.elapsedRealtime())
            }
            val lines =
                when {
                    output == null -> listOf("Performance needs Shell")
                    else ->
                        PerformanceHud.lines(reading, front?.let { CrashAlerts.label(this, it) })
                }
            view?.text = lines.joinToString("\n")
            delay(TICK_MILLIS)
        }
    }

    private suspend fun sample(packageName: String?): String? {
        val shell = droidective.access.shell() ?: return null
        return try {
            shell.run(Performance.sample(packageName)).takeIf { it.succeeded }?.stdout
        } catch (_: ShellUnavailableException) {
            null
        }
    }

    // Dragging handles the touches, and a tap still goes through performClick.
    @SuppressLint("ClickableViewAccessibility")
    private fun show(): TextView {
        val density = resources.displayMetrics.density
        val text =
            TextView(this).apply {
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, TEXT_SP)
                typeface = Typeface.MONOSPACE
                text = PerformanceHud.lines(null, null).joinToString("\n")
                val pad = (PADDING_DP * density).toInt()
                setPadding(pad, pad, pad, pad)
                background =
                    GradientDrawable().apply {
                        cornerRadius = CORNER_DP * density
                        setColor(BACKGROUND)
                    }
                contentDescription = "Performance overlay"
            }
        val params =
            WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                    PixelFormat.TRANSLUCENT,
                )
                .apply {
                    gravity = Gravity.TOP or Gravity.START
                    x = (START_DP * density).toInt()
                    y = (TOP_DP * density).toInt()
                }
        text.setOnTouchListener(Dragging(params, density))
        text.setOnClickListener {
            Launch.intentFor(this, Target.Tool(TOOL))?.let { runCatching { startActivity(it) } }
        }
        getSystemService(WindowManager::class.java).addView(text, params)
        return text
    }

    /** Moves the overlay with a finger; a touch that hardly moves is a tap. */
    private inner class Dragging(
        private val params: WindowManager.LayoutParams,
        density: Float,
    ) : android.view.View.OnTouchListener {
        private val slop = SLOP_DP * density
        private var startX = 0f
        private var startY = 0f
        private var fromX = 0
        private var fromY = 0
        private var moved = false

        override fun onTouch(v: android.view.View, event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = event.rawX
                    startY = event.rawY
                    fromX = params.x
                    fromY = params.y
                    moved = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - startX
                    val dy = event.rawY - startY
                    if (abs(dx) > slop || abs(dy) > slop) moved = true
                    if (moved) {
                        params.x = fromX + dx.toInt()
                        params.y = fromY + dy.toInt()
                        getSystemService(WindowManager::class.java).updateViewLayout(v, params)
                    }
                }
                MotionEvent.ACTION_UP -> if (!moved) v.performClick()
            }
            return true
        }
    }

    private fun notification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Performance overlay", NotificationManager.IMPORTANCE_LOW)
                .apply { description = "Shown while the performance overlay is on screen" }
        )
        val stop =
            PendingIntent.getService(
                this,
                0,
                Intent(this, PerformanceOverlayService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE,
            )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.sym_monitor_heart)
            .setContentTitle("Performance overlay is on")
            .setContentText("Tap to take it off the screen")
            .setContentIntent(stop)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL = "performance-overlay"
        private const val NOTIFICATION_ID = NoticeIds.PERFORMANCE
        private const val ACTION_STOP = "com.droidective.android.action.STOP_OVERLAY"
        private const val TOOL = "performance"
        private const val TICK_MILLIS = 1_000L
        private const val TEXT_SP = 11f
        private const val PADDING_DP = 8
        private const val CORNER_DP = 10f
        private const val START_DP = 12
        private const val TOP_DP = 96
        private const val SLOP_DP = 6
        private const val BACKGROUND = 0xCC000000.toInt()

        private val running = MutableStateFlow(false)

        /** Whether the overlay is on screen. */
        val isRunning: StateFlow<Boolean> = running.asStateFlow()

        fun start(context: Context) {
            // On at once for whoever reads it next; onDestroy turns it off if it can't show.
            running.value = true
            try {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, PerformanceOverlayService::class.java),
                )
            } catch (e: IllegalStateException) {
                running.value = false
                if (
                    Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                        e !is ForegroundServiceStartNotAllowedException
                ) {
                    throw e
                }
            }
        }

        fun stop(context: Context) {
            running.value = false
            context.stopService(Intent(context, PerformanceOverlayService::class.java))
        }
    }
}
