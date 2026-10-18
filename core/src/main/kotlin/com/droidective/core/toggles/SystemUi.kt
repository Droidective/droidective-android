package com.droidective.core.toggles

/**
 * SystemUI's demo mode: a clean status bar for screenshots. The broadcasts and extras are the ones
 * the Mac app sends, so a demo status bar looks the same from either app.
 */
object DemoMode {
    const val ACTION = "com.android.systemui.demo"
    const val ALLOWED_SETTING = "sysui_demo_allowed"

    /** Sent in order to enter demo mode; each map is one broadcast's string extras. */
    val enter: List<Map<String, String>> =
        listOf(
            mapOf("command" to "enter"),
            mapOf("command" to "clock", "hhmm" to "0941"),
            mapOf("command" to "battery", "level" to "100", "plugged" to "false"),
            mapOf("command" to "network", "wifi" to "show", "level" to "4"),
            mapOf("command" to "notifications", "visible" to "false"),
        )

    val exit: Map<String, String> = mapOf("command" to "exit")
}

/** The keyboard cycle behind Switch keyboard, from the Secure settings Android keeps. */
object Keyboards {
    /**
     * The keyboard after [current] among [enabled].
     *
     * @param enabled `enabled_input_methods`: ids separated by ':', each optionally followed by
     *   ';'-separated subtypes.
     * @param current `default_input_method`.
     * @return the next id, wrapping around, or null when fewer than two keyboards are enabled.
     */
    fun next(enabled: String?, current: String?): String? {
        val ids =
            enabled
                .orEmpty()
                .split(':')
                .map { it.substringBefore(';').trim() }
                .filter { it.isNotEmpty() }
        if (ids.size < 2) return null
        val index = ids.indexOf(current)
        return ids[(index + 1) % ids.size]
    }
}
