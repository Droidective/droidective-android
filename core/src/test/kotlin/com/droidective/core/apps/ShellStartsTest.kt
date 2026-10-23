package com.droidective.core.apps

import com.droidective.core.shell.Parse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class ShellStartsTest {
    @Test
    fun everyValueIsQuoted() {
        assertEquals(
            "am start -n 'com.google.android.apps.maps/com.google.android.maps.MapsActivity'",
            ShellStarts.activity(
                    "com.google.android.apps.maps",
                    "com.google.android.maps.MapsActivity",
                )
                .line,
        )
        assertEquals(
            "am start -a android.intent.action.VIEW -d 'myapp://onboarding?step=1&x='\\''y'\\'''",
            ShellStarts.link("myapp://onboarding?step=1&x='y'").line,
        )
        assertEquals(
            "am start -a 'android.settings.WIFI_SETTINGS'",
            ShellStarts.settingsScreen("android.settings.WIFI_SETTINGS").line,
        )
        assertEquals(
            "cmd deviceidle tempwhitelist -d 10000 'com.droidective.android'",
            ShellStarts.allowForegroundService("com.droidective.android").line,
        )
    }

    @Test
    fun anythingThatIsntWhatItSaysIsRefused() {
        assertFailsWith<IllegalArgumentException> { ShellStarts.activity("com.x", "A; reboot") }
        assertFailsWith<IllegalArgumentException> { ShellStarts.activity("x'; reboot", "A") }
        assertFailsWith<IllegalArgumentException> { ShellStarts.link("not a link") }
        assertFailsWith<IllegalArgumentException> { ShellStarts.settingsScreen("a b") }
        assertFailsWith<IllegalArgumentException> {
            ShellStarts.allowForegroundService("x; reboot")
        }
    }

    @Test
    fun readsTheWifiNetworkFromCmdWifiStatus() {
        // API 36 emulator output, trimmed.
        val api36 =
            "Wifi is enabled\r\nWifi scanning is only available when wifi is enabled\r\n" +
                "==== Primary ClientModeManager instance ====\r\n" +
                "Wifi is connected to \"AndroidWifi\"\r\n" +
                "WifiInfo: SSID: \"AndroidWifi\", BSSID: 00:00:00:00:00:00, Security type: 0\r\n"
        assertEquals("AndroidWifi", Parse.wifiSsid(api36))
        assertEquals(
            "Office 5G, 2nd floor",
            Parse.wifiSsid("WifiInfo: SSID: \"Office 5G, 2nd floor\", BSSID: 00:00:00:00:00:00"),
        )
        assertNull(Parse.wifiSsid("Wifi is enabled\nWifi is not connected\n"))
        assertNull(Parse.wifiSsid("WifiInfo: SSID: \"<unknown ssid>\", BSSID: <none>"))
        assertNull(Parse.wifiSsid(""))
    }

    @Test
    fun readsTheAppInFront() {
        val api36 =
            "ACTIVITY MANAGER ACTIVITIES (dumpsys activity activities)\r\n" +
                "  topResumedActivity=ActivityRecord{131160728 u0 com.droidective.android/.MainActivity t38}\r\n"
        assertEquals("com.droidective.android", Parse.resumedPackage(api36))
        assertEquals(
            "com.example.app.debug",
            Parse.resumedPackage(
                "    mResumedActivity: ActivityRecord{c3a1 u0 com.example.app.debug/com.example.Main t4}"
            ),
        )
        assertNull(Parse.resumedPackage("Display #0 (activities from top to bottom):"))
    }
}
