package com.droidective.android.widget

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.core.graphics.drawable.toBitmap
import androidx.core.graphics.scale
import androidx.glance.GlanceId
import androidx.glance.action.Action
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.provideContent
import com.droidective.android.automation.AutomationService
import com.droidective.android.droidective
import com.droidective.android.surface.BackgroundTaps
import com.droidective.android.surface.Launch
import com.droidective.android.surface.SlotActions
import com.droidective.android.surface.Surfaces
import com.droidective.core.layout.Cell
import com.droidective.core.layout.Display
import com.droidective.core.layout.Item
import com.droidective.core.layout.Layout
import com.droidective.core.layout.LayoutKind
import com.droidective.core.layout.Style
import com.droidective.core.layout.Tap
import java.util.UUID

/**
 * What one home-screen bar draws.
 *
 * @property deletedFor the widget's id when its layout was deleted in the app: it says so, and a
 *   tap chooses another.
 */
data class BarModel(val style: Style, val cells: List<Cell>, val deletedFor: Int? = null)

/**
 * What a newly placed widget starts as, by the kind picked from the launcher's widget list: a bar,
 * a single button, or a list. After that each is just a layout, and any can be restyled as any.
 */
enum class Starter(
    val title: String,
    val short: String,
    val toggles: List<String>,
    val style: Style,
) {
    Bar(
        "Home-screen bar",
        "Bar",
        listOf("wifi", "bluetooth", "flashlight", "auto-rotate", "do-not-disturb", "layout-bounds"),
        Style(),
    ),
    Button(
        "Button",
        "Button",
        listOf("flashlight"),
        Style(iconSize = 36, labels = true, cornerRadius = 24),
    ),
    List(
        "List",
        "List",
        listOf("wifi", "bluetooth", "do-not-disturb", "layout-bounds", "show-touches"),
        Style(display = Display.List, stateText = true, cornerRadius = 20, padding = 8),
    ),
}

/** A home-screen widget: any layout of toggles, apps, actions and tools, MicroToggles style. */
class ToggleBarWidget(private val starter: Starter = Starter.Bar) : GlanceAppWidget() {
    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val widgetId = GlanceAppWidgetManager(context).getAppWidgetId(id)
        val first = load(context, widgetId, starter)
        provideContent {
            val model by
                produceState(first) {
                    Surfaces.changes.collect { value = load(context, widgetId, starter) }
                }
            Bar(model)
        }
    }

    companion object {
        /** The toggles a new notification bar starts with. */
        val starterToggles = Starter.Bar.toggles

        suspend fun load(context: Context, widgetId: Int, starter: Starter): BarModel {
            val layout =
                ensureLayout(context, widgetId, starter)
                    ?: return BarModel(Style(), emptyList(), deletedFor = widgetId)
            return BarModel(layout.style, Surfaces.cells(context, layout))
        }

        /**
         * The widget's layout. The first time it's drawn that's the layout the app is placing, if
         * it is, or else a new starter. Null once its layout was deleted in the app: an app can't
         * take a widget off the home screen, so it says so until another layout is chosen for it.
         */
        fun ensureLayout(context: Context, widgetId: Int, starter: Starter): Layout? {
            val layouts = context.droidective.layouts
            layouts.layoutFor(widgetId)?.let {
                return it
            }
            if (layouts.isBound(widgetId)) return null
            layouts.takePlacing(System.currentTimeMillis())?.let(layouts::get)?.let {
                layouts.bind(widgetId, it.id)
                AutomationService.sync(context)
                return it
            }
            return newStarter(context, widgetId, starter)
        }

        /**
         * Shows [layoutId] on [widgetId]. The starter the widget was given when it arrived goes, if
         * it's still as it started and nothing else shows it: choosing a layout of one's own
         * shouldn't leave a spare "Home-screen bar" behind.
         */
        fun show(context: Context, widgetId: Int, layoutId: String) {
            val layouts = context.droidective.layouts
            val before = layouts.layoutFor(widgetId)
            layouts.bind(widgetId, layoutId)
            val spare =
                before != null &&
                    before.id != layoutId &&
                    before.id == starterId(widgetId) &&
                    layouts.widgetsShowing(before.id).isEmpty() &&
                    isUntouched(before)
            if (spare) layouts.delete(before.id)
            AutomationService.sync(context)
        }

        private fun starterId(widgetId: Int) = "bar-$widgetId"

        private fun isUntouched(layout: Layout): Boolean =
            Starter.entries.any { starter ->
                layout.name == starter.title &&
                    layout.style == starter.style &&
                    layout.items.map { (it as? Item.Toggle)?.toggleId } == starter.toggles
            }

        /** A new [starter] layout for [widgetId], which shows it from now on. */
        fun newStarter(context: Context, widgetId: Int, starter: Starter): Layout {
            val layouts = context.droidective.layouts
            val layout =
                Layout(
                    id = starterId(widgetId),
                    kind = LayoutKind.Bar,
                    name = starter.title,
                    items = starter.toggles.map { Item.Toggle(UUID.randomUUID().toString(), it) },
                    style = starter.style,
                    editedAt = System.currentTimeMillis(),
                )
            layouts.save(layout)
            layouts.bind(widgetId, layout.id)
            AutomationService.sync(context)
            return layout
        }
    }
}

/** Null when the target can't be opened safely; the cell then draws but does nothing. */
internal fun actionFor(context: Context, cell: Cell): Action? =
    when (val tap = cell.tap) {
        is Tap.RunToggle ->
            actionRunCallback<RunToggleAction>(
                actionParametersOf(RunToggleAction.toggleKey to tap.toggleId)
            )
        is Tap.RunCommand ->
            actionRunCallback<RunCommandAction>(
                actionParametersOf(RunCommandAction.commandKey to tap.commandId)
            )
        is Tap.RunAutomation ->
            actionRunCallback<RunAutomationAction>(
                actionParametersOf(RunAutomationAction.automationKey to tap.automationId)
            )
        is Tap.RunAction ->
            actionRunCallback<RunActionAction>(
                actionParametersOf(
                    RunActionAction.actionKey to SlotActions.encode(tap.action),
                    RunActionAction.labelKey to tap.label,
                )
            )
        is Tap.Copy ->
            actionRunCallback<CopyAddressAction>(
                actionParametersOf(CopyAddressAction.addressKey to tap.text)
            )
        is Tap.Open ->
            Launch.intentFor(context, tap.target)?.let {
                actionStartActivity(OpenActivity.wrap(context, it))
            }
    }

/** Runs a toggle tapped on a widget, in the background. */
class RunToggleAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters,
    ) {
        val toggleId = parameters[toggleKey] ?: return
        BackgroundTaps.run(context, Tap.RunToggle(toggleId))
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
        val commandId = parameters[commandKey] ?: return
        BackgroundTaps.run(context, Tap.RunCommand(commandId))
    }

    companion object {
        val commandKey = ActionParameters.Key<String>("command")
    }
}

/** Runs one of the person's automations, tapped on a widget. */
class RunAutomationAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters,
    ) {
        val automationId = parameters[automationKey] ?: return
        BackgroundTaps.run(context, Tap.RunAutomation(automationId))
    }

    companion object {
        val automationKey = ActionParameters.Key<String>("automation")
    }
}

/** Runs one automation action, tapped on a widget. */
class RunActionAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters,
    ) {
        val action = parameters[actionKey]?.let(SlotActions::decode) ?: return
        BackgroundTaps.run(context, Tap.RunAction(action, parameters[labelKey].orEmpty()))
    }

    companion object {
        val actionKey = ActionParameters.Key<String>("action")
        val labelKey = ActionParameters.Key<String>("label")
    }
}

/** One kind of home-screen widget in the launcher's list. Removing one forgets its binding. */
abstract class WidgetReceiver(starter: Starter) : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = ToggleBarWidget(starter)

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        super.onDeleted(context, appWidgetIds)
        appWidgetIds.forEach { context.droidective.layouts.unbind(it) }
        AutomationService.sync(context)
    }

    /** A launcher restoring its widgets (a backup, a new phone) gives them new ids. */
    override fun onRestored(context: Context, oldWidgetIds: IntArray, newWidgetIds: IntArray) {
        super.onRestored(context, oldWidgetIds, newWidgetIds)
        context.droidective.layouts.restored(oldWidgetIds.toList(), newWidgetIds.toList())
    }

    companion object {
        /** Every kind, as the launcher knows them. */
        val kinds =
            mapOf(
                ToggleBarReceiver::class.java to Starter.Bar,
                ButtonWidgetReceiver::class.java to Starter.Button,
                ListWidgetReceiver::class.java to Starter.List,
            )

        /** Every placed Droidective widget, of any kind. */
        fun placedIds(context: Context): Set<Int> {
            val manager = android.appwidget.AppWidgetManager.getInstance(context)
            return kinds.keys
                .flatMap { manager.getAppWidgetIds(ComponentName(context, it)).toList() }
                .toSet()
        }

        /** What [widgetId] started as, from the receiver the launcher placed it with. */
        fun starterFor(context: Context, widgetId: Int): Starter {
            val provider =
                android.appwidget.AppWidgetManager.getInstance(context)
                    .getAppWidgetInfo(widgetId)
                    ?.provider
                    ?.className
            return kinds.entries.firstOrNull { it.key.name == provider }?.value ?: Starter.Bar
        }
    }
}

class ToggleBarReceiver : WidgetReceiver(Starter.Bar)

class ButtonWidgetReceiver : WidgetReceiver(Starter.Button)

class ListWidgetReceiver : WidgetReceiver(Starter.List)

/** App and shortcut icons, sized for a widget slot and cached, so widget updates stay small. */
object AppIcons {
    private val cache = android.util.LruCache<String, Bitmap>(64)

    fun bitmap(context: Context, packageName: String, sizeDp: Int = DEFAULT_DP): Bitmap {
        val px = (sizeDp * context.resources.displayMetrics.density).toInt()
        return cache.get("$packageName@$px")
            ?: run {
                val drawable =
                    try {
                        context.packageManager.getApplicationIcon(packageName)
                    } catch (_: android.content.pm.PackageManager.NameNotFoundException) {
                        context.packageManager.defaultActivityIcon
                    }
                drawable.toBitmap(px, px).also { cache.put("$packageName@$px", it) }
            }
    }

    fun file(context: Context, path: String, sizeDp: Int = DEFAULT_DP): Bitmap {
        val px = (sizeDp * context.resources.displayMetrics.density).toInt()
        return cache.get("$path@$px")
            ?: run {
                val decoded = BitmapFactory.decodeFile(java.io.File(context.filesDir, path).path)
                (decoded?.scale(px, px)
                        ?: context.packageManager.defaultActivityIcon.toBitmap(px, px))
                    .also { cache.put("$path@$px", it) }
            }
    }

    private const val DEFAULT_DP = 28
}
