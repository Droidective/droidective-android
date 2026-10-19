package com.droidective.core.toggles

import com.droidective.core.Access
import com.droidective.core.ShellProvider
import com.droidective.core.testing.FakePlatformToggles
import com.droidective.core.testing.FakeSettingsStore
import com.droidective.core.testing.FakeShell
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest

/**
 * What a OnePlus Pad on Android 16 showed and the emulators didn't: values Android writes that a
 * toggle didn't expect, and reads an app isn't allowed to make on a real phone. The app's own shell
 * and the privileged one are separate fakes, so a test can tell which one read.
 */
class RealPhoneReadsTest {
    private val appShell = FakeShell()
    private val privileged = FakeShell()
    private val settings = FakeSettingsStore()
    private var access = Access(granted = true, shell = ShellProvider.Su)
    private val runner =
        ToggleRunner(settings, FakePlatformToggles(), appShell, { privileged }, { access })

    private fun toggle(id: String) = requireNotNull(Toggles.byId(id)) { id }

    @Test
    fun stayAwakeIsOnForAnyCharger() = runTest {
        // Developer options writes 15 (AC, USB, wireless and dock) from Android 13.
        for ((stored, state) in listOf("15" to "on", "7" to "on", "2" to "on", "0" to "off")) {
            settings.values["global" to "stay_on_while_plugged_in"] = stored
            assertEquals(state, runner.read(toggle("stay-awake")), stored)
        }
        settings.values.remove("global" to "stay_on_while_plugged_in")
        assertEquals("off", runner.read(toggle("stay-awake")))
    }

    @Test
    fun grayscaleNeverTurnedOnIsOff() = runTest {
        assertEquals("off", runner.read(toggle("grayscale")))
        settings.values["secure" to "accessibility_display_daltonizer_enabled"] = "1"
        assertEquals("on", runner.read(toggle("grayscale")))
    }

    @Test
    fun selinuxIsReadByTheShellNotTheApp() = runTest {
        privileged.respond("getenforce", stdout = "Enforcing\n")
        assertEquals("enforcing", runner.read(toggle("selinux")))
        assertEquals(listOf("getenforce"), privileged.invocations)
        assertEquals(emptyList(), appShell.invocations)

        access = Access(granted = true, shell = null)
        assertNull(runner.read(toggle("selinux")))
    }

    @Test
    fun strictModeIsReadByTheShellBecauseAppsCantReadSystemProps() = runTest {
        privileged.respond("getprop persist.sys.strictmode.visual", stdout = "1\n")
        assertEquals("on", runner.read(toggle("strict-mode")))
        assertEquals(emptyList(), appShell.invocations)
        // A debug_prop is still read as the app, which needs no shell.
        appShell.respond("getprop debug.layout", stdout = "true\n")
        assertEquals("on", runner.read(toggle("layout-bounds")))
        assertEquals(listOf("getprop debug.layout"), appShell.invocations)
    }

    @Test
    fun aSystemSettingGoesThroughTheShellWithoutModifySystemSettings() = runTest {
        // Font size is a public System key: with a shell there's no special access to ask for.
        runner.apply(toggle("font-size"), "1")
        assertEquals("settings put system font_scale '1.0'", privileged.invocations.first())

        access = Access(granted = false, shell = null)
        assertEquals(
            Outcome.NeedsSpecialAccess(SpecialAccess.ModifySystemSettings),
            runner.apply(toggle("font-size"), "1"),
        )
    }
}
