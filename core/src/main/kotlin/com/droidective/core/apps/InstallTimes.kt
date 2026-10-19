package com.droidective.core.apps

/**
 * When an app was installed, as Android reports it. An app that came with the system image carries
 * the image's build clock, which on many phones is a few days after 1970 rather than a real date.
 */
object InstallTimes {
    /** 23 September 2008, the day Android 1.0 shipped: nothing was installed before it. */
    const val ANDROID_RELEASE_MILLIS = 1_222_128_000_000L

    /** [millis], or null when it can't be a real install time. */
    fun real(millis: Long): Long? = millis.takeIf { it >= ANDROID_RELEASE_MILLIS }
}
