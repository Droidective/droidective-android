package com.droidective.android.bugreport

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import com.droidective.android.MainActivity
import com.droidective.android.R
import com.droidective.android.droidective
import com.droidective.android.surface.Launch
import com.droidective.core.device.BugReport
import com.droidective.core.device.BugReportLine
import com.droidective.core.shell.Parse
import com.droidective.core.shell.ShellUnavailableException
import com.droidective.platform.SharedStorage
import com.droidective.platform.SharedStorage.Place.Downloads
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Where taking a bug report is. */
sealed interface BugReportRun {
    /** [done] of [of], or null before bugreportz has said how far it is. */
    data class Gathering(val done: Int?, val of: Int?) : BugReportRun

    data class Saved(val name: String, val uri: Uri) : BugReportRun

    data class Failed(val message: String) : BugReportRun
}

/**
 * The one bug report being taken, held by the app: it takes a minute or more, and leaving the
 * screen shouldn't lose it. Its progress and its end ride in a notification.
 */
object BugReports {
    private const val CHANNEL = "bug-report"
    private const val NOTIFICATION_ID = 7_402
    const val TOOL = "bug-report"

    private val current = MutableStateFlow<BugReportRun?>(null)
    val state: StateFlow<BugReportRun?> = current.asStateFlow()

    private var job: Job? = null

    val running: Boolean
        get() = job != null

    fun start(context: Context) {
        if (job != null) return
        val starting = BugReportRun.Gathering(null, null)
        current.value = starting
        notify(context, starting)
        job =
            context.droidective.scope.launch {
                var end: BugReportRun = BugReportRun.Failed("It was stopped.")
                try {
                    end = take(context)
                } finally {
                    job = null
                    current.value = end
                    notify(context, end)
                }
            }
    }

    private suspend fun take(context: Context): BugReportRun {
        val shell =
            context.droidective.access.shell() ?: return BugReportRun.Failed("Shell isn't running.")
        var zip: String? = null
        var failed: String? = null
        try {
            shell.stream(BugReport.command).collect { line ->
                when (val read = BugReport.parse(line)) {
                    is BugReportLine.Progress ->
                        if (zip == null) {
                            val gathering = BugReportRun.Gathering(read.done, read.of)
                            current.value = gathering
                            notify(context, gathering)
                        }
                    is BugReportLine.Done -> zip = read.path
                    is BugReportLine.Failed -> failed = read.reason
                    is BugReportLine.Begin,
                    null -> Unit
                }
            }
        } catch (e: ShellUnavailableException) {
            return BugReportRun.Failed(e.message ?: "The shell stopped responding.")
        }
        failed?.let {
            return BugReportRun.Failed(it)
        }
        val path = zip ?: return BugReportRun.Failed("bugreportz ended without a report.")
        val folder =
            SharedStorage.handover(context)
                ?: return BugReportRun.Failed("Shared storage isn't available.")
        val handOver = shell.run(BugReport.handOver(path, folder.path))
        if (!handOver.succeeded) {
            return BugReportRun.Failed(
                Parse.failure(handOver.stderr, handOver.stdout) ?: "The shell couldn't copy it."
            )
        }
        val name = BugReport.fileName(path)
        return try {
            SharedStorage.publish(context, File(folder, name), "application/zip", Downloads)?.let {
                BugReportRun.Saved(name, it)
            } ?: BugReportRun.Failed("Android didn't make room for it in Downloads.")
        } catch (e: IOException) {
            BugReportRun.Failed("It couldn't be saved to Downloads: ${e.message}")
        }
    }

    /** The share sheet for a saved report, titled with its name rather than its MediaStore id. */
    fun share(saved: BugReportRun.Saved): Intent {
        val uri = saved.uri
        val send =
            Intent(Intent.ACTION_SEND)
                .setType("application/zip")
                .putExtra(Intent.EXTRA_STREAM, uri)
                .putExtra(Intent.EXTRA_TITLE, saved.name)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        send.clipData = ClipData(saved.name, arrayOf("application/zip"), ClipData.Item(uri))
        return Intent.createChooser(send, "Share the bug report")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    private fun notify(context: Context, run: BugReportRun) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Bug reports", NotificationManager.IMPORTANCE_LOW).apply {
                description = "A bug report being taken, and where it was saved"
            }
        )
        val open =
            PendingIntent.getActivity(
                context,
                NOTIFICATION_ID,
                Intent(context, MainActivity::class.java)
                    .putExtra(Launch.EXTRA_DESTINATION, Launch.DESTINATION_TOOL)
                    .putExtra(Launch.EXTRA_TOOL, TOOL),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        val builder =
            NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.sym_bug_report)
                .setContentIntent(open)
                .setOnlyAlertOnce(true)
        when (run) {
            is BugReportRun.Gathering ->
                builder
                    .setContentTitle("Taking a bug report")
                    .setContentText("It takes a minute or two")
                    .setProgress(run.of ?: 0, run.done ?: 0, run.of == null)
                    .setOngoing(true)
            is BugReportRun.Saved ->
                builder
                    .setContentTitle("Bug report saved")
                    .setContentText("Download/${run.name}")
                    .setAutoCancel(true)
            is BugReportRun.Failed ->
                builder
                    .setContentTitle("The bug report didn't finish")
                    .setContentText(run.message)
                    .setAutoCancel(true)
        }
        try {
            manager.notify(NOTIFICATION_ID, builder.build())
        } catch (_: SecurityException) {
            // Notifications are off; the screen shows the same.
        }
    }
}
