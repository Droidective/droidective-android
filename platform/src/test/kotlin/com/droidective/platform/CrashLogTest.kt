package com.droidective.platform

import android.app.AppOpsManager
import android.app.Application
import android.os.DropBoxManager
import android.os.Process
import androidx.test.core.app.ApplicationProvider
import com.droidective.core.Grants
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDropBoxManager

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class CrashLogTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val dropBox = shadowOf(context.getSystemService(DropBoxManager::class.java))
    private val log = CrashLog(context)

    @After fun clear() = ShadowDropBoxManager.reset()

    private fun allow(grant: Boolean = true, usage: Boolean = true) {
        if (grant) shadowOf(context).grantPermissions(Grants.READ_DROPBOX_DATA)
        else shadowOf(context).denyPermissions(Grants.READ_DROPBOX_DATA)
        shadowOf(context.getSystemService(AppOpsManager::class.java))
            .setMode(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName,
                if (usage) AppOpsManager.MODE_ALLOWED else AppOpsManager.MODE_IGNORED,
            )
    }

    /** The first entry of a recorded `dumpsys dropbox --print` fixture, as getText returns it. */
    private fun fixture(file: String): String =
        File(System.getProperty("fixtures.dir"), "dropbox/$file")
            .readText()
            .split("========================================\n")[1]
            .substringAfter('\n')

    private fun add(tag: String, time: Long, text: String) =
        dropBox.addData(tag, time, text.encodeToByteArray())

    @Test
    fun saysWhatsMissingInTheOrderToFixIt() {
        allow(grant = false, usage = false)
        assertEquals(CrashLog.Access.NeedsGrant, log.access())
        allow(grant = true, usage = false)
        assertEquals(CrashLog.Access.NeedsUsageAccess, log.access())
        allow()
        assertEquals(CrashLog.Access.Ready, log.access())
    }

    @Test
    fun listsCrashesNewestFirstAndSkipsOtherTags() {
        allow()
        add("data_app_crash", 1_000, fixture("data_app_crash.txt"))
        add("system_server_wtf", 2_000, "Process: system_server\n\nwhat a terrible failure")
        add("data_app_native_crash", 3_000, fixture("data_app_native_crash.txt"))
        add("data_app_anr", 4_000, fixture("data_app_anr.txt"))

        val recent = log.recent()
        assertEquals(listOf(4_000L, 3_000L, 1_000L), recent.map { it.timeMillis })
        assertEquals(
            listOf("data_app_anr", "data_app_native_crash", "data_app_crash"),
            recent.map { it.tag },
        )
        assertEquals(listOf(4_000L, 3_000L), log.recent(limit = 2).map { it.timeMillis })
    }

    @Test
    fun readsOneReportBackByTagAndTime() {
        allow()
        add("data_app_crash", 1_000, fixture("data_app_crash.txt"))
        add("data_app_crash", 2_000, fixture("system_app_crash.txt"))

        assertEquals("com.droidective.android", log.read("data_app_crash", 1_000)?.packageName)
        assertEquals(2_000, log.read("data_app_crash", 2_000)?.timeMillis)
        assertNull(log.read("data_app_crash", 1_500), "no entry at that time")
        assertNull(log.read("system_server_wtf", 1_000), "not a crash tag")
    }

    @Test
    fun readsNothingWithoutAccess() {
        allow(usage = false)
        add("data_app_crash", 1_000, fixture("data_app_crash.txt"))
        assertEquals(emptyList(), log.recent())
    }
}
