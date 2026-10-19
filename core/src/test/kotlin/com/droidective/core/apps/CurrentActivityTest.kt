package com.droidective.core.apps

import com.droidective.core.shell.Parse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CurrentActivityTest {
    @Test
    fun readsTheActivityInFrontFromDumpsys() {
        // Recorded from the API 36 emulator with Settings open.
        val api36 =
            "ACTIVITY MANAGER ACTIVITIES (dumpsys activity activities)\r\n" +
                "    topResumedActivity=ActivityRecord{27517519 u0 " +
                "com.android.settings/.homepage.SettingsHomepageActivity t151}\r\n"
        assertEquals(
            "com.android.settings/.homepage.SettingsHomepageActivity",
            Parse.resumedActivity(api36),
        )
        assertEquals(
            "com.example.app.debug/com.example.Main\$Inner",
            Parse.resumedActivity(
                "    mResumedActivity: ActivityRecord{c3a1 u0 com.example.app.debug/" +
                    "com.example.Main\$Inner t4}"
            ),
        )
        assertNull(Parse.resumedActivity("Display #0 (activities from top to bottom):"))
        assertNull(Parse.resumedActivity(""))
    }

    @Test
    fun writesTheShortFormAmStartTakes() {
        assertEquals(
            "com.android.settings/.homepage.SettingsHomepageActivity",
            CurrentActivity.component(
                "com.android.settings",
                "com.android.settings.homepage.SettingsHomepageActivity",
            ),
        )
        assertEquals(
            "com.example.app.debug/com.example.Main",
            CurrentActivity.component("com.example.app.debug", "com.example.Main"),
        )
        // A package that only shares a prefix keeps the full class name.
        assertEquals(
            "com.example/com.examplex.Main",
            CurrentActivity.component("com.example", "com.examplex.Main"),
        )
    }

    @Test
    fun readsThroughDumpsysActivities() {
        assertEquals("dumpsys activity activities", CurrentActivity.command.line)
    }
}
