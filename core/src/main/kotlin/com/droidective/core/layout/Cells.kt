package com.droidective.core.layout

import com.droidective.core.Access
import com.droidective.core.AccessLevel
import com.droidective.core.toggles.ToggleDef
import com.droidective.core.toggles.Toggles
import com.droidective.core.toggles.Write

/** What a tap on a cell does. Surfaces turn these into PendingIntents or calls. */
sealed interface Tap {
    /** Runs in the background, then the surface redraws. No activity starts. */
    data class RunToggle(val toggleId: String) : Tap

    /** Starts an activity straight from the tap, never through a trampoline. */
    data class Open(val target: Target) : Tap
}

sealed interface Target {
    data class App(val packageName: String, val activityName: String) : Target

    /** Another app's shortcut intent. Started only as an activity, with grant flags stripped. */
    data class Shortcut(val intentUri: String) : Target

    data class DeepLink(val uri: String) : Target

    /** A system Settings screen by intent action: a fallback, or a Settings-screen item. */
    data class SystemScreen(val action: String) : Target

    data class Folder(val folderId: String) : Target

    /** A toggle whose job is a screen of the app's own, such as the brightness slider. */
    data class ToggleScreen(val toggleId: String) : Target

    data class Setup(val level: AccessLevel) : Target
}

sealed interface CellIcon {
    data class Symbol(val name: String) : CellIcon

    data class AppIcon(val packageName: String) : CellIcon

    data class File(val path: String) : CellIcon
}

/**
 * One drawn slot.
 *
 * @property active draw in the "on" colour.
 * @property degraded the toggle's level is missing, so the tap does its stand-in instead.
 */
data class Cell(
    val key: String,
    val label: String,
    val icon: CellIcon,
    val active: Boolean,
    val degraded: Boolean,
    val tap: Tap,
)

/** Decides how each item draws and what a tap on it does, given the current access. */
object Cells {
    /**
     * @param state the toggle's current state id when [item] is a toggle.
     * @param folderName looks up a folder's name for its label.
     * @return null for an item that points at something gone, such as a removed toggle.
     */
    fun plan(item: Item, state: String?, access: Access, folderName: (String) -> String?): Cell? =
        when (item) {
            is Item.Toggle ->
                Toggles.byId(item.toggleId)?.let { toggleCell(item.key, it, state, access) }
            is Item.App ->
                Cell(
                    item.key,
                    item.label,
                    CellIcon.AppIcon(item.packageName),
                    active = false,
                    degraded = false,
                    Tap.Open(Target.App(item.packageName, item.activityName)),
                )
            is Item.Shortcut ->
                Cell(
                    item.key,
                    item.label,
                    item.iconFile?.let(CellIcon::File) ?: CellIcon.Symbol("open_in_new"),
                    active = false,
                    degraded = false,
                    Tap.Open(Target.Shortcut(item.intentUri)),
                )
            is Item.Folder ->
                folderName(item.folderId)?.let { name ->
                    Cell(
                        item.key,
                        name,
                        CellIcon.Symbol("folder"),
                        active = false,
                        degraded = false,
                        Tap.Open(Target.Folder(item.folderId)),
                    )
                }
            is Item.DeepLink ->
                Cell(
                    item.key,
                    item.label,
                    CellIcon.Symbol("link"),
                    active = false,
                    degraded = false,
                    Tap.Open(Target.DeepLink(item.uri)),
                )
            is Item.SettingsScreen ->
                Cell(
                    item.key,
                    item.label,
                    CellIcon.Symbol("settings"),
                    active = false,
                    degraded = false,
                    Tap.Open(Target.SystemScreen(item.action)),
                )
        }

    private fun toggleCell(key: String, toggle: ToggleDef, state: String?, access: Access): Cell? {
        if (!toggle.isBuilt) return null
        val write = toggle.write
        val inPlace = access.allows(toggle.level)
        val tap =
            when {
                write is Write.Api && write.api.opensScreen ->
                    Tap.Open(Target.ToggleScreen(toggle.id))
                inPlace -> Tap.RunToggle(toggle.id)
                toggle.fallback != null ->
                    Tap.Open(Target.SystemScreen(toggle.fallback.settingsAction))
                else -> Tap.Open(Target.Setup(toggle.level))
            }
        return Cell(
            key = key,
            label = toggle.title,
            icon = CellIcon.Symbol(toggle.icon),
            active = toggle.isActive(state),
            degraded = !inPlace,
            tap = tap,
        )
    }
}
