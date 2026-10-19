package com.droidective.platform

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.DropBoxManager
import androidx.core.content.ContextCompat
import com.droidective.core.Grants
import com.droidective.core.crashes.CrashReport

/**
 * Crash reports from Android's DropBox, where the system files every app crash, native crash and
 * ANR. Hearing about one as it happens needs a receiver registered at runtime (spike 9: Android
 * skips manifest receivers for this broadcast), so [listen] is for a running service.
 */
class CrashLog(private val context: Context) {
    /** What stands between the app and reading crash reports. */
    enum class Access {
        Ready,

        /** The development permission Android files reports under, which only `pm grant` gives. */
        NeedsGrant,

        /** Usage access, which the person turns on in Settings ▸ Usage access. */
        NeedsUsageAccess,
    }

    private val dropBox = context.getSystemService(DropBoxManager::class.java)

    fun access(): Access =
        when {
            context.checkSelfPermission(Grants.crashReports(Build.VERSION.SDK_INT)) !=
                PackageManager.PERMISSION_GRANTED -> Access.NeedsGrant
            !ForegroundApp.usageAccess(context) -> Access.NeedsUsageAccess
            else -> Access.Ready
        }

    /**
     * Up to [limit] of the newest reports, newest first. DropBox keeps entries for a few days and
     * at most a thousand of them, whatever their tag.
     */
    fun recent(limit: Int = RECENT_LIMIT): List<CrashReport> {
        if (access() != Access.Ready) return emptyList()
        val newest = ArrayDeque<CrashReport>()
        var after = 0L
        while (true) {
            val entry = next(null, after) ?: break
            entry.use {
                after = it.timeMillis
                if (it.tag in CrashReport.tags) {
                    report(it)?.let { report ->
                        newest.addFirst(report)
                        if (newest.size > limit) newest.removeLast()
                    }
                }
            }
        }
        return newest.toList()
    }

    /** The report DropBox filed under [tag] at [timeMillis], or null when it isn't a crash. */
    fun read(tag: String, timeMillis: Long): CrashReport? {
        if (tag !in CrashReport.tags) return null
        return next(tag, timeMillis - 1)?.use {
            if (it.timeMillis == timeMillis) report(it) else null
        }
    }

    /**
     * Calls [onCrash] for each report filed from now on, until the returned stop is called. Runs
     * the read on the main thread; an entry is read only up to [MAX_ENTRY_BYTES].
     */
    fun listen(onCrash: (CrashReport) -> Unit): () -> Unit {
        val receiver =
            object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    val tag = intent.getStringExtra(DropBoxManager.EXTRA_TAG) ?: return
                    val time = intent.getLongExtra(DropBoxManager.EXTRA_TIME, 0)
                    read(tag, time)?.let(onCrash)
                }
            }
        // The broadcast comes from the system, which a not-exported receiver still hears.
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(DropBoxManager.ACTION_DROPBOX_ENTRY_ADDED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        return { runCatching { context.unregisterReceiver(receiver) } }
    }

    /** The next entry after [after], or null at the end or when Android refuses the read. */
    private fun next(tag: String?, after: Long): DropBoxManager.Entry? =
        try {
            dropBox.getNextEntry(tag, after)
        } catch (_: SecurityException) {
            null
        }

    private fun report(entry: DropBoxManager.Entry): CrashReport? =
        entry.getText(MAX_ENTRY_BYTES)?.let { CrashReport.parse(entry.tag, entry.timeMillis, it) }

    companion object {
        /** Enough for any stack trace and the top of an ANR's thread dump. */
        const val MAX_ENTRY_BYTES = 64 * 1024
        const val RECENT_LIMIT = 100
    }
}
