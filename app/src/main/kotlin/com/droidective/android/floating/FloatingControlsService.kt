package com.droidective.android.floating

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
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Toast
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.view.isInvisible
import androidx.core.view.isVisible
import com.droidective.android.R
import com.droidective.android.droidective
import com.droidective.android.screenrecord.ScreenRecorder
import com.droidective.android.surface.ActivityLookup
import com.droidective.android.surface.BackgroundTaps
import com.droidective.android.surface.CurrentActivities
import com.droidective.android.surface.Surfaces
import com.droidective.android.ui.Symbols
import com.droidective.core.layout.Tap
import com.droidective.core.toggles.FloatingControls
import com.droidective.core.toggles.Toggles
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * The capture controls over every other app: a bubble to drag anywhere, which opens to a column of
 * buttons — screenshots, a recording, a views capture, the activity on screen, the debugging proxy.
 * They get out of whatever they capture. Their notification takes them off the screen.
 */
class FloatingControlsService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var root: LinearLayout? = null
    private var panel: LinearLayout? = null
    private val buttons = mutableMapOf<String, ImageView>()

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
        if (root == null) {
            root = show()
            running.value = true
            Surfaces.refreshBehind(this)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        root?.let { runCatching { getSystemService(WindowManager::class.java).removeView(it) } }
        root = null
        running.value = false
        Surfaces.refreshBehind(this)
        super.onDestroy()
    }

    // The bubble's touches are its drag; a tap still goes through performClick.
    @SuppressLint("ClickableViewAccessibility")
    private fun show(): LinearLayout {
        val density = resources.displayMetrics.density
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
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
                    x = prefs.getInt(KEY_X, (START_DP * density).toInt())
                    y = prefs.getInt(KEY_Y, (TOP_DP * density).toInt())
                }
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val bubble = button("picture_in_picture", "Floating controls", BUBBLE_DP, density)
        bubble.setOnTouchListener(Dragging(column, params, density))
        bubble.setOnClickListener { toggle() }
        column.addView(bubble)
        val buttonsColumn =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                isVisible = false
            }
        FloatingControls.controls.forEach { control ->
            val toggle = Toggles.byId(control.toggleId) ?: return@forEach
            val view = button(toggle.icon, toggle.title, BUTTON_DP, density)
            view.setOnClickListener { run(control) }
            buttons[control.toggleId] = view
            buttonsColumn.addView(view)
        }
        buttonsColumn.addView(
            button("layers", "Current activity", BUTTON_DP, density).apply {
                setOnClickListener { currentActivity() }
            }
        )
        buttonsColumn.addView(
            button("close", "Take the controls off the screen", BUTTON_DP, density).apply {
                setOnClickListener { stop(this@FloatingControlsService) }
            }
        )
        column.addView(buttonsColumn)
        panel = buttonsColumn
        getSystemService(WindowManager::class.java).addView(column, params)
        return column
    }

    private fun button(symbol: String, label: String, sizeDp: Int, density: Float): ImageView {
        val size = (sizeDp * density).toInt()
        val pad = (PADDING_DP * density).toInt()
        return ImageView(this).apply {
            setImageResource(Symbols.byName.getValue(symbol).outlined)
            contentDescription = label
            setPadding(pad, pad, pad, pad)
            background =
                GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(BACKGROUND)
                }
            layoutParams =
                LinearLayout.LayoutParams(size, size).apply {
                    topMargin = (GAP_DP * density).toInt()
                }
        }
    }

    /** Opens the column, showing which of its toggles are on right now. */
    private fun toggle() {
        val column = panel ?: return
        column.isVisible = !column.isVisible
        if (column.isVisible) scope.launch { drawStates() }
    }

    private suspend fun drawStates() {
        FloatingControls.controls.forEach { control ->
            val toggle = Toggles.byId(control.toggleId) ?: return@forEach
            val symbol = Symbols.byName.getValue(toggle.icon)
            val on = droidective.toggles.read(toggle) == ON
            buttons[control.toggleId]?.setImageResource(if (on) symbol.filled else symbol.outlined)
        }
    }

    private fun run(control: FloatingControls.Control) {
        val toggle = Toggles.byId(control.toggleId) ?: return
        when (val hide = control.hide) {
            FloatingControls.Hide.Never -> Unit
            is FloatingControls.Hide.For -> {
                hide()
                scope.launch {
                    delay(hide.millis)
                    reveal()
                }
            }
            FloatingControls.Hide.WhileOn -> {
                hide()
                scope.launch {
                    // Starting takes a moment to say so; then wait for the recording to be saved.
                    delay(START_GRACE_MILLIS)
                    ScreenRecorder.state.first { !ScreenRecorder.busy }
                    reveal()
                }
            }
        }
        scope.launch {
            BackgroundTaps.run(this@FloatingControlsService, Tap.RunToggle(toggle.id))
            drawStates()
        }
    }

    private fun currentActivity() {
        scope.launch {
            val message =
                when (val found = CurrentActivities.find(this@FloatingControlsService)) {
                    is ActivityLookup.Found -> {
                        CurrentActivities.copy(this@FloatingControlsService, found.component)
                        "Copied ${found.component}"
                    }
                    ActivityLookup.NoAccess -> "Current activity needs Usage access or Shell."
                    is ActivityLookup.Failed -> found.message
                }
            Toast.makeText(this@FloatingControlsService, message, Toast.LENGTH_LONG).show()
        }
    }

    private fun hide() {
        root?.isInvisible = true
    }

    /** Back on screen, saying what changed while away: a recording that has ended, say. */
    private fun reveal() {
        root?.isInvisible = false
        scope.launch { drawStates() }
    }

    /** Moves the controls with a finger, remembering where they were left; a still touch taps. */
    private inner class Dragging(
        private val window: View,
        private val params: WindowManager.LayoutParams,
        density: Float,
    ) : View.OnTouchListener {
        private val slop = SLOP_DP * density
        private var startX = 0f
        private var startY = 0f
        private var fromX = 0
        private var fromY = 0
        private var moved = false

        override fun onTouch(v: View, event: MotionEvent): Boolean {
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
                        getSystemService(WindowManager::class.java).updateViewLayout(window, params)
                    }
                }
                MotionEvent.ACTION_UP ->
                    if (moved) {
                        getSharedPreferences(PREFS, MODE_PRIVATE).edit {
                            putInt(KEY_X, params.x)
                            putInt(KEY_Y, params.y)
                        }
                    } else {
                        v.performClick()
                    }
            }
            return true
        }
    }

    private fun notification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Floating controls", NotificationManager.IMPORTANCE_LOW)
                .apply { description = "Shown while the floating controls are on screen" }
        )
        val stop =
            PendingIntent.getService(
                this,
                0,
                Intent(this, FloatingControlsService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE,
            )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.sym_picture_in_picture)
            .setContentTitle("Floating controls are on")
            .setContentText("Tap to take them off the screen")
            .setContentIntent(stop)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL = "floating-controls"
        private const val NOTIFICATION_ID = 7_440
        private const val ACTION_STOP = "com.droidective.android.action.STOP_FLOATING_CONTROLS"
        private const val PREFS = "floating-controls"
        private const val KEY_X = "x"
        private const val KEY_Y = "y"
        private const val ON = "on"
        private const val BUBBLE_DP = 48
        private const val BUTTON_DP = 44
        private const val PADDING_DP = 10
        private const val GAP_DP = 6
        private const val START_DP = 12
        private const val TOP_DP = 200
        private const val SLOP_DP = 6
        private const val START_GRACE_MILLIS = 1_000L
        private const val BACKGROUND = 0xCC1E1E1E.toInt()

        private val running = MutableStateFlow(false)

        /** Whether the controls are on screen. */
        val isRunning: StateFlow<Boolean> = running.asStateFlow()

        fun start(context: Context) {
            // On at once for whoever reads it next; onDestroy turns it off if it can't show.
            running.value = true
            try {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, FloatingControlsService::class.java),
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
            context.stopService(Intent(context, FloatingControlsService::class.java))
        }
    }
}
