package com.droidective.platform

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Process

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
    fun fromUsage(context: Context): String? {
        if (!usageAccess(context)) return null
        val usage = context.getSystemService(UsageStatsManager::class.java)
        val now = System.currentTimeMillis()
        val events = usage.queryEvents(now - LOOKBACK_MILLIS, now)
        val event = UsageEvents.Event()
        var front: String? = null
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED) front = event.packageName
        }
        return front
    }
}
