package com.droidective.core.apps

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Against `am` output from an API 36 emulator (fixtures/intents/). */
class IntentsTest {
    private fun fixture(name: String) =
        File(System.getProperty("fixtures.dir"), "intents/$name").readText()

    @Test
    fun aColdStartIsTimedLikeAndroidStudioTimesIt() {
        val spec =
            IntentSpec(component = "com.android.settings/.Settings", wait = true, coldStart = true)
        assertEquals(
            "am start -W -S -n 'com.android.settings/.Settings'",
            Intents.command(spec).line,
        )
        val result = Intents.parse(fixture("start-cold-api36.txt"))
        assertTrue(result.ok)
        assertEquals("COLD", result.launchState)
        assertEquals(199, result.totalMillis)
        assertEquals(201, result.waitMillis)
        assertEquals("com.android.settings/.homepage.SettingsHomepageActivity", result.activity)
    }

    @Test
    fun everyValueIsQuotedAndInAmsOrder() {
        val spec =
            IntentSpec(
                action = "android.intent.action.VIEW",
                data = "myapp://item/42?ref=a&b=c",
                type = "text/plain",
                categories = listOf("android.intent.category.BROWSABLE"),
                flags = listOf(IntentFlag.NewTask, IntentFlag.ClearTop),
                extras =
                    listOf(
                        IntentExtra("name", ExtraType.Text, "it's"),
                        IntentExtra("count", ExtraType.Int, "3"),
                        IntentExtra("on", ExtraType.Bool, "true"),
                    ),
                packageName = "com.example.app",
                wait = false,
            )
        assertEquals(
            "am start -a 'android.intent.action.VIEW' -d 'myapp://item/42?ref=a&b=c' " +
                "-t 'text/plain' -c 'android.intent.category.BROWSABLE' --activity-new-task " +
                "--activity-clear-top --es 'name' 'it'\\''s' --ei 'count' '3' --ez 'on' 'true' " +
                "-p 'com.example.app'",
            Intents.command(spec).line,
        )
    }

    @Test
    fun broadcastsAndServicesNeverWaitOrStop() {
        val spec =
            IntentSpec(
                kind = IntentKind.Broadcast,
                action = "com.example.REFRESH",
                packageName = "com.example.app",
                wait = true,
                coldStart = true,
            )
        assertEquals(
            "am broadcast -a 'com.example.REFRESH' -p 'com.example.app'",
            Intents.command(spec).line,
        )
        assertEquals(
            "adb shell am startservice -n 'com.example.app/.Sync'",
            Intents.adb(IntentSpec(kind = IntentKind.Service, component = "com.example.app/.Sync")),
        )
        val delivered = Intents.parse(fixture("broadcast-api36.txt"))
        assertTrue(delivered.ok)
        assertEquals("Delivered, result=0", delivered.message)
    }

    @Test
    fun amsErrorsAreFailuresThoughItExitsZero() {
        val unresolved = Intents.parse(fixture("start-unresolved-api36.txt"))
        assertFalse(unresolved.ok)
        assertTrue(unresolved.message.startsWith("Activity not started, unable to resolve Intent"))
        val missing = Intents.parse(fixture("start-missing-api36.txt"))
        assertFalse(missing.ok)
        assertEquals(
            "Activity class {com.example.missing/com.example.missing.Main} does not exist.",
            missing.message,
        )
        val warm = Intents.parse(fixture("start-warm-api36.txt"))
        assertTrue(warm.ok, "an activity already on top is still a launch")
        assertEquals(0, warm.totalMillis)
        assertNull(Intents.parse("").totalMillis)
    }

    @Test
    fun saysWhatsWrongBeforeItRuns() {
        assertEquals("An intent needs an action, data or a component.", IntentSpec().problem)
        assertEquals(
            "A component is package/class, such as com.example/.MainActivity.",
            IntentSpec(component = "com.example").problem,
        )
        assertEquals(
            "count has to be a whole number, not \"three\".",
            IntentSpec(action = "a", extras = listOf(IntentExtra("count", ExtraType.Int, "three")))
                .problem,
        )
        assertEquals(
            "Every extra needs a key.",
            IntentSpec(action = "a", extras = listOf(IntentExtra("", ExtraType.Text, "x"))).problem,
        )
        assertFailsWith<IllegalArgumentException> { Intents.command(IntentSpec()) }
        assertTrue(Intents.isComponent("com.example/com.example.Main\$Inner"))
        assertFalse(Intents.isComponent("com.example/.Main; reboot"))
    }

    @Test
    fun savedIntentsSurviveARestartNewestFirst() {
        val dir = kotlin.io.path.createTempDirectory("intents").toFile()
        try {
            val saved = SavedIntents(dir)
            saved.save(
                SavedIntent("Refresh", IntentSpec(kind = IntentKind.Broadcast, action = "a"))
            )
            saved.save(SavedIntent("Open item", IntentSpec(data = "myapp://item/1")))
            saved.save(
                SavedIntent("Refresh", IntentSpec(kind = IntentKind.Broadcast, action = "b"))
            )
            val again = SavedIntents(dir).all()
            assertEquals(listOf("Refresh", "Open item"), again.map { it.name })
            assertEquals("b", again.first().spec.action)
            SavedIntents(dir).remove("Open item")
            assertEquals(listOf("Refresh"), SavedIntents(dir).all().map { it.name })
        } finally {
            dir.deleteRecursively()
        }
    }
}
