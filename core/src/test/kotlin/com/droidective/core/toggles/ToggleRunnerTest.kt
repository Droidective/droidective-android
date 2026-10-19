package com.droidective.core.toggles

import com.droidective.core.Access
import com.droidective.core.AccessLevel
import com.droidective.core.ShellProvider
import com.droidective.core.shell.ShellCommand
import com.droidective.core.testing.FakePlatformToggles
import com.droidective.core.testing.FakeSettingsStore
import com.droidective.core.testing.FakeShell
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class ToggleRunnerTest {
    private val shell = FakeShell()
    private val settings = FakeSettingsStore()
    private val platform = FakePlatformToggles()
    private var access = Access(granted = false, shell = ShellProvider.Shizuku)
    private val params = mutableMapOf<Param, String>()
    private val runner =
        ToggleRunner(settings, platform, shell, { shell }, { access }, { params[it] })

    private fun toggle(id: String) = requireNotNull(Toggles.byId(id)) { id }

    @Test
    fun wifiRunsTheSameCommandTheMacSends() = runTest {
        platform.states[PlatformApi.WifiEnabled] = "off"
        runner.apply(toggle("wifi"), "off")
        assertEquals(listOf("svc wifi disable"), shell.invocations)
    }

    @Test
    fun layoutBoundsSetsThePropThenPokesRunningApps() = runTest {
        shell.respond("getprop debug.layout", stdout = "true\n")
        val outcome = runner.apply(toggle("layout-bounds"), "on")
        assertEquals(
            listOf(
                "setprop debug.layout true",
                "service call activity 1599295570",
                "getprop debug.layout",
            ),
            shell.invocations,
        )
        assertEquals(Outcome.Applied("on", confirmed = true), outcome)
    }

    @Test
    fun grantedSettingsGoThroughTheSettingsApi() = runTest {
        access = Access(granted = true, shell = null)
        settings.writable += SettingScope.Global
        val outcome = runner.apply(toggle("animation-scale"), "0.5")
        assertEquals("0.5", settings.values["global" to "window_animation_scale"])
        assertEquals("0.5", settings.values["global" to "transition_animation_scale"])
        assertEquals("0.5", settings.values["global" to "animator_duration_scale"])
        assertEquals(Outcome.Applied("0.5", confirmed = true), outcome)
        assertEquals(emptyList(), shell.invocations)
    }

    @Test
    fun withoutTheGrantASettingIsWrittenByTheShellQuoted() = runTest {
        runner.apply(toggle("stay-awake"), "on")
        assertEquals(listOf("settings put global stay_on_while_plugged_in '7'"), shell.invocations)
    }

    @Test
    fun privateSystemSettingsAlwaysUseTheShell() = runTest {
        access = Access(granted = true, shell = ShellProvider.Shizuku)
        settings.writable += SettingScope.Global
        runner.apply(toggle("show-touches"), "on")
        assertEquals(listOf("settings put system show_touches '1'"), shell.invocations)
    }

    @Test
    fun preferredNetworkSetsSlotZerosMaskAndReadsItBack() = runTest {
        access = Access(granted = true, shell = ShellProvider.Shizuku)
        // Recorded from the API 36 emulator after the 4G mask.
        shell.respond(
            "cmd phone get-allowed-network-types-for-users -s 0",
            "GPRS|EDGE|UMTS|CDMA|CDMA - EvDo rev. 0|CDMA - EvDo rev. A|CDMA - 1xRTT|HSDPA|" +
                "HSUPA|HSPA|CDMA - EvDo rev. B|LTE|CDMA - eHRPD|HSPA+|GSM|LTE_CA\n",
        )
        val outcome = runner.apply(toggle("preferred-network"), "4g")
        assertEquals(Outcome.Applied("4g", confirmed = true), outcome)
        assertEquals(
            "cmd phone set-allowed-network-types-for-users -s 0 01001111101111111111",
            shell.invocations.first(),
        )
    }

    @Test
    fun missingShellUsesTheFallback() = runTest {
        access = Access.Nothing
        val outcome = runner.apply(toggle("wifi"), "on")
        assertEquals(Outcome.UseFallback(toggle("wifi").fallback!!), outcome)
        assertEquals(emptyList(), shell.invocations)
    }

    @Test
    fun missingShellWithNoStandInAsksForSetup() = runTest {
        access = Access(granted = true, shell = null)
        assertEquals(
            Outcome.NeedsLevel(AccessLevel.Shell),
            runner.apply(toggle("layout-bounds"), "on"),
        )
    }

    @Test
    fun aWriteThatDoesNotStickIsReported() = runTest {
        access = Access(granted = true, shell = null)
        settings.writable += SettingScope.Secure
        settings.values["secure" to "night_display_activated"] = "0"
        val store =
            object : SettingsStore by settings {
                override fun put(scope: SettingScope, key: String, value: String) = true
            }
        val stubborn = ToggleRunner(store, platform, shell, { shell }, { access })
        assertEquals(Outcome.DidNotStick("on", "off"), stubborn.apply(toggle("night-light"), "on"))
    }

    @Test
    fun aFailedCommandReportsItsError() = runTest {
        shell.respond("svc nfc enable", exitCode = 1, stderr = "\r\nNFC is not supported\r\n")
        assertEquals(Outcome.Failed("NFC is not supported"), runner.apply(toggle("nfc"), "on"))
    }

    @Test
    fun aStoppedShellIsAFailureNotACrash() = runTest {
        shell.unavailable = true
        assertEquals(Outcome.Failed("Shizuku stopped"), runner.apply(toggle("wifi"), "on"))
    }

    @Test
    fun publicSystemSettingsNeedModifySystemSettings() = runTest {
        assertEquals(
            Outcome.NeedsSpecialAccess(SpecialAccess.ModifySystemSettings),
            runner.apply(toggle("auto-rotate"), "on"),
        )
    }

    @Test
    fun brightnessAlsoSwitchesToManualMode() = runTest {
        settings.writable += SettingScope.SystemPublic
        runner.apply(toggle("brightness"), "40")
        assertEquals("0", settings.values["system" to "screen_brightness_mode"])
        assertEquals("102", settings.values["system" to "screen_brightness"])
    }

    @Test
    fun actionsAreNotReadBack() = runTest {
        assertEquals(
            Outcome.Applied("run", confirmed = false),
            runner.apply(toggle("lock-screen"), "run"),
        )
        assertEquals(listOf("input keyevent KEYCODE_SLEEP"), shell.invocations)
    }

    @Test
    fun pendingTogglesSayWhy() = runTest {
        val outcome = runner.apply(toggle("hotspot"), "on")
        assertEquals(
            true,
            (outcome as Outcome.Failed).message.startsWith("Wi-Fi hotspot isn’t built yet"),
        )
    }

    @Test
    fun aHiddenSettingIsReadThroughTheShell() = runTest {
        settings.hidden += "reduce_bright_colors_activated"
        shell.respond("settings get secure reduce_bright_colors_activated", stdout = "1\r\n")
        assertEquals("on", runner.read(toggle("extra-dim")))
    }

    @Test
    fun aKeyNeverWrittenReadsAsWhatAndroidDoesWithoutIt() = runTest {
        settings.hidden += "show_touches"
        shell.respond("settings get system show_touches", stdout = "null\n")
        assertEquals("off", runner.read(toggle("show-touches")))
        assertEquals(
            "on",
            runner.read(toggle("verify-adb-installs")),
            "Android verifies unless told not to",
        )
        assertEquals(
            null,
            runner.read(toggle("auto-rotate")),
            "always written, so unset is unknown",
        )
    }

    @Test
    fun aHiddenSettingWithoutShellIsUnknownNotACrash() = runTest {
        access = Access(granted = true, shell = null)
        settings.hidden += "reduce_bright_colors_activated"
        assertEquals(null, runner.read(toggle("extra-dim")))
        assertEquals(emptyList(), shell.invocations)
    }

    @Test
    fun aProxyWithNoAddressAsksForOne() = runTest {
        access = Access(granted = true, shell = null)
        settings.writable += SettingScope.Global
        assertEquals(Outcome.NeedsValue(Param.Proxy), runner.apply(toggle("http-proxy"), "on"))
        params[Param.Proxy] = "10.0.0.12:8888"
        assertEquals(
            Outcome.Applied("on", confirmed = true),
            runner.apply(toggle("http-proxy"), "on"),
        )
        assertEquals("10.0.0.12:8888", settings.values["global" to "http_proxy"])
        runner.apply(toggle("http-proxy"), "off")
        assertEquals(":0", settings.values["global" to "http_proxy"])
    }

    @Test
    fun privateDnsSetsTheHostnameBeforeTheMode() = runTest {
        params[Param.DnsHost] = "dns.adguard-dns.com"
        runner.apply(toggle("private-dns"), "hostname")
        assertEquals(
            listOf(
                "settings put global private_dns_specifier 'dns.adguard-dns.com'",
                "settings put global private_dns_mode 'hostname'",
            ),
            shell.invocations,
        )
    }

    @Test
    fun densityStepsFromThePhysicalDensity() = runTest {
        shell.respond("wm density", stdout = "Physical density: 420\r\n")
        runner.apply(toggle("display-density"), "smaller")
        assertEquals(listOf("wm density", "wm density 357", "wm density"), shell.invocations)
    }

    @Test
    fun densityWithNoPhysicalLineFails() = runTest {
        shell.respond("wm density", stdout = "cmd: Can't find service: window")
        assertTrue(runner.apply(toggle("display-density"), "larger") is Outcome.Failed)
    }

    @Test
    fun selinuxNeedsRootNotJustShell() = runTest {
        assertEquals(
            Outcome.NeedsLevel(AccessLevel.Root),
            runner.apply(toggle("selinux"), "permissive"),
        )
        access = Access(granted = false, shell = ShellProvider.Su)
        runner.apply(toggle("selinux"), "permissive")
        assertEquals(listOf("setenforce 0", "getenforce"), shell.invocations)
    }

    @Test
    fun aParameterInACommandIsQuoted() = runTest {
        params[Param.Proxy] = "x'; reboot; '"
        val synthetic =
            ToggleDef(
                id = "synthetic",
                title = "Synthetic",
                group = ToggleGroup.Developer,
                icon = "tune",
                states = listOf(ToggleState("run", "Run")),
                read = Probe.None,
                write = Write.Commands(mapOf("run" to listOf(ShellCommand("echo {proxy}")))),
            )
        runner.apply(synthetic, "run")
        assertEquals(listOf("echo 'x'\\''; reboot; '\\'''"), shell.invocations)
    }

    @Test
    fun cyclesMoveThroughStatesInOrderAndRestartFromUnknown() {
        val scale = toggle("animation-scale")
        assertEquals("0.5", scale.next("1").id)
        assertEquals("1", scale.next("0").id)
        assertEquals("1", scale.next(null).id)
    }
}
