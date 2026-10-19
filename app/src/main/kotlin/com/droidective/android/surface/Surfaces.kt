package com.droidective.android.surface

import android.content.Context
import androidx.glance.appwidget.updateAll
import com.droidective.android.automation.AutomationService
import com.droidective.android.droidective
import com.droidective.android.widget.ToggleBarWidget
import com.droidective.core.layout.Cell
import com.droidective.core.layout.Cells
import com.droidective.core.layout.Item
import com.droidective.core.layout.Layout
import com.droidective.core.toggles.Toggles
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

/** Loads what a surface draws, and tells every surface when something changed. */
object Surfaces {
    private val ticks = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** Fires after any toggle changes, so open widget sessions and screens re-read. */
    val changes: SharedFlow<Unit> = ticks.asSharedFlow()

    /** The cells for [layout], with each toggle's current state and the settled access. */
    suspend fun cells(context: Context, layout: Layout): List<Cell> {
        val app = context.droidective
        val access = app.access.settled()
        return layout.items.mapNotNull { item ->
            val state =
                when (item) {
                    is Item.Toggle ->
                        Toggles.byId(item.toggleId)?.let { ToggleTaps.stateOf(context, it) }
                    is Item.Command ->
                        app.commands.get(item.commandId)?.let { CommandTaps.stateOf(context, it) }
                    else -> null
                }
            Cells.plan(
                item,
                state,
                access,
                folderName = { id -> app.layouts.get(id)?.name },
                command = app.commands::get,
            )
        }
    }

    /**
     * [refresh] in the app's scope, for a screen that answers first and lets widgets, tiles and the
     * notification bar catch up behind it. Leaving the screen doesn't cut it short.
     */
    fun refreshBehind(context: Context) {
        val app = context.droidective
        app.scope.launch { refresh(app) }
    }

    /**
     * Redraws every surface, then starts or stops the automation service to match what's placed: a
     * surface added or removed changes whether [LiveSurfaces] needs it.
     */
    suspend fun refresh(context: Context) {
        redraw(context)
        AutomationService.sync(context)
    }

    /** Redraws every surface with what each toggle reads now. */
    suspend fun redraw(context: Context) {
        ticks.tryEmit(Unit)
        ToggleBarWidget().updateAll(context)
        NotificationBar.refresh(context)
        TileSlots.requestRefresh(context)
    }
}
