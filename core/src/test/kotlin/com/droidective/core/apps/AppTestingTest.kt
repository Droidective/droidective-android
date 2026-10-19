package com.droidective.core.apps

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Monkey against an API 36 emulator's Clock (fixtures/monkey/clock-60-events.txt); the crash
 * fixture is Monkey's crash report, in AOSP's format, written out by hand.
 */
class AppTestingTest {
    private val dir = File(System.getProperty("fixtures.dir"), "monkey")

    @Test
    fun setsAnAppsLanguagesInOrderOrPutsItBack() {
        assertEquals(
            "cmd locale set-app-locales 'com.example.app' --locales 'ar-XB,en-US'",
            AppLocales.set("com.example.app", listOf("ar-XB", "en-US")).line,
        )
        assertEquals(
            "cmd locale set-app-locales 'com.example.app'",
            AppLocales.set("com.example.app", emptyList()).line,
        )
        assertFailsWith<IllegalArgumentException> {
            AppLocales.set("com.example.app", listOf("fr; reboot"))
        }
        assertFailsWith<IllegalArgumentException> { AppLocales.set("x y", listOf("fr")) }
    }

    @Test
    fun readsTheLanguagesTheLocaleServiceReports() {
        // As Android 16 on a OnePlus Pad prints it.
        assertEquals(
            emptyList(),
            AppLocales.parse("Locales for com.oneplus.calculator for user 0 are []\n"),
        )
        assertEquals(
            listOf("fr-FR", "de"),
            AppLocales.parse("Locales for com.example.app for user 0 are [fr-FR,de]\r\n"),
        )
        assertNull(AppLocales.parse("Unknown command: get-app-locales"))
    }

    @Test
    fun everyListedLanguageIsATag() {
        assertTrue(AppLocales.common.all { AppLocales.isTag(it.tag) })
        assertTrue(AppLocales.isTag("zh-Hant-TW"))
        assertFalse(AppLocales.isTag("fr_FR"))
        assertFalse(AppLocales.isTag(""))
    }

    @Test
    fun monkeyTapsOnlyTheQuotedAppWithSystemKeysAndRotationOff() {
        assertEquals(
            "monkey -p 'com.example.app' --throttle 50 --pct-syskeys 0 --pct-anyevent 0 " +
                "--pct-rotation 0 " +
                "--pct-motion 0 --pct-trackball 0 --pct-pinchzoom 0 -s 42 -v 500",
            Monkey.command("com.example.app", 500, seed = 42).line,
        )
        assertEquals(
            "monkey -p 'com.example.app' --throttle 50 --pct-syskeys 0 --pct-anyevent 0 " +
                "--pct-rotation 0 -v 1",
            Monkey.command("com.example.app", 1, swipes = true).line,
        )
        assertFailsWith<IllegalArgumentException> { Monkey.command("com.example.app", 0) }
        assertFailsWith<IllegalArgumentException> { Monkey.command("com.example.app", 100_001) }
    }

    @Test
    fun aCleanRunReportsItsEvents() {
        val report = Monkey.report(File(dir, "clock-60-events.txt").readText())
        assertEquals(
            MonkeyReport(
                injected = 60,
                crashed = false,
                notResponding = false,
                aborted = false,
                crash = null,
            ),
            report,
        )
    }

    @Test
    fun aCrashReportsItsException() {
        val report = Monkey.report(File(dir, "crash-shape.txt").readText())
        assertTrue(report.crashed)
        assertTrue(report.aborted)
        assertEquals(12, report.injected)
        assertEquals("java.lang.IllegalStateException: Cart is empty", report.crash)
    }
}
