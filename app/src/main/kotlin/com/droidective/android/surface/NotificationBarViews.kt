package com.droidective.android.surface

import android.app.PendingIntent
import android.content.Context
import android.graphics.Color
import android.view.View
import android.widget.RemoteViews
import com.droidective.android.R
import com.droidective.android.ui.Symbols
import com.droidective.android.widget.AppIcons
import com.droidective.android.widget.contrastOf
import com.droidective.core.layout.Cell
import com.droidective.core.layout.CellIcon
import com.droidective.core.layout.NotificationLook
import com.droidective.core.layout.Style

/**
 * What a notification bar draws with [style]: the collapsed row of icons at their size, and when
 * the style asks for more, the expanded view, its labels under the icons or a row per slot. Filled,
 * both sit on the style's background, and their text takes a colour that reads on it.
 *
 * @param pending what a tap on [Cell] sends, or null when there's nothing it can do.
 */
internal class NotificationBarViews(
    private val context: Context,
    private val style: Style,
    private val pending: (Cell) -> PendingIntent?,
) {
    private val density = context.resources.displayMetrics.density

    /** The text colour on a filled bar; null leaves the shade's own. */
    private val text: Int? =
        if (style.filled) (style.labelColor ?: contrastOf(style.background)).toInt() else null

    /** The 48 dp row: icons, or the text of a slot whose text is the point. */
    fun collapsed(cells: List<Cell>): RemoteViews {
        val views = filled(R.layout.notification_bar)
        val padding = px(NotificationLook.rowPadding(style))
        SLOTS.forEachIndexed { index, slot ->
            val cell = cells.getOrNull(index)
            val readout = TEXTS[index]
            views.setViewVisibility(readout, View.GONE)
            views.setViewVisibility(slot, View.GONE)
            when {
                cell == null -> Unit
                cell.readout -> {
                    views.setViewVisibility(readout, View.VISIBLE)
                    views.setTextViewText(readout, cell.label)
                    text?.let { views.setTextColor(readout, it) }
                    pending(cell)?.let { views.setOnClickPendingIntent(readout, it) }
                }
                else -> {
                    views.setViewVisibility(slot, View.VISIBLE)
                    views.setContentDescription(slot, cell.label)
                    views.setViewPadding(slot, padding, padding, padding, padding)
                    icon(views, slot, cell)
                    pending(cell)?.let { views.setOnClickPendingIntent(slot, it) }
                }
            }
        }
        return views
    }

    /** The expanded view, or null when it would only repeat the row. */
    fun expanded(cells: List<Cell>): RemoteViews? =
        when (NotificationLook.expanded(style)) {
            NotificationLook.Expanded.Row -> null
            NotificationLook.Expanded.Labelled -> labelled(cells)
            NotificationLook.Expanded.List -> list(cells)
        }

    /** Each slot's icon at its size, with its label under it, and its state when asked. */
    private fun labelled(cells: List<Cell>): RemoteViews {
        val views = filled(R.layout.notification_bar_labelled)
        val padding = px(NotificationLook.rowPadding(style))
        CELLS.forEachIndexed { index, column ->
            val cell = cells.getOrNull(index)
            views.setViewVisibility(column, if (cell == null) View.GONE else View.VISIBLE)
            if (cell == null) return@forEachIndexed
            val icon = CELL_ICONS[index]
            views.setViewPadding(icon, 0, padding, 0, padding)
            icon(views, icon, cell)
            views.setContentDescription(column, cell.label)
            label(views, CELL_LABELS[index], cell.label, style.labels || cell.readout)
            state(views, CELL_STATES[index], cell)
            pending(cell)?.let { views.setOnClickPendingIntent(column, it) }
        }
        return views
    }

    /** One row per slot: its icon, its label and its state. */
    private fun list(cells: List<Cell>): RemoteViews {
        val views = filled(R.layout.notification_bar_list)
        val padding =
            px((LIST_ICON_BOX - NotificationLook.iconSize(style).coerceAtMost(LIST_ICON_MAX)) / 2)
        ROWS.forEachIndexed { index, row ->
            val cell = cells.getOrNull(index)
            views.setViewVisibility(row, if (cell == null) View.GONE else View.VISIBLE)
            if (cell == null) return@forEachIndexed
            val icon = ROW_ICONS[index]
            views.setViewPadding(icon, padding, padding, padding, padding)
            icon(views, icon, cell)
            label(views, ROW_LABELS[index], cell.label, shown = true)
            state(views, ROW_STATES[index], cell)
            pending(cell)?.let { views.setOnClickPendingIntent(row, it) }
        }
        return views
    }

    /**
     * [layout], on the style's background when it has its own: the white rounded fill takes the
     * colour opaque, and the colour's alpha as the fill's, so the shade shows through it.
     */
    private fun filled(layout: Int): RemoteViews {
        val views = RemoteViews(context.packageName, layout)
        if (!style.filled) return views
        val color = style.background.toInt()
        views.setViewVisibility(R.id.fill, View.VISIBLE)
        views.setInt(R.id.fill, "setColorFilter", color or OPAQUE)
        views.setInt(R.id.fill, "setImageAlpha", Color.alpha(color))
        return views
    }

    private fun label(views: RemoteViews, id: Int, label: String, shown: Boolean) {
        views.setViewVisibility(id, if (shown) View.VISIBLE else View.GONE)
        views.setTextViewText(id, label)
        text?.let { views.setTextColor(id, it) }
    }

    /** The state as a person reads it, in the slot's colour, when the style asks for it. */
    private fun state(views: RemoteViews, id: Int, cell: Cell) {
        val shown = style.stateText && cell.state != null
        views.setViewVisibility(id, if (shown) View.VISIBLE else View.GONE)
        if (!shown) return
        views.setTextViewText(id, cell.state)
        views.setTextColor(id, cell.tint(style.iconOn, style.iconOff).toInt())
    }

    private fun icon(views: RemoteViews, id: Int, cell: Cell) {
        when (val icon = cell.icon) {
            is CellIcon.Symbol -> {
                val symbol = Symbols.byName[icon.name] ?: Symbols.byName.getValue("toggle_on")
                views.setImageViewResource(id, if (cell.active) symbol.filled else symbol.outlined)
                views.setInt(id, "setColorFilter", cell.tint(style.iconOn, style.iconOff).toInt())
            }
            is CellIcon.AppIcon ->
                views.setImageViewBitmap(id, AppIcons.forSlot(context, icon.packageName))
            is CellIcon.File -> views.setImageViewBitmap(id, AppIcons.file(context, icon.path))
        }
    }

    private fun px(dp: Int): Int = (dp * density).toInt()

    companion object {
        /** How many slots a notification bar draws. */
        val SIZE: Int
            get() = SLOTS.size

        private val SLOTS =
            listOf(R.id.slot0, R.id.slot1, R.id.slot2, R.id.slot3, R.id.slot4, R.id.slot5)
        private val TEXTS =
            listOf(R.id.text0, R.id.text1, R.id.text2, R.id.text3, R.id.text4, R.id.text5)
        private val CELLS =
            listOf(R.id.cell0, R.id.cell1, R.id.cell2, R.id.cell3, R.id.cell4, R.id.cell5)
        private val CELL_ICONS =
            listOf(
                R.id.cellIcon0,
                R.id.cellIcon1,
                R.id.cellIcon2,
                R.id.cellIcon3,
                R.id.cellIcon4,
                R.id.cellIcon5,
            )
        private val CELL_LABELS =
            listOf(
                R.id.cellLabel0,
                R.id.cellLabel1,
                R.id.cellLabel2,
                R.id.cellLabel3,
                R.id.cellLabel4,
                R.id.cellLabel5,
            )
        private val CELL_STATES =
            listOf(
                R.id.cellState0,
                R.id.cellState1,
                R.id.cellState2,
                R.id.cellState3,
                R.id.cellState4,
                R.id.cellState5,
            )
        private val ROWS = listOf(R.id.row0, R.id.row1, R.id.row2, R.id.row3, R.id.row4, R.id.row5)
        private val ROW_ICONS =
            listOf(
                R.id.rowIcon0,
                R.id.rowIcon1,
                R.id.rowIcon2,
                R.id.rowIcon3,
                R.id.rowIcon4,
                R.id.rowIcon5,
            )
        private val ROW_LABELS =
            listOf(
                R.id.rowLabel0,
                R.id.rowLabel1,
                R.id.rowLabel2,
                R.id.rowLabel3,
                R.id.rowLabel4,
                R.id.rowLabel5,
            )
        private val ROW_STATES =
            listOf(
                R.id.rowState0,
                R.id.rowState1,
                R.id.rowState2,
                R.id.rowState3,
                R.id.rowState4,
                R.id.rowState5,
            )

        /** A list row's icon box, 40 dp, holds an icon of up to 32 dp. */
        private const val LIST_ICON_BOX = 40
        private const val LIST_ICON_MAX = 32
        private const val OPAQUE = 0xFF000000.toInt()
    }
}
