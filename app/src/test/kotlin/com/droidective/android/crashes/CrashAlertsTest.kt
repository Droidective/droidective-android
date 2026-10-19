package com.droidective.android.crashes

import android.app.Application
import android.app.NotificationManager
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.droidective.android.surface.Launch
import com.droidective.core.crashes.CrashKind
import com.droidective.core.crashes.CrashReport
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], application = Application::class)
class CrashAlertsTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val notifications = context.getSystemService(NotificationManager::class.java)

    private fun report(packageName: String, kind: CrashKind = CrashKind.Crash) =
        CrashReport(
            kind = kind,
            tag = "data_app_crash",
            timeMillis = 1_792_384_026_296,
            packageName = packageName,
            versionName = "4.2",
            process = packageName,
            pid = 11211,
            foreground = false,
            headline = "java.lang.IllegalStateException: boom",
            details = "java.lang.IllegalStateException: boom\n\tat com.example.Main.run",
        )

    private fun prefs() = context.getSharedPreferences("crash-alerts", 0)

    private fun posted() = shadowOf(notifications).allNotifications

    @Test
    fun alertsOnlyForWatchedApps() {
        prefs().edit().putStringSet("watched", setOf("com.example.watched")).commit()

        CrashAlerts.onCrash(context, report("com.example.other"))
        assertEquals(0, posted().size)

        CrashAlerts.onCrash(context, report("com.example.watched"))
        val alert = posted().single()
        assertEquals("com.example.watched crashed", alert.extras.getString("android.title"))
        assertEquals(
            "java.lang.IllegalStateException: boom",
            alert.extras.getString("android.text"),
        )
    }

    @Test
    fun titlesSayWhatHappened() {
        assertEquals(
            "com.example.app crashed",
            CrashAlerts.title(context, report("com.example.app")),
        )
        assertEquals(
            "com.example.app stopped responding",
            CrashAlerts.title(context, report("com.example.app", CrashKind.Anr)),
        )
        assertEquals(
            "com.example.app crashed in native code",
            CrashAlerts.title(context, report("com.example.app", CrashKind.NativeCrash)),
        )
    }

    @Test
    fun anAlertsTapFindsItsReportAgain() {
        val intent = CrashAlerts.openIntent(context, report("com.example.app"))
        assertEquals(CrashRef("data_app_crash", 1_792_384_026_296), CrashAlerts.refFrom(intent))
    }

    @Test
    fun aForgedIntentPicksNoReport() {
        val base =
            Intent()
                .putExtra(Launch.EXTRA_TOOL, CrashAlerts.TOOL)
                .putExtra(CrashAlerts.EXTRA_TIME, 5L)
        assertNull(CrashAlerts.refFrom(Intent(base).putExtra(CrashAlerts.EXTRA_TAG, "netstats")))
        assertNull(CrashAlerts.refFrom(Intent(base)))
        assertNull(
            CrashAlerts.refFrom(
                Intent(base)
                    .putExtra(CrashAlerts.EXTRA_TAG, "data_app_crash")
                    .putExtra(Launch.EXTRA_TOOL, "apps")
            )
        )
        assertNull(
            CrashAlerts.refFrom(
                Intent(base)
                    .putExtra(CrashAlerts.EXTRA_TAG, "data_app_crash")
                    .putExtra(CrashAlerts.EXTRA_TIME, 0L)
            )
        )
    }
}
