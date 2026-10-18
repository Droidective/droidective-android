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
    private val allowed =
        setOf(
            "com.droidective.android.MainActivity",
            // Guarded by INTERACT_ACROSS_USERS_FULL, which only the system holds.
            "rikka.shizuku.ShizukuProvider",
        )

    @Test
    fun onlyAllowedComponentsAreExported() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val flags =
            PackageManager.GET_ACTIVITIES or
                PackageManager.GET_SERVICES or
                PackageManager.GET_RECEIVERS or
                PackageManager.GET_PROVIDERS
        val info = context.packageManager.getPackageInfo(context.packageName, flags)
        val components: List<ComponentInfo> =
            listOfNotNull(info.activities, info.services, info.receivers, info.providers).flatMap {
                it.toList()
            }
        assertEquals(allowed, components.filter { it.exported }.map { it.name }.toSet())
    }
}
