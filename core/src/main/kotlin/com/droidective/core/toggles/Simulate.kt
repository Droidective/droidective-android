package com.droidective.core.toggles

import com.droidective.core.shell.Parse
import com.droidective.core.shell.ShellCommand
import kotlin.math.roundToInt

/** Fake battery commands, the same `dumpsys battery` lines the Mac sends. */
object FakeBattery {
    fun set(level: Int, unplugged: Boolean): List<ShellCommand> {
        require(level in 0..100) { "Battery level $level isn’t between 0 and 100" }
        return listOfNotNull(
            ShellCommand.of("dumpsys", "battery", "unplug").takeIf { unplugged },
            ShellCommand.of("dumpsys", "battery", "set", "level", level.toString()),
        )
    }

    val reset: List<ShellCommand> = listOf(ShellCommand.of("dumpsys", "battery", "reset"))
}

/**
 * Display density steps relative to the phone's own density, like Settings' display size. The
 * factors are Android's smallest and second-largest display-size steps.
 */
object DensitySteps {
    const val SMALLER = 0.85
    const val LARGER = 1.15

    val probe: ShellCommand = ShellCommand.of("wm", "density")

    /** The commands for [state] given `wm density` output, or null when it has no physical line. */
    fun commands(output: String, state: String): List<ShellCommand>? {
        val physical = Parse.wmDensity(output)?.physical ?: return null
        return when (state) {
            "default" -> listOf(ShellCommand.of("wm", "density", "reset"))
            "smaller" -> listOf(set(physical, SMALLER))
            "larger" -> listOf(set(physical, LARGER))
            else -> null
        }
    }

    /** Which step the phone is on, from `wm density` output. */
    fun state(output: String): String? {
        val density = Parse.wmDensity(output) ?: return null
        val override = density.override ?: return "default"
        return when {
            override == density.physical -> "default"
            override < density.physical -> "smaller"
            else -> "larger"
        }
    }

    private fun set(physical: Int, factor: Double) =
        ShellCommand.of("wm", "density", (physical * factor).roundToInt().toString())
}
