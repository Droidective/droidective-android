package com.droidective.android.widget

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.clickable
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.droidective.android.ui.Symbols
import com.droidective.core.layout.Cell
import com.droidective.core.layout.CellIcon
import com.droidective.core.layout.CellShape
import com.droidective.core.layout.Display
import com.droidective.core.layout.Style
import com.droidective.core.layout.WidgetGrid

/**
 * The colours one widget draws with: the layout's own, or the wallpaper's through [GlanceTheme]
 * when it asks for them (Android 12+; earlier versions get Material's baseline).
 */
private class WidgetColors(
    val background: ColorProvider,
    val on: ColorProvider,
    val off: ColorProvider,
    val label: ColorProvider,
    val chipOn: ColorProvider,
    val chipOff: ColorProvider,
)

@Composable
private fun colorsFor(style: Style): WidgetColors {
    if (style.dynamicColors) {
        val theme = GlanceTheme.colors
        return WidgetColors(
            background = theme.widgetBackground,
            on = theme.primary,
            off = theme.onSurfaceVariant,
            label = theme.onSurface,
            chipOn = theme.primaryContainer,
            chipOff = theme.surfaceVariant,
        )
    }
    return WidgetColors(
        background = ColorProvider(Color(style.background)),
        on = ColorProvider(Color(style.iconOn)),
        off = ColorProvider(Color(style.iconOff)),
        label = ColorProvider(Color(style.labelColor ?: style.iconOff)),
        chipOn = ColorProvider(Color(chip(style.iconOn, CHIP_ON_ALPHA))),
        chipOff = ColorProvider(Color(chip(contrastOf(style.background), CHIP_OFF_ALPHA))),
    )
}

/** [argb] at [alpha] (0–255): a chip is the icon's colour, faint. */
private fun chip(argb: Long, alpha: Int): Long = (alpha.toLong() shl 24) or (argb and 0xFFFFFF)

/** White on a dark background, black on a light one. */
private fun contrastOf(background: Long): Long {
    val r = (background shr 16) and 0xFF
    val g = (background shr 8) and 0xFF
    val b = background and 0xFF
    val luminance = (LUMA_R * r + LUMA_G * g + LUMA_B * b) / 255
    return if (luminance > HALF) 0xFF000000 else 0xFFFFFFFF
}

/** One widget: a grid of icons or a list of rows, over the layout's background. */
@Composable
internal fun Bar(model: BarModel) {
    val style = model.style
    val colors = colorsFor(style)
    Box(
        modifier =
            GlanceModifier.fillMaxSize()
                .background(colors.background)
                .cornerRadius(style.cornerRadius.dp)
                .padding(style.padding.dp),
        contentAlignment = Alignment.Center,
    ) {
        when {
            model.cells.isEmpty() ->
                Text(
                    "Long-press to add toggles",
                    style = TextStyle(color = colors.off, fontSize = 12.sp),
                )
            style.display == Display.List -> Rows(model.cells, style, colors)
            else -> Grid(model.cells, style, colors)
        }
    }
}

@Composable
private fun Grid(cells: List<Cell>, style: Style, colors: WidgetColors) {
    val columns = WidgetGrid.columns(LocalSize.current.width.value, cells.size, style)
    Column(GlanceModifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
        cells.chunked(columns).forEach { row ->
            Row(
                GlanceModifier.fillMaxWidth().defaultWeight(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                row.forEach { cell ->
                    GridCell(cell, style, colors, GlanceModifier.defaultWeight())
                }
                // Keep the last row's cells the same width as the rows above.
                repeat(columns - row.size) { Spacer(GlanceModifier.defaultWeight()) }
            }
        }
    }
}

@Composable
private fun GridCell(cell: Cell, style: Style, colors: WidgetColors, modifier: GlanceModifier) {
    val action = actionFor(LocalContext.current, cell)
    Column(
        modifier = (action?.let { modifier.clickable(it) } ?: modifier).padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(cell, style, colors)
        if (style.labels || cell.readout) {
            Spacer(GlanceModifier.height(2.dp))
            Text(
                cell.label,
                maxLines = 1,
                style = TextStyle(color = colors.label, fontSize = style.labelSize.sp),
            )
        }
        if (style.stateText && cell.state != null) {
            Text(
                cell.state.orEmpty(),
                maxLines = 1,
                style =
                    TextStyle(color = tintOf(cell, colors), fontSize = (style.labelSize - 1).sp),
            )
        }
    }
}

/** The list display: one row per slot, its label beside the icon, scrolling when there are many. */
@Composable
private fun Rows(cells: List<Cell>, style: Style, colors: WidgetColors) {
    val context = LocalContext.current
    LazyColumn(GlanceModifier.fillMaxSize()) {
        items(cells, itemId = { it.key.hashCode().toLong() }) { cell ->
            val action = actionFor(context, cell)
            val row = GlanceModifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp)
            Row(
                modifier = action?.let { row.clickable(it) } ?: row,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(cell, style, colors)
                Spacer(GlanceModifier.width(12.dp))
                Column(GlanceModifier.defaultWeight()) {
                    Text(
                        cell.label,
                        maxLines = 1,
                        style =
                            TextStyle(
                                color = colors.label,
                                fontSize = (style.labelSize + 3).sp,
                                fontWeight = FontWeight.Medium,
                            ),
                    )
                    if (style.stateText && cell.state != null) {
                        Text(
                            cell.state.orEmpty(),
                            maxLines = 1,
                            style =
                                TextStyle(
                                    color = tintOf(cell, colors),
                                    fontSize = style.labelSize.sp,
                                ),
                        )
                    }
                }
            }
        }
    }
}

/**
 * A slot's own colour shows while it's on, and always on a slot with no state (an app, a tool, an
 * action), which would otherwise never show it.
 */
private fun tintOf(cell: Cell, colors: WidgetColors): ColorProvider {
    val own = cell.color?.takeIf { cell.active || cell.state == null }
    return when {
        own != null -> ColorProvider(Color(own))
        cell.active -> colors.on
        else -> colors.off
    }
}

/** The icon, on its chip when the style has one, with the amber dot of a stand-in. */
@Composable
private fun Icon(cell: Cell, style: Style, colors: WidgetColors) {
    val shape = style.cellShape
    val inner = GlanceModifier.size(style.iconSize.dp)
    Box(contentAlignment = Alignment.TopEnd) {
        if (shape == CellShape.None) {
            CellImage(cell, style.iconSize, tintOf(cell, colors), inner)
        } else {
            val chipSize = (style.iconSize * CHIP_SCALE).toInt()
            val radius = if (shape == CellShape.Circle) chipSize / 2 else chipSize / 4
            Box(
                GlanceModifier.size(chipSize.dp)
                    .cornerRadius(radius.dp)
                    .background(if (cell.active) colors.chipOn else colors.chipOff),
                contentAlignment = Alignment.Center,
            ) {
                CellImage(cell, style.iconSize, tintOf(cell, colors), inner)
            }
        }
        if (cell.degraded) {
            Box(
                GlanceModifier.size(8.dp)
                    .cornerRadius(4.dp)
                    .background(ColorProvider(Color(STAND_IN_DOT)))
            ) {}
        }
    }
}

@Composable
private fun CellImage(cell: Cell, sizeDp: Int, tint: ColorProvider, modifier: GlanceModifier) {
    val context = LocalContext.current
    when (val icon = cell.icon) {
        is CellIcon.Symbol -> {
            val symbol = Symbols.byName[icon.name] ?: Symbols.byName.getValue("toggle_on")
            Image(
                provider = ImageProvider(if (cell.active) symbol.filled else symbol.outlined),
                contentDescription = cell.label,
                colorFilter = ColorFilter.tint(tint),
                modifier = modifier,
            )
        }
        is CellIcon.AppIcon ->
            Image(
                provider = ImageProvider(AppIcons.bitmap(context, icon.packageName, sizeDp)),
                contentDescription = cell.label,
                modifier = modifier,
            )
        is CellIcon.File ->
            Image(
                provider = ImageProvider(AppIcons.file(context, icon.path, sizeDp)),
                contentDescription = cell.label,
                modifier = modifier,
            )
    }
}

/** The amber of the Shell level: this toggle is running on its stand-in. */
private const val STAND_IN_DOT = 0xFFF0B552

/** A chip is half again the icon's size, so the icon sits inside it with room around. */
private const val CHIP_SCALE = 1.6f
private const val CHIP_ON_ALPHA = 0x40
private const val CHIP_OFF_ALPHA = 0x14
private const val LUMA_R = 0.299
private const val LUMA_G = 0.587
private const val LUMA_B = 0.114
private const val HALF = 0.5
