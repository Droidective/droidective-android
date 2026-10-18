package com.droidective.core

import com.droidective.core.apps.AppAction
import com.droidective.core.apps.AppActions
import com.droidective.core.commands.CommandRepository
import com.droidective.core.commands.CustomCommand
import com.droidective.core.device.Rate
import com.droidective.core.device.RateMeter
import com.droidective.core.device.RootCheck
import com.droidective.core.device.RootEvidence
import com.droidective.core.device.RootVerdict
import com.droidective.core.features.Features
import com.droidective.core.net.Addresses
import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.checkAll
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class ToolsTest {
    @Test
    fun acceptsTheProxyFormsAndroidTakes() {
        assertEquals("10.0.0.12:8888", Addresses.proxy(" 10.0.0.12:8888 "))
        assertEquals("proxy.local:3128", Addresses.proxy("proxy.local:3128"))
        assertEquals("[fe80::1]:8888", Addresses.proxy("[fe80::1]:8888"))
        listOf(
                "10.0.0.12",
                "10.0.0.12:0",
                "10.0.0.12:70000",
                ":8888",
                "host name:80",
                "256.1.1.1:80",
                "a;rm:80",
            )
            .forEach { assertNull(Addresses.proxy(it), it) }
    }

    @Test
    fun anyValidPortRoundTrips() = runTest {
        checkAll(Arb.int(1..65_535)) { port ->
            assertEquals("h.example:$port", Addresses.proxy("h.example:$port"))
        }
    }

    @Test
    fun privateDnsTakesHostnamesOnly() {
        assertEquals("dns.adguard-dns.com", Addresses.dnsHostname("dns.adguard-dns.com."))
        assertNull(Addresses.dnsHostname("1.1.1.1"))
        assertNull(Addresses.dnsHostname("localhost"))
        assertNull(Addresses.dnsHostname("bad host.com"))
    }

    @Test
    fun appActionsQuoteThePackage() {
        AppAction.entries.forEach { action ->
            // Exhaustive, so a new action can't ship without its expected line.
            val expected =
                when (action) {
                    AppAction.ForceStop -> "am force-stop 'com.example.app'"
                    AppAction.ClearCache -> "pm clear --cache-only 'com.example.app'"
                    AppAction.ClearData -> "pm clear 'com.example.app'"
                    AppAction.Disable -> "pm disable-user --user 0 'com.example.app'"
                    AppAction.Enable -> "pm enable 'com.example.app'"
                    AppAction.Uninstall -> "pm uninstall 'com.example.app'"
                }
            assertEquals(expected, AppActions.command(action, "com.example.app").line)
        }
        assertEquals(
            "pm grant 'com.example.app' 'android.permission.CAMERA'",
            AppActions.permission("com.example.app", "android.permission.CAMERA", grant = true)
                .line,
        )
        assertFailsWith<IllegalArgumentException> {
            AppActions.command(AppAction.ClearData, "com.x'; reboot; '")
        }
        assertFailsWith<IllegalArgumentException> {
            AppActions.permission("com.x", "a b", grant = false)
        }
    }

    @Test
    fun aCommandToggleRunsItsOffLineWhenOn() {
        val toggle =
            CustomCommand(
                "vpn",
                "VPN",
                command = "svc vpn on",
                offCommand = "svc vpn off",
                check = "dumpsys vpn",
                onWhenOutputContains = "ACTIVE",
            )
        assertEquals("svc vpn off", toggle.next(currentlyOn = true).line)
        assertEquals("svc vpn on", toggle.next(currentlyOn = false).line)
        assertEquals(true, toggle.isOn("state: ACTIVE\r\n"))
        assertEquals(false, toggle.isOn("state: idle"))
        val button = CustomCommand("reboot", "Reboot", command = "reboot")
        assertEquals("reboot", button.next(currentlyOn = true).line)
        assertNull(button.isOn("anything"))
    }

    @Test
    fun commandsPersistAndRejectBadIds() {
        val repo = CommandRepository(createTempDirectory("commands").toFile())
        repo.save(CustomCommand("a", "A", "echo a"))
        repo.save(CustomCommand("a", "A2", "echo a2"))
        assertEquals(listOf("A2"), repo.all().map { it.name })
        assertFailsWith<IllegalArgumentException> { repo.save(CustomCommand("../x", "X", "echo")) }
        assertFailsWith<IllegalArgumentException> { repo.save(CustomCommand("b", "B", " ")) }
        repo.delete("a")
        assertEquals(emptyList(), repo.all())
    }

    @Test
    fun rateMeterNeedsTwoSamplesAndSurvivesACounterReset() {
        val meter = RateMeter()
        assertNull(meter.sample(1_000, 500, atMillis = 0))
        assertEquals(Rate(2_000.0, 500.0), meter.sample(3_000, 1_000, atMillis = 1_000))
        assertNull(meter.sample(10, 10, atMillis = 2_000), "the counter went backwards")
        assertEquals(Rate(90.0, 0.0), meter.sample(100, 10, atMillis = 3_000))
        assertNull(meter.sample(900_000, 10, atMillis = 120_000), "two minutes in the background")
        assertEquals(Rate(100.0, 0.0), meter.sample(900_100, 10, atMillis = 121_000))
    }

    @Test
    fun formatsRatesTheWayAStatusBarDoes() {
        assertEquals("0 B/s", RateMeter.format(0.0))
        assertEquals("980 B/s", RateMeter.format(980.0))
        assertEquals("1.2 KB/s", RateMeter.format(1_234.0))
        assertEquals("14 MB/s", RateMeter.format(14_200_000.0))
    }

    @Test
    fun rootVerdictFollowsTheStrongestSign() {
        val none = RootEvidence(emptyList(), emptyList(), "release-keys", false, "green")
        assertEquals(RootVerdict.NotRooted, RootCheck.verdict(none))
        assertEquals(
            RootVerdict.Unlocked,
            RootCheck.verdict(none.copy(verifiedBootState = "orange")),
        )
        assertEquals(RootVerdict.Rooted, RootCheck.verdict(none.copy(managers = listOf("Magisk"))))
        assertTrue(
            RootCheck.reasons(none.copy(suBinaries = listOf("/system/bin/su")))
                .single()
                .contains("/system/bin/su")
        )
    }

    @Test
    fun everyToolKeepsTheMacsId() {
        // FeatureRegistry.swift at Droidective 17d14e2: a tool on the phone means the same thing
        // as the Mac's feature with that id.
        val macIds =
            setOf(
                "send-text",
                "get-ip",
                "connection",
                "reverse-port",
                "wireless-adb",
                "emulators",
                "network-speed",
                "wifi",
                "private-dns",
                "react-native",
                "open-dev-menu",
                "reload-js",
                "deep-link",
                "process-death",
                "rn-dev-host",
                "reactotron",
                "js-console",
                "scrcpy",
                "mirror-wall",
                "screenshot",
                "screen-record",
                "video-editor",
                "demo-mode",
                "file-explorer",
                "device-info",
                "root-status",
                "system-restrictions",
                "dev-settings",
                "simulate",
                "fake-battery",
                "dark-mode",
                "push-notification",
                "layout-overrides",
                "animation-scale",
                "locale",
                "network-toggles",
                "http-proxy",
                "apps",
                "install-app",
                "apk-studio",
                "apk-inspector",
                "apk-sign",
                "apk-decompile",
                "aab-convert",
                "frida-console",
                "app-management",
                "permissions",
                "app-info",
                "current-activity",
                "foreground-package",
                "meminfo",
                "sandbox-browser",
                "monkey",
                "logcat",
                "ios-logs",
                "crash-catcher",
                "bug-report",
                "performance",
                "api-client",
                "terminal",
                "custom-commands",
            )
        assertEquals(61, macIds.size)
        assertEquals(emptySet(), Features.all.map { it.id }.toSet() - macIds)
        assertEquals(Features.all.size, Features.all.map { it.id }.toSet().size)
    }
}
