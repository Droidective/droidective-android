package com.droidective.core.toggles

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ScreenshotTest {
    @Test
    fun aScreenshotToAFileIsLeftReadable() {
        assertEquals(
            "screencap -p '/x/ui.png' && chmod 644 '/x/ui.png'",
            Screenshot.toFile("/x/ui.png").line,
        )
        // A path is quoted, whatever it holds.
        assertEquals(
            "screencap -p '/x/it'\\''s.png' && chmod 644 '/x/it'\\''s.png'",
            Screenshot.toFile("/x/it's.png").line,
        )
    }

    @Test
    fun onlyAPictureCanBeAsked() {
        assertFailsWith<IllegalArgumentException> { Screenshot.toFile("/x/notes.txt") }
        assertFailsWith<IllegalArgumentException> { Screenshot.toFile("/x/../y.png") }
    }
}
