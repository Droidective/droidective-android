package com.droidective.core.shell

import com.droidective.core.testing.FixtureShell
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/** Parsers against output recorded from a real emulator, not hand-written samples. */
class FixtureParseTest {
    private val device = FixtureShell()

    @Test
    fun parsesEveryPropertyOfARealGetprop() = runTest {
        val props = Parse.props(device.run(ShellCommand.of("getprop")).stdout)
        assertTrue(props.size > 100, "only ${props.size} properties parsed")
        assertEquals("sdk_gphone64_arm64", props["ro.product.model"])
        assertTrue(props.getValue("ro.build.version.sdk").toInt() >= 30)
    }

    @Test
    fun keepsValuesWithBracketsAndColons() {
        assertEquals(
            mapOf("ro.boot.hardware.revision" to "a: [b]"),
            Parse.props("[ro.boot.hardware.revision]: [a: [b]]\r\n"),
        )
    }
}
