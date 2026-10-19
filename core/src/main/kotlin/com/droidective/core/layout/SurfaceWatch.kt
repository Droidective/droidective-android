package com.droidective.core.layout

import com.droidective.core.automations.Action

/**
 * What a listener has to follow for every placed surface to show the truth: the toggles (and the
 * actions that set one) and the stateful commands on home-screen bars, notification bars and Quick
 * Settings tiles. A folder's items aren't on screen until it opens, and it reads them then.
 */
data class SurfaceWatch(val toggleIds: Set<String>, val commandIds: Set<String>) {
    companion object {
        /**
         * @param shown the layouts on screen: bars a widget shows and every notification bar.
         * @param tiles the items the Quick Settings tile slots hold.
         */
        fun of(shown: List<Layout>, tiles: Collection<Item>): SurfaceWatch {
            val items = shown.filter { it.kind != LayoutKind.Folder }.flatMap { it.items } + tiles
            val setters = items.mapNotNull {
                ((it as? Item.Action)?.action as? Action.SetToggle)?.toggleId
            }
            return SurfaceWatch(
                toggleIds =
                    (items.filterIsInstance<Item.Toggle>().map { it.toggleId } + setters).toSet(),
                commandIds = items.filterIsInstance<Item.Command>().map { it.commandId }.toSet(),
            )
        }
    }
}
