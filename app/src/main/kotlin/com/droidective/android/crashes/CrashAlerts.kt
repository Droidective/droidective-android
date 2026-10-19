package com.droidective.android.crashes

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.edit
import com.droidective.android.MainActivity
import com.droidective.android.R
import com.droidective.android.automation.AutomationService
import com.droidective.android.surface.Launch
import com.droidective.core.crashes.CrashReport
import kotlinx.coroutines.flow.MutableStateFlow

/** A crash to open in Crash Catcher: where DropBox filed it. */
data class CrashRef(val tag: String, val timeMillis: Long)

/**
 * The apps Crash Catcher alerts about, and the notification it posts when one crashes or stops
 * responding. Hearing about a crash needs a running listener, so the automation service runs while
 * any app is watched.
 */
object CrashAlerts {
    const val TOOL = "crash-catcher"
    const val EXTRA_TAG = "com.droidective.android.extra.CRASH_TAG"
    const val EXTRA_TIME = "com.droidective.android.extra.CRASH_TIME"
    private const val PREFS = "crash-alerts"
    private const val KEY_WATCHED = "watched"
    private const val CHANNEL = "crashes"

    /** The crash Crash Catcher shows when it opens next, set by tapping an alert. */
    val focus = MutableStateFlow<CrashRef?>(null)

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun watched(context: Context): Set<String> =
        prefs(context).getStringSet(KEY_WATCHED, null).orEmpty().toSet()

    fun setWatched(context: Context, packageName: String, on: Boolean) {
        val now = watched(context).let { if (on) it + packageName else it - packageName }
        prefs(context).edit { putStringSet(KEY_WATCHED, now) }
        AutomationService.sync(context)
    }

    /** A report the listener read: alerts when it's an app being watched. */
    fun onCrash(context: Context, report: CrashReport) {
        if (report.packageName in watched(context)) notify(context, report)
    }

    /** Opens Crash Catcher on [report]. */
    fun openIntent(context: Context, report: CrashReport): Intent =
        Intent(context, MainActivity::class.java)
            .putExtra(Launch.EXTRA_DESTINATION, Launch.DESTINATION_TOOL)
            .putExtra(Launch.EXTRA_TOOL, TOOL)
            .putExtra(EXTRA_TAG, report.tag)
            .putExtra(EXTRA_TIME, report.timeMillis)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /**
     * The crash an alert's tap names. Any app can start the activity this arrives at, so a forged
     * intent can do no more than pick a crash report to show.
     */
    fun refFrom(intent: Intent): CrashRef? {
        if (intent.getStringExtra(Launch.EXTRA_TOOL) != TOOL) return null
        val tag = intent.getStringExtra(EXTRA_TAG)?.takeIf { it in CrashReport.tags } ?: return null
        val time = intent.getLongExtra(EXTRA_TIME, 0).takeIf { it > 0 } ?: return null
        return CrashRef(tag, time)
    }

    /** "Settings crashed", "Maps stopped responding": an app's label and what happened to it. */
    fun title(context: Context, report: CrashReport): String =
        "${label(context, report.packageName)} " +
            report.kind.title.replaceFirstChar { it.lowercase() }

    fun label(context: Context, packageName: String): String =
        try {
            val pm = context.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
        } catch (_: PackageManager.NameNotFoundException) {
            packageName
        }

    private fun notify(context: Context, report: CrashReport) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Crash alerts", NotificationManager.IMPORTANCE_HIGH)
                .apply { description = "When an app Crash Catcher watches crashes" }
        )
        val open =
            PendingIntent.getActivity(
                context,
                report.packageName.hashCode(),
                openIntent(context, report),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        val notification =
            Notification.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.sym_bug_report)
                .setContentTitle(title(context, report))
                .setContentText(report.headline)
                .setStyle(Notification.BigTextStyle().bigText(report.headline))
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
        try {
            // One alert per app: a crash loop replaces the last one instead of piling up.
            manager.notify("crash:${report.packageName}", 1, notification)
        } catch (_: SecurityException) {
            // Notifications are off for the app; Crash Catcher still lists the crash.
        }
    }
}
