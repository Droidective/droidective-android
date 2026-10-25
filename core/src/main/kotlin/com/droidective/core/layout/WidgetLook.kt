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

/**
 * Whether this kind draws with the whole [Style]. A notification bar draws the part of it the shade
 * leaves room for ([NotificationLook]); a folder takes only the on and off colours, since the
 * folder screen draws everything else.
 */
val LayoutKind.usesWholeStyle: Boolean
    get() = this == LayoutKind.Bar

/**
 * What a preview of this layout draws with: its own style for a home-screen widget, what the shade
 * draws for a notification bar, and a folder's colours alone, as a grid on a clear ground.
 */
fun Layout.previewStyle(): Style =
    when (kind) {
        LayoutKind.Bar -> style
        LayoutKind.Notification -> NotificationLook.preview(style)
        LayoutKind.Folder ->
            Style(background = CLEAR, iconOn = style.iconOn, iconOff = style.iconOff)
    }

/**
 * How a notification bar draws its [Style]. Collapsed, Android gives a custom notification one 48
 * dp row, so that's icons at their size and nothing under them; expanded there's room for labels,
 * or for a row per slot. Its padding is fixed, and so are the corners of its own background.
 */
object NotificationLook {
    /** What fits the 48 dp collapsed row, with a little room around the icon. */
    val ICON_SIZES = 16..40

    /** What the expanded notification shows, beyond the collapsed row. */
    enum class Expanded {
        /** Nothing more: it expands to the same row. */
        Row,

        /** The row again, with each slot's label (and state, when asked) under its icon. */
        Labelled,

        /** One row per slot: its icon, its label and its state. */
        List,
    }

    fun iconSize(style: Style): Int = style.iconSize.coerceIn(ICON_SIZES)

    /** The space around an icon in the collapsed row, in dp, so it draws at [iconSize]. */
    fun rowPadding(style: Style): Int = (ROW_DP - iconSize(style)) / 2

    fun expanded(style: Style): Expanded =
        when {
            style.display == Display.List -> Expanded.List
            style.labels || style.stateText -> Expanded.Labelled
            else -> Expanded.Row
        }

    /** What the editor previews: what the expanded notification shows, on its own background. */
    fun preview(style: Style): Style =
        Style(
            background = if (style.filled) style.background else CLEAR,
            iconOn = style.iconOn,
            iconOff = style.iconOff,
            labels = style.labels,
            display = style.display,
            iconSize = iconSize(style),
            stateText = style.stateText,
            labelColor = style.labelColor,
            filled = style.filled,
            cornerRadius = CORNER_DP,
        )

    private const val ROW_DP = 48

    /** The corners of its own background, as `notification_fill.xml` draws them. */
    private const val CORNER_DP = 16
}

private const val CLEAR = 0x00000000L

/** The Quick Settings tile slots, numbered from 1. */
object TileSlotNumbers {
    /** The lowest of 1 to [count] that [taken] doesn't hold, or null when every slot is used. */
    fun firstFree(taken: Set<Int>, count: Int): Int? = (1..count).firstOrNull { it !in taken }
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
