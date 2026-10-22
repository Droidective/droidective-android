package com.droidective.android.update

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.droidective.android.BuildConfig
import com.droidective.android.NoticeIds
import com.droidective.android.R
import com.droidective.core.update.GitHubReleases
import com.droidective.core.update.Latest
import com.droidective.core.update.UpdatePolicy
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** What the last update check found. */
sealed interface UpdateStatus {
    data object NeverChecked : UpdateStatus

    data class UpToDate(val at: Long) : UpdateStatus

    /** GitHub has no release yet, or the repo isn't public. */
    data class NoRelease(val at: Long) : UpdateStatus

    data class Available(val at: Long, val version: String, val page: String) : UpdateStatus

    data class Failed(val at: Long, val reason: String) : UpdateStatus
}

/**
 * Update checks, since no store delivers this app's updates. A daily background check posts one
 * notification per new release; Setup shows the last result and checks on demand. Nothing is
 * downloaded: a newer release opens its GitHub page, where Android's own installer takes over.
 */
object Updates {
    private const val PREFS = "updates"
    private const val WORK = "update-check"
    private const val CHANNEL = "updates"
    private const val NOTIFICATION_ID = NoticeIds.UPDATE
    private const val KEY_AUTOMATIC = "automatic"
    private const val KEY_KIND = "kind"
    private const val KEY_AT = "at"
    private const val KEY_VERSION = "version"
    private const val KEY_PAGE = "page"
    private const val KEY_REASON = "reason"
    private const val KEY_NOTIFIED = "notified-tag"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun automatic(context: Context): Boolean = prefs(context).getBoolean(KEY_AUTOMATIC, true)

    fun setAutomatic(context: Context, on: Boolean) {
        prefs(context).edit { putBoolean(KEY_AUTOMATIC, on) }
        schedule(context)
    }

    /** Keeps the daily check queued while it's on; runs at every app start. */
    fun schedule(context: Context) {
        val work = WorkManager.getInstance(context)
        if (!automatic(context)) {
            work.cancelUniqueWork(WORK)
            return
        }
        val request =
            PeriodicWorkRequestBuilder<UpdateCheckWorker>(
                    UpdatePolicy.INTERVAL_MILLIS,
                    TimeUnit.MILLISECONDS,
                )
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                )
                .build()
        work.enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    fun status(context: Context): UpdateStatus {
        val prefs = prefs(context)
        val at = prefs.getLong(KEY_AT, 0)
        return when (prefs.getString(KEY_KIND, null)) {
            "up-to-date" -> UpdateStatus.UpToDate(at)
            "no-release" -> UpdateStatus.NoRelease(at)
            "available" ->
                UpdateStatus.Available(
                    at,
                    prefs.getString(KEY_VERSION, null).orEmpty(),
                    prefs.getString(KEY_PAGE, null).orEmpty(),
                )
            "failed" -> UpdateStatus.Failed(at, prefs.getString(KEY_REASON, null).orEmpty())
            else -> UpdateStatus.NeverChecked
        }
    }

    /**
     * Asks GitHub now. [fromBackground] posts a notification for a release nobody has been told
     * about; a check someone started shows its answer on screen, which counts as telling them.
     *
     * @param endpoint the releases API; tests point it at a local server.
     */
    suspend fun check(
        context: Context,
        fromBackground: Boolean,
        installed: String = BuildConfig.VERSION_NAME,
        endpoint: String = GitHubReleases.LATEST,
    ): UpdateStatus {
        val latest = withContext(Dispatchers.IO) { GitHubReleases(installed, endpoint).latest() }
        val now = System.currentTimeMillis()
        val status =
            when (latest) {
                Latest.None -> UpdateStatus.NoRelease(now)
                is Latest.Failed -> UpdateStatus.Failed(now, latest.reason)
                is Latest.Found ->
                    if (UpdatePolicy.isNewer(installed, latest.release))
                        UpdateStatus.Available(
                            now,
                            latest.release.version.toString(),
                            latest.release.page,
                        )
                    else UpdateStatus.UpToDate(now)
            }
        record(context, status)
        if (latest is Latest.Found) {
            val notified = prefs(context).getString(KEY_NOTIFIED, null)
            if (UpdatePolicy.shouldNotify(installed, latest.release, notified)) {
                if (fromBackground && status is UpdateStatus.Available) notify(context, status)
                prefs(context).edit { putString(KEY_NOTIFIED, latest.release.tag) }
            }
        }
        return status
    }

    /** Opens a release's GitHub page in the browser. */
    fun releasePage(page: String): Intent =
        Intent(Intent.ACTION_VIEW, page.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    private fun record(context: Context, status: UpdateStatus) {
        prefs(context).edit {
            when (status) {
                UpdateStatus.NeverChecked -> remove(KEY_KIND)
                is UpdateStatus.UpToDate ->
                    putString(KEY_KIND, "up-to-date").putLong(KEY_AT, status.at)
                is UpdateStatus.NoRelease ->
                    putString(KEY_KIND, "no-release").putLong(KEY_AT, status.at)
                is UpdateStatus.Available ->
                    putString(KEY_KIND, "available")
                        .putLong(KEY_AT, status.at)
                        .putString(KEY_VERSION, status.version)
                        .putString(KEY_PAGE, status.page)
                is UpdateStatus.Failed ->
                    putString(KEY_KIND, "failed")
                        .putLong(KEY_AT, status.at)
                        .putString(KEY_REASON, status.reason)
            }
        }
    }

    private fun notify(context: Context, available: UpdateStatus.Available) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Updates", NotificationManager.IMPORTANCE_LOW).apply {
                description = "A new version of Droidective is on GitHub"
            }
        )
        val open =
            PendingIntent.getActivity(
                context,
                NOTIFICATION_ID,
                releasePage(available.page),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        val notification =
            Notification.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.sym_download)
                .setContentTitle("Droidective ${available.version} is available")
                .setContentText("Tap to open the release on GitHub.")
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
        try {
            manager.notify(NOTIFICATION_ID, notification)
        } catch (_: SecurityException) {
            // Notifications are off for the app; Setup still shows the release.
        }
    }
}

/** The daily background check. A failure waits for the next day rather than retrying sooner. */
class UpdateCheckWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        Updates.check(applicationContext, fromBackground = true)
        return Result.success()
    }
}
