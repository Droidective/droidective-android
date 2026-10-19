package com.droidective.android

import android.app.Application
import android.content.pm.ComponentInfo
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.test.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Every component other apps can reach. Remote v2's companion kept its attack surface small by
 * being small; in one app the same limit is this list, checked against the merged manifest. Adding
 * an exported component means adding it here, in review.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], application = Application::class)
class ManifestTest {
    /** Quick Settings must bind these, and only the system holds BIND_QUICK_SETTINGS_TILE. */
    private val tileServices =
        (1..12).map { "com.droidective.android.surface.TileSlot$it" } +
            listOf(
                    "LayoutBounds",
                    "ShowTaps",
                    "AnimationScale",
                    "DemoMode",
                    "WirelessDebugging",
                    "NetworkSpeed",
                    "CurrentActivity",
                )
                .map { "com.droidective.android.surface.${it}Tile" }

    private val allowed =
        setOf(
            "com.droidective.android.MainActivity",
            // The launcher starts it when a bar is added or reconfigured.
            "com.droidective.android.widget.BarConfigureActivity",
            // The launcher sends each widget kind APPWIDGET_UPDATE.
            "com.droidective.android.widget.ToggleBarReceiver",
            "com.droidective.android.widget.ButtonWidgetReceiver",
            "com.droidective.android.widget.ListWidgetReceiver",
            // Boot, update and clock broadcasts, which only the system can send.
            "com.droidective.android.automation.AutomationSystemReceiver",
            // Run from the Mac: WRITE_SECURE_SETTINGS, and a shell check in code.
            "com.droidective.android.RunAutomation",
            // Bluetooth connect and disconnect, which only the system sends.
            "com.droidective.android.automation.BluetoothSignalReceiver",
            // Guarded by INTERACT_ACROSS_USERS_FULL, which only the system holds.
            "rikka.shizuku.ShizukuProvider",
        ) + tileServices

    /**
     * What libraries export, each behind a permission only the system (or adb, for DUMP) holds. A
     * library update that adds an export, or drops its permission, fails here.
     */
    private val libraryExports =
        mapOf(
            "androidx.glance.appwidget.GlanceRemoteViewsService" to
                "android.permission.BIND_REMOTEVIEWS",
            "androidx.work.impl.background.systemjob.SystemJobService" to
                "android.permission.BIND_JOB_SERVICE",
            "androidx.work.impl.diagnostics.DiagnosticsReceiver" to "android.permission.DUMP",
            "androidx.profileinstaller.ProfileInstallReceiver" to "android.permission.DUMP",
        )

    private fun exported(): List<ComponentInfo> {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val flags =
            PackageManager.GET_ACTIVITIES or
                PackageManager.GET_SERVICES or
                PackageManager.GET_RECEIVERS or
                PackageManager.GET_PROVIDERS
        val info = context.packageManager.getPackageInfo(context.packageName, flags)
        return listOfNotNull(info.activities, info.services, info.receivers, info.providers)
            .flatMap { it.toList() }
            .filter { it.exported }
    }

    @Test
    fun onlyAllowedComponentsAreExported() {
        assertEquals(allowed + libraryExports.keys, exported().map { it.name }.toSet())
    }

    @Test
    fun everyExportedServiceIsGuardedByASystemPermission() {
        exported().filterIsInstance<android.content.pm.ServiceInfo>().forEach {
            val expected = libraryExports[it.name] ?: "android.permission.BIND_QUICK_SETTINGS_TILE"
            assertEquals(expected, it.permission, it.name)
        }
    }

    @Test
    fun runFromTheMacNeedsWhatOnlyTheShellHas() {
        val receiver =
            exported().first { it.name == "com.droidective.android.RunAutomation" }
                as android.content.pm.ActivityInfo
        assertEquals("android.permission.WRITE_SECURE_SETTINGS", receiver.permission)
    }

    @Test
    fun libraryReceiversKeepTheirPermission() {
        exported()
            .filterIsInstance<android.content.pm.ActivityInfo>()
            .filter { it.name in libraryExports }
            .forEach { assertEquals(libraryExports[it.name], it.permission, it.name) }
    }
}
