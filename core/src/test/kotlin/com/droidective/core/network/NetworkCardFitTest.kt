package com.droidective.core.network

import com.droidective.core.layout.TextFit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NetworkCardFitTest {
    private val longest = NetworkLine("Local", "255.255.255.255", main = true)
    private val public = NetworkLine("Public", "255.255.255.255", main = false)
    private val lines = listOf(longest, public)

    private fun main(fitted: NetworkCardFit.Fitted) = fitted.lines.first().value

    /** Every line fits the width, and the whole card the height, the padding taken off. */
    private fun assertFits(width: Float, height: Float, scale: Float = 1f) {
        val fitted = NetworkCardFit.fit(width, height, scale, "Not Available", lines)
        val room = width - 2 * NetworkCardFit.PADDING_SIDES
        for (sized in fitted.lines) {
            val wide = TextFit.width(sized.line.value, monospace = true) * sized.value * scale
            assertTrue(
                wide <= room + 0.01f,
                "${sized.line.value} at ${width}x$height: $wide > $room",
            )
        }
        val tall = NetworkCardFit.heightOf(fitted, scale)
        assertTrue(tall <= height - 2 * NetworkCardFit.PADDING_ENDS, "${width}x$height: $tall")
    }

    @Test
    fun theLongestAddressFitsEverySizeFromTheSmallestUp() {
        listOf(
                110f to 40f, // the smallest it resizes to
                110f to 110f,
                170f to 170f, // two by two on a phone
                250f to 110f,
                431f to 215f, // four by two on a tablet
            )
            .forEach { (width, height) -> assertFits(width, height) }
        assertFits(170f, 170f, scale = 1.3f)
    }

    @Test
    fun aBiggerWidgetShowsBiggerNumbers() {
        val small = main(NetworkCardFit.fit(110f, 80f, 1f, "Wi-Fi", lines))
        val phone = main(NetworkCardFit.fit(170f, 170f, 1f, "Wi-Fi", lines))
        val tablet = main(NetworkCardFit.fit(431f, 215f, 1f, "Wi-Fi", lines))
        assertTrue(small < phone && phone < tablet, "$small, $phone, $tablet")
        assertTrue(tablet > 30f, "a wide widget's address is large: $tablet")
    }

    @Test
    fun theFirstAddressStaysTheLargestWhateverTheShape() {
        listOf(431f to 215f, 170f to 300f, 110f to 200f).forEach { (width, height) ->
            val fitted = NetworkCardFit.fit(width, height, 1f, "Not Available", lines)
            val first = main(fitted)
            assertTrue((fitted.name ?: 0f) < first, "name at ${width}x$height")
            fitted.lines.drop(1).forEach {
                assertTrue(it.value < first, "${it.line.label} at ${width}x$height")
            }
        }
    }

    @Test
    fun aShortAddressGrowsTallerThanALongOneInTheSameWidget() {
        val short = listOf(longest.copy(value = "10.0.2.15"))
        assertTrue(
            main(NetworkCardFit.fit(250f, 110f, 1f, null, short)) >
                main(NetworkCardFit.fit(250f, 110f, 1f, null, listOf(longest)))
        )
    }

    @Test
    fun aWidgetTooShortForEverythingDropsTheLastLinesThenTheName() {
        val oneCell = NetworkCardFit.fit(250f, 40f, 1f, "Wi-Fi", lines)
        assertNull(oneCell.name, "no room for the name")
        assertEquals(listOf(longest), oneCell.lines.map { it.line }, "the first address stays")
        val twoCells = NetworkCardFit.fit(250f, 110f, 1f, "Wi-Fi", lines)
        assertEquals(lines, twoCells.lines.map { it.line })
    }

    @Test
    fun aLargerFontSettingGetsSmallerSizesThatDrawAsWide() {
        val usual = main(NetworkCardFit.fit(170f, 170f, 1f, null, listOf(longest)))
        val larger = main(NetworkCardFit.fit(170f, 170f, 1.3f, null, listOf(longest)))
        assertTrue(larger < usual)
        assertEquals(usual, larger * 1.3f, 0.5f)
    }
}
