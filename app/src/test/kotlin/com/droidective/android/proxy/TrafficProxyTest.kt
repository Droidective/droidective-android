package com.droidective.android.proxy

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.droidective.core.net.proxy.TrafficRules
import com.droidective.core.net.proxy.TrafficRulesRepository
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Pointing the phone at the proxy and back, through the Settings API: the path a phone with
 * WRITE_SECURE_SETTINGS granted takes, and the one where a mistake costs the phone its network.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], application = Application::class)
class TrafficProxyTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val resolver = context.contentResolver

    private var setting: String?
        get() = Settings.Global.getString(resolver, "http_proxy")
        set(value) {
            Settings.Global.putString(resolver, "http_proxy", value)
        }

    @Before
    fun grant() {
        shadowOf(context).grantPermissions(Manifest.permission.WRITE_SECURE_SETTINGS)
        context.getSharedPreferences("traffic-proxy", 0).edit().clear().commit()
    }

    @Test
    fun stoppingPutsBackTheProxyThatWasThere() = runTest {
        setting = "10.0.0.12:8888"

        assertTrue(TrafficProxy.point(context, here = true))
        assertEquals("127.0.0.1:8888", setting)
        assertTrue(TrafficProxy.pointing.value)

        assertTrue(TrafficProxy.point(context, here = false))
        assertEquals("10.0.0.12:8888", setting)
        assertFalse(TrafficProxy.pointing.value)
    }

    @Test
    fun stoppingWithNoProxyBeforeClearsItTheWayAndroidReads() = runTest {
        setting = null

        TrafficProxy.point(context, here = true)
        TrafficProxy.point(context, here = false)

        assertEquals(":0", setting)
    }

    @Test
    fun aSettingSomebodyChangedSinceIsLeftAlone() = runTest {
        setting = ":0"
        TrafficProxy.point(context, here = true)
        setting = "192.168.1.20:9090"

        assertTrue(TrafficProxy.point(context, here = false))
        assertEquals("192.168.1.20:9090", setting)
    }

    @Test
    fun pointingTwiceKeepsWhatWasThereTheFirstTime() = runTest {
        setting = "10.0.0.12:8888"

        TrafficProxy.point(context, here = true)
        TrafficProxy.point(context, here = true)
        TrafficProxy.point(context, here = false)

        assertEquals("10.0.0.12:8888", setting)
    }

    @Test
    fun aProcessStartedAgainKnowsTheSettingPointsHere() {
        // Nothing in memory says so: Android ended the process with the phone pointed here.
        setting = "127.0.0.1:8888"

        assertTrue(TrafficProxy.refreshPointing(context))
        assertTrue(TrafficProxy.pointing.value)
    }

    @Test
    fun aLeftoverFromARunThatNeverCleanedUpIsCleared() = runTest {
        // The process died pointed here and took what was there before with it.
        setting = "127.0.0.1:8888"

        assertTrue(TrafficProxy.point(context, here = false))
        assertEquals(":0", setting)
    }

    @Test
    fun aPhoneLeftPointedAtNothingGetsItsSettingBack() = runTest {
        // Rebooted, updated or force-stopped while it ran: the service never heard.
        setting = "127.0.0.1:8888"

        assertTrue(TrafficProxy.releaseIfStranded(context))
        assertEquals(":0", setting)
    }

    @Test
    fun aSettingThatPointsElsewhereIsNotStranded() = runTest {
        setting = "10.0.0.12:8888"

        assertFalse(TrafficProxy.releaseIfStranded(context))
        assertEquals("10.0.0.12:8888", setting)
    }

    @Test
    fun theNoticeSaysWhyTheProxyCameOff() {
        ProxyNotices.released(context)

        val posted =
            shadowOf(context.getSystemService(NotificationManager::class.java))
                .allNotifications
                .single()
        assertEquals(
            "The phone's proxy setting was put back",
            posted.extras.getString(Notification.EXTRA_TITLE),
        )
    }

    @Test
    fun theRulesChosenAreKept() {
        val rules =
            TrafficRules(apps = listOf("com.example.shop"), passThrough = listOf("a.example"))
        TrafficProxy.setRules(context, rules)

        assertEquals(rules, TrafficProxy.rules.value)
        assertEquals(rules, TrafficRulesRepository(context.filesDir).load())
        TrafficProxy.setRules(context, TrafficRules())
    }
}
