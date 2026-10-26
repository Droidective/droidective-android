package com.droidective.android.screenrecord

import android.app.ForegroundServiceStartNotAllowedException
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.droidective.android.MainActivity
import com.droidective.android.NoticeIds
import com.droidective.android.R
import com.droidective.android.droidective
import com.droidective.android.surface.Launch
import com.droidective.android.surface.Surfaces
import com.droidective.core.device.PutBack
import com.droidective.core.device.PutBacks
import com.droidective.core.device.RecordOptions
import com.droidective.core.device.ScreenRecording
import com.droidective.core.device.SystemValues
import com.droidective.core.device.ValueTable
import com.droidective.core.shell.Parse
import com.droidective.core.shell.Shell
import com.droidective.core.shell.ShellCommand
import com.droidective.core.shell.ShellUnavailableException
import com.droidective.core.shell.Stoppable
import com.droidective.core.toggles.Toggles
import com.droidective.platform.SharedStorage
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Where a recording is: counting down, recording, saving, or finished one way or the other. */
sealed interface RecordRun {
    data class Counting(val left: Int) : RecordRun

    /** [since] is [SystemClock.elapsedRealtime]'s; [at] the wall clock, for the notification. */
    data class Recording(val since: Long, val at: Long) : RecordRun

    data object Saving : RecordRun

    data class Saved(val name: String, val uri: Uri, val seconds: Long, val bytes: Long) : RecordRun

    data class Failed(val message: String) : RecordRun
}

/** What to record with, and what to change on the phone while it records. */
data class RecordRequest(
    val options: RecordOptions,
    val showTaps: Boolean,
    val cleanStatusBar: Boolean,
    val delaySeconds: Int,
)

/** The one screen recording, which outlives the screen that started it. */
object ScreenRecorder {
    const val TOOL = "screen-record"

    internal val current = MutableStateFlow<RecordRun?>(null)
    val state: StateFlow<RecordRun?> = current.asStateFlow()

    internal var request: RecordRequest? = null

    val busy: Boolean
        get() =
            current.value.let {
                it is RecordRun.Counting || it is RecordRun.Recording || it is RecordRun.Saving
            }

    fun start(context: Context, request: RecordRequest) {
        if (busy) return
        this.request = request
        current.value = RecordRun.Counting(request.delaySeconds)
        try {
            ContextCompat.startForegroundService(context, service(context))
        } catch (e: IllegalStateException) {
            if (
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    e is ForegroundServiceStartNotAllowedException
            ) {
                current.value = RecordRun.Failed("Android wouldn't start the recorder just now.")
            } else {
                throw e
            }
        }
    }

    fun stop(context: Context) {
        // Said at once, so a tile reading it straight after sees it stopping.
        current.value =
            when (current.value) {
                is RecordRun.Recording -> RecordRun.Saving
                is RecordRun.Counting -> RecordRun.Failed("Stopped before it started.")
                else -> current.value
            }
        context.startService(service(context).setAction(ScreenRecordService.ACTION_STOP))
    }

    /** Counting down or recording: what a Record screen tile shows as on. */
    val recording: Boolean
        get() = current.value.let { it is RecordRun.Counting || it is RecordRun.Recording }

    /** The share sheet for a saved recording. */
    fun share(saved: RecordRun.Saved): Intent {
        val send =
            Intent(Intent.ACTION_SEND)
                .setType(MIME)
                .putExtra(Intent.EXTRA_STREAM, saved.uri)
                .putExtra(Intent.EXTRA_TITLE, saved.name)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        send.clipData = ClipData(saved.name, arrayOf(MIME), ClipData.Item(saved.uri))
        return Intent.createChooser(send, "Share the recording")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /** Plays a saved recording in the phone's video player. */
    fun play(saved: RecordRun.Saved): Intent =
        Intent(Intent.ACTION_VIEW)
            .setDataAndType(saved.uri, MIME)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)

    private fun service(context: Context) = Intent(context, ScreenRecordService::class.java)

    internal const val MIME = "video/mp4"
}

/**
 * Runs a recording in the foreground, which also keeps OxygenOS from freezing the app and holding
 * the notification's Stop until it thaws. Taps and the status bar go back the way they were.
 */
class ScreenRecordService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null
    @Volatile private var pid: Int? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification(ScreenRecorder.current.value ?: RecordRun.Counting(0)),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            } else {
                0
            },
        )
        if (intent?.action == ACTION_STOP) {
            stopRecording()
        } else if (job == null) {
            val request = ScreenRecorder.request ?: return START_NOT_STICKY.also { stopSelf() }
            job = scope.launch { finish(record(request)) }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        // A recording outliving its service would run to its time limit unseen.
        stopRecording()
        scope.cancel()
        super.onDestroy()
    }

    private fun stopRecording() {
        val running = pid
        if (running == null) {
            // Still counting down: nothing is recording yet, so there's nothing to save.
            if (job != null) {
                job?.cancel()
                finish(RecordRun.Failed("Stopped before it started."))
            }
            return
        }
        droidective.scope.launch {
            try {
                droidective.access.shell()?.run(ScreenRecording.stop(running))
            } catch (_: ShellUnavailableException) {
                // The shell is gone, and screenrecord with it.
            }
        }
    }

    private suspend fun record(request: RecordRequest): RecordRun {
        val shell = droidective.access.shell() ?: return RecordRun.Failed("Shell isn't running.")
        val folder =
            SharedStorage.handover(this) ?: return RecordRun.Failed("Shared storage isn't there.")
        for (left in request.delaySeconds downTo 1) {
            show(RecordRun.Counting(left))
            delay(SECOND)
        }
        val restore = changeForRecording(shell, request)
        val file = File(folder, ScreenRecording.fileName(System.currentTimeMillis()))
        val started = SystemClock.elapsedRealtime()
        val said =
            try {
                capture(shell, request.options, file)
            } finally {
                restore()
            }
        val seconds = (SystemClock.elapsedRealtime() - started) / SECOND
        return save(shell, file, seconds, said)
    }

    /** Runs screenrecord until it exits, and returns the last thing it said. */
    private suspend fun capture(shell: Shell, options: RecordOptions, file: File): String? {
        var last: String? = null
        try {
            shell.stream(Stoppable.wrap(ScreenRecording.command(options, file.path))).collect { line
                ->
                val said = Stoppable.pid(line)
                if (said != null) {
                    pid = said
                    show(
                        RecordRun.Recording(
                            SystemClock.elapsedRealtime(),
                            System.currentTimeMillis(),
                        )
                    )
                } else if (line.isNotBlank()) {
                    last = line.trim()
                }
            }
        } catch (e: ShellUnavailableException) {
            last = e.message
        } finally {
            pid = null
        }
        return last
    }

    private suspend fun save(shell: Shell, file: File, seconds: Long, said: String?): RecordRun {
        show(RecordRun.Saving)
        if (file.length() == 0L) {
            file.delete()
            return RecordRun.Failed(said ?: "screenrecord ended without a recording.")
        }
        val readable = shell.run(ScreenRecording.readable(file.path))
        if (!readable.succeeded) {
            return RecordRun.Failed(
                Parse.failure(readable.stderr, readable.stdout) ?: "The recording stayed locked."
            )
        }
        val bytes = file.length()
        return try {
            SharedStorage.publish(this, file, ScreenRecorder.MIME, SharedStorage.Place.Movies)
                ?.let { RecordRun.Saved(file.name, it, seconds, bytes) }
                ?: RecordRun.Failed("Android didn't make room for it in Movies.")
        } catch (e: IOException) {
            RecordRun.Failed("It couldn't be saved to Movies: ${e.message}")
        }
    }

    /**
     * Shows taps and cleans the status bar if asked, and returns what puts them back. The two
     * settings go back to their exact values, unset included, not just to "off". How to undo them
     * is written down first ([PutBacks]): if the app is killed mid-recording, the next start undoes
     * them, and a recording started before then undoes them before reading what they were.
     */
    private suspend fun changeForRecording(
        shell: Shell,
        request: RecordRequest,
    ): suspend () -> Unit {
        val putBacks = droidective.putBacks
        putBacks.clear(PUT_BACK, shell, droidective.setToggle)
        val settings =
            listOfNotNull(
                SHOW_TAPS_SETTING.takeIf { request.showTaps },
                DEMO_ALLOWED_SETTING.takeIf { request.cleanStatusBar },
            )
        val before = settings.associateWith { read(shell, it) }
        val demo = Toggles.byId(DEMO_MODE)?.takeIf { request.cleanStatusBar }
        val demoWasOn = demo?.let { droidective.toggles.read(it) == ON } ?: true
        putBacks.hold(
            PutBack(
                PUT_BACK,
                "Show taps and the clean status bar from a recording",
                lines =
                    before.map { (key, value) ->
                        val table = tableOf(key)
                        (value?.let { SystemValues.put(table, key, it) }
                                ?: SystemValues.delete(table, key))
                            .line
                    },
                // Demo mode leaves first: with sysui_demo_allowed back to 0 it couldn't.
                toggles = if (demoWasOn) emptyMap() else mapOf(DEMO_MODE to OFF),
            )
        )
        if (request.showTaps)
            run(shell, SystemValues.put(ValueTable.System, SHOW_TAPS_SETTING, "1"))
        if (!demoWasOn) droidective.toggles.apply(demo, ON)
        return { putBacks.clear(PUT_BACK, shell, droidective.setToggle) }
    }

    private fun tableOf(key: String): ValueTable =
        if (key == SHOW_TAPS_SETTING) ValueTable.System else ValueTable.Global

    private suspend fun read(shell: Shell, key: String): String? {
        val table = tableOf(key)
        return try {
            shell
                .run(SystemValues.get(table, key))
                .takeIf { it.succeeded }
                ?.let { SystemValues.current(table, it.stdout) }
        } catch (_: ShellUnavailableException) {
            null
        }
    }

    private suspend fun run(shell: Shell, command: ShellCommand) {
        try {
            shell.run(command)
        } catch (_: ShellUnavailableException) {
            // Nothing to put back through: the shell went with the recording.
        }
    }

    private fun show(run: RecordRun) {
        val started =
            run is RecordRun.Recording && ScreenRecorder.current.value !is RecordRun.Recording
        ScreenRecorder.current.value = run
        notify(notification(run))
        // A Record screen tile or widget slot shows whether a recording is on.
        if (started) Surfaces.refreshBehind(this)
    }

    private fun finish(run: RecordRun) {
        ScreenRecorder.current.value = run
        Surfaces.refreshBehind(this)
        job = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_DETACH)
        notify(notification(run))
        stopSelf()
    }

    private fun notify(notification: Notification) {
        try {
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
        } catch (_: SecurityException) {
            // Notifications are off; the screen shows the same.
        }
    }

    private fun notification(run: RecordRun): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Screen recording", NotificationManager.IMPORTANCE_LOW)
                .apply { description = "A screen recording in progress, and where it was saved" }
        )
        val stop =
            PendingIntent.getService(
                this,
                0,
                Intent(this, ScreenRecordService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE,
            )
        val open =
            PendingIntent.getActivity(
                this,
                NOTIFICATION_ID,
                Intent(this, MainActivity::class.java)
                    .putExtra(Launch.EXTRA_DESTINATION, Launch.DESTINATION_TOOL)
                    .putExtra(Launch.EXTRA_TOOL, ScreenRecorder.TOOL),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        val builder =
            Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.sym_videocam)
                .setContentIntent(open)
                .setOnlyAlertOnce(true)
        when (run) {
            is RecordRun.Counting ->
                builder
                    .setContentTitle("Recording in ${run.left} s")
                    .setContentText("Open what you want to record")
                    .setOngoing(true)
                    .addAction(Notification.Action.Builder(null, "Cancel", stop).build())
            is RecordRun.Recording ->
                builder
                    .setContentTitle("Recording the screen")
                    .setContentText("Tap Stop to save it")
                    .setUsesChronometer(true)
                    .setWhen(run.at)
                    .setOngoing(true)
                    .setContentIntent(stop)
                    .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            RecordRun.Saving -> builder.setContentTitle("Saving the recording").setOngoing(true)
            is RecordRun.Saved -> {
                val play =
                    PendingIntent.getActivity(
                        this,
                        1,
                        ScreenRecorder.play(run),
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                    )
                val share =
                    PendingIntent.getActivity(
                        this,
                        2,
                        ScreenRecorder.share(run),
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                    )
                builder
                    .setContentTitle("Recording saved")
                    .setContentText("Movies/Droidective/${run.name}")
                    .setContentIntent(play)
                    .addAction(Notification.Action.Builder(null, "Share", share).build())
                    .setAutoCancel(true)
            }
            is RecordRun.Failed ->
                builder
                    .setContentTitle("The recording didn't save")
                    .setContentText(run.message)
                    .setAutoCancel(true)
        }
        return builder.build()
    }

    companion object {
        internal const val ACTION_STOP = "com.droidective.android.action.STOP_RECORDING"
        private const val CHANNEL = "screen-record"
        private const val NOTIFICATION_ID = NoticeIds.SCREEN_RECORD
        private const val SECOND = 1_000L
        private const val SHOW_TAPS_SETTING = "show_touches"
        private const val DEMO_ALLOWED_SETTING = "sysui_demo_allowed"
        private const val DEMO_MODE = "demo-mode"
        private const val PUT_BACK = "screen-record"
        private const val ON = "on"
        private const val OFF = "off"
    }
}
