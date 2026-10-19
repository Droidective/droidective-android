package com.droidective.android.crashes

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], application = Application::class)
class OwnCrashesTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val dir = File(context.filesDir, "own-crashes-test")
    private val crashes = OwnCrashes(dir)
    private val original = Thread.getDefaultUncaughtExceptionHandler()

    @After
    fun restore() {
        Thread.setDefaultUncaughtExceptionHandler(original)
        dir.deleteRecursively()
    }

    @Test
    fun recordsTheCrashThenLetsAndroidHandleIt() {
        var handed: Throwable? = null
        crashes.install { _, error -> handed = error }
        val boom = IllegalStateException("boom")

        Thread.getDefaultUncaughtExceptionHandler()!!.uncaughtException(
            Thread.currentThread(),
            boom,
        )

        assertSame(boom, handed, "Android's handler still runs")
        val report = crashes.recent().single().text
        assertTrue(report.startsWith("Droidective "), report)
        assertTrue("java.lang.IllegalStateException: boom" in report, report)
        assertTrue("Thread: ${Thread.currentThread().name}" in report, report)
    }

    @Test
    fun keepsTheNewestFive() {
        (1L..7L).forEach {
            crashes.record(Thread.currentThread(), RuntimeException("crash $it"), it)
        }
        assertEquals(listOf(7L, 6L, 5L, 4L, 3L), crashes.recent().map { it.timeMillis })
        assertEquals(OwnCrashes.KEEP, dir.listFiles()!!.size)
    }

    @Test
    fun clearForgetsThemAndNothingRecordedIsEmpty() {
        assertEquals(emptyList(), crashes.recent())
        crashes.record(Thread.currentThread(), RuntimeException("x"), 1)
        crashes.clear()
        assertEquals(emptyList(), crashes.recent())
    }
}
