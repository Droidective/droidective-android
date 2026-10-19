package com.droidective.android.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import androidx.core.graphics.scale
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.Action
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
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
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.droidective.android.automation.AutomationService
import com.droidective.android.droidective
import com.droidective.android.surface.CommandTaps
import com.droidective.android.surface.Launch
import com.droidective.android.surface.Problems
import com.droidective.android.surface.Surfaces
import com.droidective.android.surface.ToggleTaps
import com.droidective.android.ui.Symbols
import com.droidective.core.layout.Cell
import com.droidective.core.layout.CellIcon
import com.droidective.core.layout.Item
import com.droidective.core.layout.Layout
import com.droidective.core.layout.LayoutKind
import com.droidective.core.layout.Style
import com.droidective.core.layout.Tap
import com.droidective.core.toggles.Outcome
import com.droidective.core.toggles.Toggles
import java.util.UUID
import kotlin.math.max

/** What one home-screen bar draws. */
data class BarModel(val style: Style, val cells: List<Cell>)

/** A home-screen bar: any layout of toggles, apps and shortcuts, MicroToggles style. */
class ToggleBarWidget : GlanceAppWidget() {
    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val widgetId = GlanceAppWidgetManager(context).getAppWidgetId(id)
        val first = load(context, widgetId)
        provideContent {
            val model by
                produceState(first) { Surfaces.changes.collect { value = load(context, widgetId) } }
            Bar(model)
        }
    }

    companion object {
        /** The bar a newly added widget starts with, before it's configured. */
        val starterToggles =
            listOf(
                "wifi",
                "bluetooth",
                "flashlight",
                "auto-rotate",
                "do-not-disturb",
                "layout-bounds",
            )

        suspend fun load(context: Context, widgetId: Int): BarModel {
            val layout = ensureLayout(context, widgetId)
            return BarModel(layout.style, Surfaces.cells(context, layout))
        }

        /** The widget's layout, creating the starter bar the first time it's drawn. */
        fun ensureLayout(context: Context, widgetId: Int): Layout {
            val layouts = context.droidective.layouts
            layouts.layoutFor(widgetId)?.let {
                return it
            }
            val layout =
                Layout(
                    id = "bar-$widgetId",
                    kind = LayoutKind.Bar,
                    name = "Home-screen bar",
                    items = starterToggles.map { Item.Toggle(UUID.randomUUID().toString(), it) },
                    editedAt = System.currentTimeMillis(),
                )
            layouts.save(layout)
            layouts.bind(widgetId, layout.id)
            AutomationService.sync(context)
            return layout
        }
    }
}

private const val CELL_MIN_WIDTH_DP = 52f
private const val ICON_DP = 28

@Composable
private fun Bar(model: BarModel) {
    val size = LocalSize.current
    val columns =
        max(
            1,
            minOf(
                model.cells.size,
                (size.width.value / CELL_MIN_WIDTH_DP).toInt().coerceAtLeast(1),
            ),
        )
    val rows = model.cells.chunked(columns)
    Box(
        modifier =
            GlanceModifier.fillMaxSize()
                .background(ColorProvider(Color(model.style.background)))
                .cornerRadius(8.dp)
                .padding(4.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (model.cells.isEmpty()) {
            Text(
                "Long-press to add toggles",
                style =
                    TextStyle(color = ColorProvider(Color(model.style.iconOff)), fontSize = 12.sp),
            )
        } else {
            Column(GlanceModifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                rows.forEach { row ->
                    Row(
                        GlanceModifier.fillMaxWidth().defaultWeight(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        row.forEach { cell ->
                            CellView(cell, model.style, GlanceModifier.defaultWeight())
                        }
                        // Keep the last row's cells the same width as the rows above.
                        repeat(columns - row.size) { Spacer(GlanceModifier.defaultWeight()) }
                    }
                }
            }
        }
    }
}

@Composable
private fun CellView(cell: Cell, style: Style, modifier: GlanceModifier) {
    val context = LocalContext.current
    val tint = Color(if (cell.active) style.iconOn else style.iconOff)
    val action = actionFor(context, cell)
    Column(
        modifier = (action?.let { modifier.clickable(it) } ?: modifier).padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(contentAlignment = Alignment.TopEnd) {
            CellImage(cell, tint)
            if (cell.degraded) {
                Box(
                    GlanceModifier.size(8.dp)
                        .cornerRadius(4.dp)
                        .background(ColorProvider(Color(STAND_IN_DOT)))
                ) {}
            }
        }
        if (style.labels) {
            Spacer(GlanceModifier.height(2.dp))
            Text(
                cell.label,
                maxLines = 1,
                style = TextStyle(color = ColorProvider(Color(style.iconOff)), fontSize = 10.sp),
            )
        }
    }
}

@Composable
private fun CellImage(cell: Cell, tint: Color) {
    val context = LocalContext.current
    when (val icon = cell.icon) {
        is CellIcon.Symbol -> {
            val symbol = Symbols.byName[icon.name] ?: Symbols.byName.getValue("toggle_on")
            Image(
                provider = ImageProvider(if (cell.active) symbol.filled else symbol.outlined),
                contentDescription = cell.label,
                colorFilter = ColorFilter.tint(ColorProvider(tint)),
                modifier = GlanceModifier.size(ICON_DP.dp),
            )
        }
        is CellIcon.AppIcon ->
            Image(
                provider = ImageProvider(AppIcons.bitmap(context, icon.packageName)),
                contentDescription = cell.label,
                modifier = GlanceModifier.size(ICON_DP.dp),
            )
        is CellIcon.File ->
            Image(
                provider = ImageProvider(AppIcons.file(context, icon.path)),
                contentDescription = cell.label,
                modifier = GlanceModifier.size(ICON_DP.dp),
            )
    }
}

/** Null when the target can't be opened safely; the cell then draws but does nothing. */
private fun actionFor(context: Context, cell: Cell): Action? =
    when (val tap = cell.tap) {
        is Tap.RunToggle ->
            actionRunCallback<RunToggleAction>(
                actionParametersOf(RunToggleAction.toggleKey to tap.toggleId)
            )
        is Tap.RunCommand ->
            actionRunCallback<RunCommandAction>(
                actionParametersOf(RunCommandAction.commandKey to tap.commandId)
            )
        is Tap.Open ->
            Launch.intentFor(context, tap.target)?.let {
                actionStartActivity(OpenActivity.wrap(context, it))
            }
    }

/** The amber of the Shell level: this toggle is running on its stand-in. */
private const val STAND_IN_DOT = 0xFFF0B552

/** Runs a toggle tapped on a widget, in the background. */
class RunToggleAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters,
    ) {
        val toggle = parameters[toggleKey]?.let { Toggles.byId(it) } ?: return
        val outcome = ToggleTaps.advance(context, toggle)
        if (outcome !is Outcome.Applied) Problems.report(context, toggle, outcome)
    }

    companion object {
        val toggleKey = ActionParameters.Key<String>("toggle")
    }
}

/** Runs a custom command tapped on a widget, in the background. */
class RunCommandAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters,
    ) {
        val command = parameters[commandKey]?.let { context.droidective.commands.get(it) } ?: return
        Problems.reportCommand(context, command, CommandTaps.run(context, command))
    }

    companion object {
        val commandKey = ActionParameters.Key<String>("command")
    }
}

class ToggleBarReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = ToggleBarWidget()

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        super.onDeleted(context, appWidgetIds)
        appWidgetIds.forEach { context.droidective.layouts.unbind(it) }
        AutomationService.sync(context)
    }
}

/** App and shortcut icons, sized for a widget slot and cached, so widget updates stay small. */
object AppIcons {
    private val cache = android.util.LruCache<String, Bitmap>(64)

    fun bitmap(context: Context, packageName: String): Bitmap =
        cache.get(packageName)
            ?: run {
                val px = (ICON_DP * context.resources.displayMetrics.density).toInt()
                val drawable =
                    try {
                        context.packageManager.getApplicationIcon(packageName)
                    } catch (_: android.content.pm.PackageManager.NameNotFoundException) {
                        context.packageManager.defaultActivityIcon
                    }
                drawable.toBitmap(px, px).also { cache.put(packageName, it) }
            }

    fun file(context: Context, path: String): Bitmap =
        cache.get(path)
            ?: run {
                val px = (ICON_DP * context.resources.displayMetrics.density).toInt()
                val decoded = BitmapFactory.decodeFile(java.io.File(context.filesDir, path).path)
                (decoded?.scale(px, px)
                        ?: context.packageManager.defaultActivityIcon.toBitmap(px, px))
                    .also { cache.put(path, it) }
            }
}
