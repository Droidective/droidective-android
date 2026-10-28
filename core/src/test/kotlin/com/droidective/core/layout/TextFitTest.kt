package com.droidective.core.layout

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TextFitTest {
    @Test
    fun withRoomToSpareTextKeepsItsSize() {
        assertEquals(11f, TextFit.size("10.0.2.15", 200f, max = 11f, min = 7f, fontScale = 1f))
    }

    @Test
    fun aNarrowerSpaceGetsASmallerSizeThatFits() {
        val size = TextFit.size("255.255.255.255", 60f, max = 11f, min = 7f, fontScale = 1f)
        assertTrue(size < 11f)
        assertTrue(TextFit.width("255.255.255.255", monospace = false) * size <= 60f)
    }

    @Test
    fun belowTheSmallestReadableSizeItStopsShrinking() {
        assertEquals(7f, TextFit.size("255.255.255.255", 10f, max = 11f, min = 7f, fontScale = 1f))
    }

    @Test
    fun anAddressTooLongForOneLineSplitsAtItsMiddleDot() {
        val fitted = TextFit.fitted("255.255.255.255", 44f, 40f, max = 11f, min = 8f, 1f)
        assertEquals("255.255.\n255.255", fitted.text)
        assertTrue(fitted.size > 8f, "two lines read bigger than one cut short: ${fitted.size}")
        assertTrue(TextFit.width("255.255.", false) * fitted.size <= 44f)
        assertTrue(2 * TextFit.LINE_HEIGHT * fitted.size <= 40f, "both lines fit the height")
        assertEquals(
            TextFit.Fitted("10.0.2.15", 11f),
            TextFit.fitted("10.0.2.15", 80f, 40f, max = 11f, min = 8f, 1f),
            "what fits stays on one line",
        )
        assertEquals("fe80::\n1:2:3", TextFit.fitted("fe80::1:2:3", 10f, 40f, 11f, 8f, 1f).text)
    }

    @Test
    fun withNoHeightForTwoLinesItStaysOnOne() {
        val fitted = TextFit.fitted("255.255.255.255", 44f, 12f, max = 11f, min = 8f, 1f)
        assertEquals("255.255.255.255", fitted.text)
        assertTrue(fitted.cutShort, "too small for either, so it's cut short")
        assertTrue(!TextFit.fitted("255.255.255.255", 44f, 40f, 11f, 8f, 1f).cutShort)
    }

    @Test
    fun dotsAreNarrowerThanDigitsExceptInMonospace() {
        assertTrue(TextFit.width("1.1", false) < TextFit.width("111", false))
        assertEquals(TextFit.width("1.1", true), TextFit.width("111", true))
    }
}
