package com.droidective.core.toggles

/**
 * What the floating controls offer over other apps: the actions that are only any use while the app
 * being worked on is on screen, which is the one moment you can't go and open Droidective.
 */
object FloatingControls {
    /** One button: a toggle, and how the controls keep out of what it captures. */
    data class Control(val toggleId: String, val hide: Hide)

    /** How long the controls get out of the way for a tap. */
    sealed interface Hide {
        /** Not at all: nothing on screen is captured. */
        data object Never : Hide

        /** For this long, which covers the shade closing and the capture itself. */
        data class For(val millis: Long) : Hide

        /** For as long as the toggle reads on: a recording, which would film the controls. */
        data object WhileOn : Hide
    }

    val controls: List<Control> =
        listOf(
            Control("screenshot", Hide.For(SCREENSHOT_MILLIS)),
            Control("clean-screenshot", Hide.For(CLEAN_SCREENSHOT_MILLIS)),
            Control("record-screen", Hide.WhileOn),
            // Its three-second countdown, then uiautomator's dump and the screenshot.
            Control("capture-views", Hide.For(CAPTURE_VIEWS_MILLIS)),
            // Its two-second wait, then the screenshot.
            Control("markup-screenshot", Hide.For(MARKUP_MILLIS)),
            Control("traffic-proxy", Hide.Never),
        )

    /** The toggles that capture the screen, where anything drawn over it would be captured too. */
    val captures: Set<String> =
        controls.filter { it.hide != Hide.Never }.map { it.toggleId }.toSet()

    private const val SCREENSHOT_MILLIS = 2_000L
    private const val CLEAN_SCREENSHOT_MILLIS = 3_500L
    private const val CAPTURE_VIEWS_MILLIS = 9_000L
    private const val MARKUP_MILLIS = 4_500L
}
