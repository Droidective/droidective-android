package com.droidective.platform

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Process
import com.droidective.core.apps.CurrentActivity

/** Which app is in front, from usage access: Settings ▸ Usage access, which the person allows. */
object ForegroundApp {
    private const val LOOKBACK_MILLIS = 60 * 60 * 1_000L

    fun usageAccess(context: Context): Boolean {
        val ops = context.getSystemService(AppOpsManager::class.java)
        return ops.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName,
        ) == AppOpsManager.MODE_ALLOWED
    }

    /** The package whose activity resumed last, or null without usage access. */
    fun fromUsage(context: Context): String? = lastResumed(context, except = null)?.first

    /**
     * The activity that resumed last, as `package/.Activity`, or null without usage access. Pulling
     * down the notification shade doesn't pause the app under it, so from a tile that's the app the
     * person is looking at. [except] skips a package, such as this app's own.
     */
    fun activityFromUsage(context: Context, except: String? = null): String? =
        lastResumed(context, except)?.let { (pkg, cls) -> CurrentActivity.component(pkg, cls) }

    private fun lastResumed(context: Context, except: String?): Pair<String, String>? {
        if (!usageAccess(context)) return null
        val usage = context.getSystemService(UsageStatsManager::class.java)
        val now = System.currentTimeMillis()
        val events = usage.queryEvents(now - LOOKBACK_MILLIS, now)
        val event = UsageEvents.Event()
        var front: Pair<String, String>? = null
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (
                event.eventType == UsageEvents.Event.ACTIVITY_RESUMED && event.packageName != except
            ) {
                front = event.packageName to event.className
            }
        }
        return front
    }
}
