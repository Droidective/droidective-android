package com.droidective.core.layout

import kotlin.math.max

/** The arithmetic a widget and the editor's preview share, so the preview is what you get. */
object WidgetGrid {
    /** The narrowest a grid cell gets: room for the icon and a little either side. */
    fun minCellWidth(style: Style): Int = max(MIN_CELL_DP, style.iconSize + CELL_GUTTER_DP)

    /** How many columns [count] slots take in a widget [widthDp] wide. */
    fun columns(widthDp: Float, count: Int, style: Style): Int {
        if (count <= 0) return 1
        val fixed = style.columns.takeIf { it > 0 }
        val fit = (widthDp / minCellWidth(style)).toInt()
        return (fixed ?: fit).coerceIn(1, count)
    }

    private const val MIN_CELL_DP = 52
    private const val CELL_GUTTER_DP = 24
}

/** Ready-made colour sets. Each keeps the layout's arrangement and changes only its colours. */
enum class StylePreset(val title: String) {
    Droidective("Droidective"),
    Blue("Blue"),
    Light("Light"),
    Black("Black"),
    Glass("Glass"),
    Wallpaper("Wallpaper colours");

    fun applyTo(style: Style): Style =
        when (this) {
            Droidective -> style.colours(0xF0212322, 0xFF6ECC1F, 0xFF8D948F, dynamic = false)
            Blue -> style.colours(0xF0212322, 0xFF29B6F6, 0xFF8D948F, dynamic = false)
            Light -> style.colours(0xF2FFFFFF, 0xFF1A73E8, 0xFF5F6368, dynamic = false)
            Black -> style.colours(0xFF000000, 0xFFFFFFFF, 0xFF5F6368, dynamic = false)
            Glass -> style.colours(0x40000000, 0xFFFFFFFF, 0xB3FFFFFF, dynamic = false)
            Wallpaper -> style.copy(dynamicColors = true)
        }

    /** Whether [style] wears this preset's colours. */
    fun isOn(style: Style): Boolean = applyTo(style) == style

    private fun Style.colours(background: Long, on: Long, off: Long, dynamic: Boolean) =
        copy(
            background = background,
            iconOn = on,
            iconOff = off,
            labelColor = null,
            dynamicColors = dynamic,
        )
}
