package com.droidective.core.apps

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Against `dumpsys notification --noredact` from an API 36 emulator (fixtures/notifications/). */
class NotificationInspectorTest {
    private val dump =
        File(System.getProperty("fixtures.dir"), "notifications/dumpsys-notification-api36.txt")
            .readText()

    @Test
    fun readsAnAppsNotificationWithItsActions() {
        val overlay = NotificationInspector.posted(dump, "com.droidective.android").single()
        assertEquals(7310, overlay.id)
        assertNull(overlay.tag)
        assertEquals("performance-overlay", overlay.channel)
        assertEquals(2, overlay.importance)
        assertEquals(listOf("ONGOING_EVENT", "NO_CLEAR", "FOREGROUND_SERVICE"), overlay.flags)
        assertTrue(overlay.ongoing)
        assertEquals("Performance overlay is on", overlay.title)
        assertEquals("Tap to take it off the screen", overlay.text)
        assertEquals(listOf("Stop"), overlay.actions)
        assertEquals(1792462537957, overlay.postedAt)
    }

    @Test
    fun readsOnlyTheAppAskedAbout() {
        val system = NotificationInspector.posted(dump, "android")
        assertEquals(3, system.size)
        assertEquals(emptyList(), NotificationInspector.posted(dump, "com.example.none"))
        val alertWindow = system.first()
        assertEquals(
            "com.android.server.wm.AlertWindowNotification - com.droidective.android",
            alertWindow.channel,
            "a channel with spaces in it",
        )
        assertEquals(alertWindow.channel, alertWindow.tag)
        val lock = system[1]
        assertEquals("Set a screen lock", lock.title)
        assertFalse(lock.ongoing)
    }

    @Test
    fun readsTheChannelsAndWhetherTheAppIsBlocked() {
        val settings = NotificationInspector.settings(dump, "com.droidective.android")
        assertFalse(settings.blocked)
        assertTrue(
            ChannelSettings("performance-overlay", "Performance overlay", 2, false) in
                settings.channels
        )
        assertEquals(6, settings.channels.size)
        assertEquals("ZEN_MODE_OFF", settings.doNotDisturb)
        assertNull(NotificationInspector.doNotDisturb(settings.doNotDisturb))
        assertTrue(NotificationInspector.settings(dump, "com.google.android.apps.photos").blocked)
    }

    @Test
    fun namesImportanceAndDndTheWaySettingsDoes() {
        assertEquals("Off", NotificationInspector.importance(0))
        assertEquals("High: sound and a heads-up", NotificationInspector.importance(4))
        assertEquals("Unspecified", NotificationInspector.importance(-1000))
        assertEquals(
            "Do Not Disturb is on: priority only",
            NotificationInspector.doNotDisturb("ZEN_MODE_IMPORTANT_INTERRUPTIONS"),
        )
    }
}
