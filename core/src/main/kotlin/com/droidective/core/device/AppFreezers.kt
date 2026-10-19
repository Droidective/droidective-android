package com.droidective.core.device

/**
 * Android builds that freeze an app a few seconds after it leaves the screen and hold the
 * broadcasts sent to it until something else wakes it, a widget tap's included. Measured on
 * OxygenOS 16 (docs/spikes.md, spike 1): taps 4.6 s apart landed up to 22 s late, 4 times in 10.
 * ColorOS and realme UI share the freezer. A running foreground service exempts the app.
 */
object AppFreezers {
    private val brands = setOf("oneplus", "oppo", "realme")

    /** Whether a phone made by [manufacturer] holds widget taps for a frozen app. */
    fun holdsWidgetTaps(manufacturer: String): Boolean = manufacturer.trim().lowercase() in brands
}
