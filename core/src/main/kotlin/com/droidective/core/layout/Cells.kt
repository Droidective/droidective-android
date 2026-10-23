package com.droidective.core.layout

import com.droidective.core.Access
import com.droidective.core.AccessLevel
import com.droidective.core.apps.AppAction
import com.droidective.core.automations.Action
import com.droidective.core.automations.Automation
import com.droidective.core.automations.level
import com.droidective.core.commands.CustomCommand
import com.droidective.core.features.Features
import com.droidective.core.toggles.ToggleDef
import com.droidective.core.toggles.Toggles
import com.droidective.core.toggles.Write

/** What a tap on a cell does. Surfaces turn these into PendingIntents or calls. */
sealed interface Tap {
    /** Runs in the background, then the surface redraws. No activity starts. */
    data class RunToggle(val toggleId: String) : Tap

    /** Runs a saved custom command in the background, as the shell user. */
    data class RunCommand(val commandId: String) : Tap

    /** Runs one of the person's automations now, in the background. */
    data class RunAutomation(val automationId: String) : Tap

    /** Runs one automation action in the background, such as force-stopping an app. */
    data class RunAction(val action: Action, val label: String) : Tap

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

    /** One of the app's tool screens, such as the proxy screen when its address is missing. */
    data class Tool(val featureId: String) : Target
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
 * @property state the state as a person reads it, such as "On" or "0.5×", when there is one.
 * @property color the slot's own "on" colour, from its [Look], or null for the layout's.
 */
data class Cell(
    val key: String,
    val label: String,
    val icon: CellIcon,
    val active: Boolean,
    val degraded: Boolean,
    val tap: Tap,
    val state: String? = null,
    val color: Long? = null,
) {
    /**
     * The colour the icon draws in, given the layout's [on] and [off]: the slot's own colour while
     * it's on, and always for a slot with no state (an app, a tool, an action), which would
     * otherwise never show it.
     */
    fun tint(on: Long, off: Long): Long =
        color?.takeIf { active || state == null } ?: if (active) on else off
}

/** Decides how each item draws and what a tap on it does, given the current access. */
object Cells {
    /** [cell] with the slot's own label, symbol and colour where its [look] sets one. */
    fun styled(cell: Cell, look: Look?): Cell {
        if (look == null) return cell
        return cell.copy(
            label = look.label?.takeIf { it.isNotBlank() } ?: cell.label,
            icon = look.icon?.let(CellIcon::Symbol) ?: cell.icon,
            color = look.color,
        )
    }

    /**
     * @param state the toggle's current state id when [item] is a toggle, or when it's an action
     *   that sets one; "on" or "off" for a command item.
     * @param folderName looks up a folder's name for its label.
     * @param command looks up a saved command.
     * @param automation looks up an automation.
     * @return null for an item that points at something gone, such as a removed toggle.
     */
    fun plan(
        item: Item,
        state: String?,
        access: Access,
        folderName: (String) -> String?,
        command: (String) -> CustomCommand? = { null },
        automation: (String) -> Automation? = { null },
    ): Cell? =
        when (item) {
            is Item.Automation ->
                automation(item.automationId)?.let { found ->
                    Cell(
                        item.key,
                        found.name,
                        CellIcon.Symbol("bolt"),
                        active = false,
                        degraded = false,
                        Tap.RunAutomation(found.id),
                    )
                }
            is Item.Action -> actionCell(item, state, access, command)
            is Item.Tool ->
                Features.byId(item.featureId)?.let { feature ->
                    Cell(
                        item.key,
                        feature.title,
                        CellIcon.Symbol(feature.icon),
                        active = false,
                        degraded = false,
                        Tap.Open(Target.Tool(feature.id)),
                    )
                }
            is Item.Command ->
                command(item.commandId)?.let { commandCell(item.key, it, state, access) }
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

    /**
     * An action draws as what it does. One that sets a toggle lights up while the toggle is in that
     * state, so a row of "Animation scale: 0.5×" and "Animation scale: Off" shows which is on.
     */
    private fun actionCell(
        item: Item.Action,
        state: String?,
        access: Access,
        command: (String) -> CustomCommand?,
    ): Cell? {
        val action = item.action
        if (action is Action.SetToggle && Toggles.byId(action.toggleId)?.isBuilt != true)
            return null
        if (action is Action.RunCommand && command(action.commandId) == null) return null
        val level = action.level()
        val inPlace = access.allows(level)
        return Cell(
            key = item.key,
            label = item.label,
            icon = CellIcon.Symbol(actionIcon(action)),
            active = action is Action.SetToggle && state == action.state,
            degraded = !inPlace,
            tap = if (inPlace) Tap.RunAction(action, item.label) else Tap.Open(Target.Setup(level)),
        )
    }

    /** The symbol an action draws with: its toggle's, or one for what it does to an app. */
    fun actionIcon(action: Action): String =
        when (action) {
            is Action.SetToggle -> Toggles.byId(action.toggleId)?.icon ?: "toggle_on"
            is Action.ForApp ->
                when (action.action) {
                    AppAction.ForceStop -> "block"
                    AppAction.ClearCache -> "cleaning_services"
                    AppAction.ClearData -> "delete_sweep"
                    AppAction.Disable -> "hide_source"
                    AppAction.Enable -> "check_circle"
                    AppAction.Uninstall -> "delete"
                }
            is Action.Permission -> if (action.grant) "verified_user" else "remove_moderator"
            is Action.RunCommand -> "terminal"
            is Action.OpenApp,
            is Action.OpenShortcut -> "open_in_new"
            is Action.OpenLink -> "link"
            is Action.OpenSettings -> "settings"
            is Action.Notify -> "notifications"
            is Action.Wait -> "timer"
            is Action.RunAutomation -> "bolt"
            is Action.SetLocation -> "location_on"
            Action.RealLocation -> "location_off"
            is Action.ReactotronCommand -> "data_object"
        }

    private fun commandCell(
        key: String,
        command: CustomCommand,
        state: String?,
        access: Access,
    ): Cell {
        val inPlace = access.allows(AccessLevel.Shell)
        return Cell(
            key = key,
            label = command.name,
            icon = CellIcon.Symbol("terminal"),
            state = state?.takeIf { command.isToggle }?.let { if (it == "on") "On" else "Off" },
            active = command.isToggle && state == "on",
            degraded = !inPlace,
            tap =
                if (inPlace) Tap.RunCommand(command.id)
                else Tap.Open(Target.Setup(AccessLevel.Shell)),
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
            state = toggle.states.firstOrNull { it.id == state }?.label,
            active = toggle.isActive(state),
            degraded = !inPlace,
            tap = tap,
        )
    }
}
