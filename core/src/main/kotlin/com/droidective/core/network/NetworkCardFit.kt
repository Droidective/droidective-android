package com.droidective.core.network

import com.droidective.core.layout.TextFit
import kotlin.math.max

/**
 * The Network widget's sizes for the size it is: as large as its width and height allow, so a
 * bigger widget shows bigger numbers and a long address like 255.255.255.255 still fits a small
 * one. When even the smallest sizes leave too little height, lines go, the last first, then the
 * name; the first address always stays.
 */
object NetworkCardFit {
    /** One address line: its label's size (null for the first, which has none) and its own. */
    data class LineSizes(val line: NetworkLine, val label: Float?, val value: Float)

    /**
     * Sizes in sp for text and dp for the rest.
     *
     * @property name the name's size, or null when it doesn't show.
     */
    data class Fitted(
        val name: Float?,
        val icon: Float,
        val gap: Float,
        val lines: List<LineSizes>,
    )

    /**
     * @param title the name on top, or null when the widget doesn't show it.
     * @param fontScale the phone's font size setting, which scales every sp.
     */
    fun fit(
        widthDp: Float,
        heightDp: Float,
        fontScale: Float,
        title: String?,
        lines: List<NetworkLine>,
    ): Fitted {
        val width = widthDp - 2 * PADDING_SIDES
        val height = heightDp - 2 * PADDING_ENDS
        var name = title
        var shown = lines
        while (true) {
            largestThatFits(width, height, fontScale, name, shown)?.let {
                return it
            }
            when {
                shown.size > 1 -> shown = shown.dropLast(1)
                name != null -> name = null
                else -> return at(MIN_STEPS * SCALE_STEP, width, fontScale, null, shown)
            }
        }
    }

    private fun largestThatFits(
        width: Float,
        height: Float,
        fontScale: Float,
        title: String?,
        lines: List<NetworkLine>,
    ): Fitted? {
        // Whole steps, so float rounding can't skip the smallest.
        for (step in MAX_STEPS downTo MIN_STEPS) {
            val fitted = at(step * SCALE_STEP, width, fontScale, title, lines)
            if (heightOf(fitted, fontScale) <= height) return fitted
        }
        return null
    }

    /**
     * Everything at [scale] times its usual size, each line no wider than [width]. The first
     * address is sized first, and the rest follow its size: when the width holds it back, they stay
     * smaller than it rather than growing past it into the height.
     */
    private fun at(
        scale: Float,
        width: Float,
        fontScale: Float,
        title: String?,
        lines: List<NetworkLine>,
    ): Fitted {
        val first = lines.firstOrNull { it.main }
        val main = first?.let {
            TextFit.size(it.value, width, MAIN * scale, MIN_VALUE, fontScale, true)
        }
        val follows = main?.let { minOf(scale, it / MAIN) } ?: scale
        val icon = ICON * follows
        val gap = GAP * follows
        val name = title?.let {
            TextFit.size(it, width - icon - gap, NAME * follows, MIN_LABEL, fontScale)
        }
        val sized = lines.map { line ->
            if (line.main) {
                LineSizes(line, null, main ?: MIN_VALUE)
            } else {
                val label = line.label.uppercase()
                LineSizes(
                    line,
                    TextFit.size(label, width, LABEL * follows, MIN_LABEL, fontScale),
                    TextFit.size(line.value, width, VALUE * follows, MIN_VALUE, fontScale, true),
                )
            }
        }
        return Fitted(name, icon, gap, sized)
    }

    internal fun heightOf(fitted: Fitted, fontScale: Float): Float {
        fun text(size: Float) = size * fontScale * TextFit.LINE_HEIGHT
        var total = fitted.name?.let { max(fitted.icon, text(it)) + fitted.gap } ?: 0f
        for (line in fitted.lines) {
            total += line.label?.let { fitted.gap + text(it) } ?: 0f
            total += text(line.value)
        }
        return total
    }

    /** The widget's padding, dp. */
    const val PADDING_SIDES = 14f
    const val PADDING_ENDS = 10f

    // The usual sizes, at scale 1: a two-by-two widget on a phone.
    private const val NAME = 13f
    private const val ICON = 16f
    private const val GAP = 4f
    private const val MAIN = 22f
    private const val LABEL = 10f
    private const val VALUE = 14f

    /** From 0.7 to 3 times the usual sizes, in steps of 0.05. */
    private const val SCALE_STEP = 0.05f
    private const val MIN_STEPS = 14
    private const val MAX_STEPS = 60
    private const val MIN_LABEL = 7f
    private const val MIN_VALUE = 8f
}
