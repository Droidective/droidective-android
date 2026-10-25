package com.droidective.android.crashes

import android.content.Context
import androidx.core.content.edit
import com.droidective.android.BuildConfig
import com.droidective.core.crashes.CrashScrub
import io.sentry.Breadcrumb
import io.sentry.Sentry
import io.sentry.SentryEvent
import io.sentry.SentryOptions
import io.sentry.android.core.SentryAndroid

/**
 * Crash and freeze reports to Droidective's Sentry project. Only builds made with a DSN can send
 * (the release workflow's), and Setup has the switch. Every message and breadcrumb goes through
 * [CrashScrub] first, and the SDK's own collection stays at its defaults: no PII, screenshots, view
 * hierarchy or session replay, so nothing on screen (another app's traffic, files or data) leaves.
 * [OwnCrashes] is separate: the full report, kept on the phone.
 */
object CrashReporting {
    /** Whether this build carries a DSN at all; Setup shows the switch only then. */
    val available: Boolean
        get() = BuildConfig.SENTRY_DSN.isNotEmpty()

    fun enabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ON, true)

    /** Takes effect at once: off closes the SDK, which puts the crash handler it wrapped back. */
    fun setEnabled(context: Context, on: Boolean) {
        prefs(context).edit { putBoolean(KEY_ON, on) }
        if (on) start(context) else Sentry.close()
    }

    fun start(context: Context) {
        if (!available || !enabled(context) || Sentry.isEnabled()) return
        SentryAndroid.init(context) { options ->
            options.dsn = BuildConfig.SENTRY_DSN
            options.tracesSampleRate = TRACES_SAMPLE_RATE
            options.beforeSend = SentryOptions.BeforeSendCallback { event, _ -> scrub(event) }
            options.beforeBreadcrumb = SentryOptions.BeforeBreadcrumbCallback { breadcrumb, _ ->
                scrub(breadcrumb)
            }
        }
    }

    /** The exception messages and the event's message, scrubbed; stack frames are this app's. */
    fun scrub(event: SentryEvent): SentryEvent {
        event.exceptions?.forEach { exception ->
            exception.value = exception.value?.let(CrashScrub::text)
        }
        event.message?.let { message ->
            message.message = message.message?.let(CrashScrub::text)
            message.formatted = message.formatted?.let(CrashScrub::text)
            message.params = message.params?.map(CrashScrub::text)
        }
        return event
    }

    fun scrub(breadcrumb: Breadcrumb): Breadcrumb {
        breadcrumb.message = breadcrumb.message?.let(CrashScrub::text)
        for ((key, value) in breadcrumb.data.toMap()) {
            if (value is String) breadcrumb.setData(key, CrashScrub.text(value))
        }
        return breadcrumb
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private const val PREFS = "crash-reporting"
    private const val KEY_ON = "on"
    // As the Mac app samples.
    private const val TRACES_SAMPLE_RATE = 0.2
}
