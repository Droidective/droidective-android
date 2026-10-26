package com.droidective.core.toggles

/**
 * What a toggle tapped from a widget, tile or notification bar says once it worked, as a toast, so
 * a tap away from the app never goes unanswered.
 */
object TapNotes {
    /**
     * "Wi-Fi: On", or "Lock screen: done" for an action. Nothing for a toggle that captures the
     * screen: the toast would be in the capture, and a screenshot or recording has its own
     * notification.
     */
    fun applied(toggle: ToggleDef, state: String): String? =
        when {
            toggle.id in FloatingControls.captures -> null
            toggle.isAction -> "${toggle.title}: done"
            else ->
                "${toggle.title}: ${toggle.states.firstOrNull { it.id == state }?.label ?: state}"
        }

    /** Wireless debugging is turned on to connect to it, so its note adds where: `ip:port`. */
    fun addsAdbAddress(toggle: ToggleDef, state: String): Boolean =
        toggle.id == WIRELESS_DEBUGGING && state == "on"

    private const val WIRELESS_DEBUGGING = "wireless-debugging"
}
