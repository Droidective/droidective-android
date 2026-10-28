package com.droidective.core.layout

import kotlin.math.max
import kotlin.math.min

/**
 * Font sizes that fit a line of text into the width it has. A widget can't shrink its text to fit
 * the way a screen in the app can, so its text is sized from the widget's own size. Widths are
 * estimated from the font, on the wide side, so a phone whose digits run wider still fits.
 */
object TextFit {
    /** How wide [text] is at 1 sp, in dp, at the default font scale. */
    fun width(text: String, monospace: Boolean): Float =
        if (monospace) text.length * MONOSPACE else text.sumOf { advance(it).toDouble() }.toFloat()

    /**
     * The largest size in sp, up to [max], at which [text] fits [widthDp] at the phone's
     * [fontScale]; never under [min], where it's cut short instead.
     */
    fun size(
        text: String,
        widthDp: Float,
        max: Float,
        min: Float,
        fontScale: Float,
        monospace: Boolean = false,
    ): Float {
        val width = width(text, monospace) * fontScale
        if (width <= 0f) return max
        return max(min(widthDp / width, max), min)
    }

    /**
     * Text as it's best shown, and its size.
     *
     * @property cutShort it didn't fit even at the smallest size, so it's shown cut short.
     */
    data class Fitted(val text: String, val size: Float, val cutShort: Boolean = false)

    /**
     * [text] in [widthDp] by [heightDp]: on one line while it can be at least [min]; otherwise
     * split at the dot or colon nearest its middle onto two lines when that reads bigger, as an
     * address in a narrow slot does ("255.255." over "255.255"). Never above [max], and never under
     * [min], where it's cut short instead.
     */
    fun fitted(
        text: String,
        widthDp: Float,
        heightDp: Float,
        max: Float,
        min: Float,
        fontScale: Float,
        monospace: Boolean = false,
    ): Fitted {
        val one = largest(text, widthDp, heightDp, lines = 1, max, fontScale, monospace)
        val cut = splitAt(text)
        if (one >= min || cut == null) return Fitted(text, max(one, min), one < min)
        val halves = listOf(text.substring(0, cut + 1), text.substring(cut + 1))
        val longer = halves.maxBy { width(it, monospace) }
        val two = largest(longer, widthDp, heightDp, lines = 2, max, fontScale, monospace)
        return if (two > one) Fitted(halves.joinToString("\n"), max(two, min), two < min)
        else Fitted(text, min, cutShort = true)
    }

    /** The largest size up to [max] at which [lines] lines of [text] fit both ways. */
    private fun largest(
        text: String,
        widthDp: Float,
        heightDp: Float,
        lines: Int,
        max: Float,
        fontScale: Float,
        monospace: Boolean,
    ): Float {
        val wide = width(text, monospace) * fontScale
        val byWidth = if (wide > 0f) widthDp / wide else max
        return minOf(max, byWidth, heightDp / (lines * LINE_HEIGHT * fontScale))
    }

    /** A line's height for its font size, with the padding Android puts above and below it. */
    const val LINE_HEIGHT = 1.25f

    /** The separator nearest the middle, if there's one to split at. */
    private fun splitAt(text: String): Int? =
        text.indices
            .filter { text[it] in SEPARATORS && it in 1 until text.lastIndex }
            .minByOrNull { kotlin.math.abs(it - text.length / 2) }

    private fun advance(char: Char): Float =
        when {
            char in NARROW_CHARS -> NARROW
            char.isUpperCase() || char in WIDE_CHARS -> WIDE
            else -> REGULAR
        }

    private const val MONOSPACE = 0.62f
    private const val REGULAR = 0.58f
    private const val WIDE = 0.75f
    private const val NARROW = 0.3f
    private const val NARROW_CHARS = ".,:;'|!il "
    private const val WIDE_CHARS = "mw@%"
    private const val SEPARATORS = ".:"
}
