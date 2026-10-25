package com.droidective.android.crashes

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.sentry.Breadcrumb
import io.sentry.Sentry
import io.sentry.SentryEvent
import io.sentry.protocol.Message
import io.sentry.protocol.SentryException
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CrashReportingTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()

    @Test
    fun anExceptionsMessageIsScrubbedBeforeItIsSent() {
        val event =
            SentryEvent().apply {
                exceptions =
                    listOf(
                        SentryException().apply {
                            type = "IllegalArgumentException"
                            value = "Key \"com.google.android.apps.chrome.Main\" was already used."
                        }
                    )
            }

        val sent = CrashReporting.scrub(event)

        assertEquals("Key \"<name>\" was already used.", sent.exceptions!!.single().value)
        assertEquals("IllegalArgumentException", sent.exceptions!!.single().type)
    }

    @Test
    fun theEventsOwnMessageIsScrubbed() {
        val event =
            SentryEvent().apply {
                message =
                    Message().apply {
                        message = "Open %s failed"
                        params = listOf("/sdcard/Download/app.apk")
                        formatted = "Open /sdcard/Download/app.apk failed"
                    }
            }

        val sent = CrashReporting.scrub(event).message!!

        assertEquals("Open <path> failed", sent.formatted)
        assertEquals(listOf("<path>"), sent.params)
    }

    @Test
    fun aBreadcrumbsMessageAndTextDataAreScrubbed() {
        val breadcrumb =
            Breadcrumb().apply {
                message = "Connected to 192.168.1.4:5555"
                setData("host", "build.example.com")
                setData("attempt", 2)
            }

        val kept = CrashReporting.scrub(breadcrumb)

        assertEquals("Connected to <ip>", kept.message)
        assertEquals("<name>", kept.getData("host"))
        assertEquals(2, kept.getData("attempt"))
    }

    @Test
    fun theSwitchStartsOnAndRemembersOff() {
        assertTrue(CrashReporting.enabled(app))

        CrashReporting.setEnabled(app, false)

        assertFalse(CrashReporting.enabled(app))
    }

    @Test
    fun aBuildWithoutADsnNeverStartsTheSdk() {
        assertFalse(CrashReporting.available)

        CrashReporting.start(app)

        assertFalse(Sentry.isEnabled())
    }
}
