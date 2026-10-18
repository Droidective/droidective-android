package com.droidective.android.surface

import android.content.Context
import androidx.glance.appwidget.updateAll
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
                (item as? Item.Toggle)
                    ?.let { Toggles.byId(it.toggleId) }
                    ?.let { ToggleTaps.stateOf(context, it) }
            Cells.plan(item, state, access) { id -> app.layouts.get(id)?.name }
        }
    }

    suspend fun refresh(context: Context) {
        ticks.tryEmit(Unit)
        ToggleBarWidget().updateAll(context)
        NotificationBar.refresh(context)
        TileSlots.requestRefresh(context)
    }
}
