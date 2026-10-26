package com.droidective.android.editor

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.droidective.android.droidective
import com.droidective.android.ui.LevelChip
import com.droidective.android.ui.Symbols
import com.droidective.android.ui.automations.AppChooser
import com.droidective.android.ui.automations.ChoiceDialog
import com.droidective.android.ui.automations.DispatchChooser
import com.droidective.android.ui.automations.LocationChooser
import com.droidective.android.ui.automations.ReactotronCommandChooser
import com.droidective.android.ui.automations.SnapshotChooser
import com.droidective.android.ui.automations.TextDialog
import com.droidective.android.ui.automations.ToggleStateChooser
import com.droidective.core.apps.AppAction
import com.droidective.core.apps.AppActions
import com.droidective.core.automations.Action
import com.droidective.core.automations.Review
import com.droidective.core.automations.isDestructive
import com.droidective.core.features.Features
import com.droidective.core.layout.Item
import com.droidective.core.network.NetworkFact
import com.droidective.core.reactotron.ReactotronCommands
import com.droidective.core.toggles.Toggles
import com.droidective.platform.hasHardwareFor
import java.util.UUID

/** One step of making a single-action slot, as the dialog showing it. */
private sealed interface ActionStep {
    data object ToggleState : ActionStep

    data object AppForAction : ActionStep

    data class AppAction(val packageName: String, val label: String) : ActionStep

    data object AppForPermission : ActionStep

    data class PermissionName(val packageName: String, val label: String) : ActionStep

    data class PermissionGrant(val packageName: String, val label: String, val permission: String) :
        ActionStep

    data object Place : ActionStep

    data object ReactotronCommand : ActionStep

    data object Snapshot : ActionStep

    data object Dispatch : ActionStep

    data class Confirm(val item: Item.Action) : ActionStep
}

private fun key() = UUID.randomUUID().toString()

/**
 * The Actions tab: the person's automations, their saved commands, and single actions a slot can
 * run in one tap, such as a toggle set to one state or an app force-stopped.
 */
@Composable
internal fun ActionList(query: String, onPick: (Item) -> Unit) {
    val context = LocalContext.current
    val app = context.droidective
    var step by remember { mutableStateOf<ActionStep?>(null) }
    val automations = app.automations.all().filter { it.name.contains(query, ignoreCase = true) }
    val commands = app.commands.all().filter { it.name.contains(query, ignoreCase = true) }
    LazyColumn {
        item { Header("One tap does") }
        item { PickRow("Set a toggle to one state", "toggle_on") { step = ActionStep.ToggleState } }
        item {
            PickRow("Force stop, clear data or uninstall an app", "block") {
                step = ActionStep.AppForAction
            }
        }
        item {
            PickRow("Grant or revoke a permission", "verified_user") {
                step = ActionStep.AppForPermission
            }
        }
        item {
            PickRow("Put the phone at a place, or back", "location_on") { step = ActionStep.Place }
        }
        item {
            PickRow("Run an app’s Reactotron command", "data_object") {
                step = ActionStep.ReactotronCommand
            }
        }
        item { PickRow("Put a Reactotron snapshot back", "history") { step = ActionStep.Snapshot } }
        item {
            PickRow("Dispatch an action in an app", "play_circle") { step = ActionStep.Dispatch }
        }
        item { Header("Your automations") }
        if (automations.isEmpty()) {
            item { Note("Automations you make show here, to run with one tap.") }
        }
        items(automations, key = { "automation-${it.id}" }) { automation ->
            PickRow(automation.name, "bolt") { onPick(Item.Automation(key(), automation.id)) }
        }
        item { Header("Saved commands") }
        if (commands.isEmpty()) {
            item { Note("Commands saved in Custom commands show here, to run as root or Shell.") }
        }
        items(commands, key = { "command-${it.id}" }) { command ->
            PickRow(command.name, "terminal") { onPick(Item.Command(key(), command.id)) }
        }
    }
    ActionSteps(step, onStep = { step = it }, onPick = onPick)
}

@Composable
private fun ActionSteps(step: ActionStep?, onStep: (ActionStep?) -> Unit, onPick: (Item) -> Unit) {
    val context = LocalContext.current
    val close = { onStep(null) }
    fun finish(action: Action, label: String) {
        val item = Item.Action(key(), action, label)
        if (action.isDestructive) onStep(ActionStep.Confirm(item))
        else {
            close()
            onPick(item)
        }
    }
    when (step) {
        null -> Unit
        ActionStep.ToggleState ->
            ToggleStateChooser(
                "Which toggle",
                Toggles.all.filter {
                    it.isBuilt && !it.isAction && it.states.size > 1 && context.hasHardwareFor(it)
                },
                onDismiss = close,
            ) { toggleId, state ->
                val action = Action.SetToggle(toggleId, state)
                finish(action, Review.describe(action, context.droidective.commands::get))
            }
        ActionStep.AppForAction ->
            AppChooser("Which app", onDismiss = close) {
                onStep(ActionStep.AppAction(it.packageName, it.label))
            }
        is ActionStep.AppAction ->
            ChoiceDialog(step.label, AppAction.entries.map { it.title to it }, onDismiss = close) {
                finish(Action.ForApp(step.packageName, it), "${it.title} ${step.label}")
            }
        ActionStep.AppForPermission ->
            AppChooser("Which app", onDismiss = close) {
                onStep(ActionStep.PermissionName(it.packageName, it.label))
            }
        is ActionStep.PermissionName ->
            TextDialog(
                "Permission",
                "Name",
                initial = "android.permission.",
                valid = AppActions::isPermissionName,
                onDismiss = close,
                onDone = { onStep(ActionStep.PermissionGrant(step.packageName, step.label, it)) },
            )
        is ActionStep.PermissionGrant ->
            ChoiceDialog(
                step.permission,
                listOf("Grant" to true, "Revoke" to false),
                onDismiss = close,
            ) { grant ->
                val short = step.permission.substringAfterLast('.')
                finish(
                    Action.Permission(step.packageName, step.permission, grant),
                    "${if (grant) "Grant" else "Revoke"} $short: ${step.label}",
                )
            }
        ActionStep.Place -> LocationChooser(onDismiss = close, onPick = ::finish)
        ActionStep.ReactotronCommand ->
            ReactotronCommandChooser(onDismiss = close) { finish(it, it.label) }
        ActionStep.Snapshot -> SnapshotChooser(onDismiss = close) { finish(it, it.name) }
        ActionStep.Dispatch ->
            DispatchChooser(onDismiss = close) {
                finish(it, ReactotronCommands.type(it.action) ?: it.action)
            }
        is ActionStep.Confirm ->
            ConfirmDialog(step.item, onDismiss = close) {
                close()
                onPick(step.item)
            }
    }
}

@Composable
private fun ConfirmDialog(item: Item.Action, onDismiss: () -> Unit, onAdd: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(item.label) },
        text = {
            Text(
                "A tap on this slot will do it at once, with no question asked. It can't be undone."
            )
        },
        confirmButton = { TextButton(onClick = onAdd) { Text("Add it") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** The Tools tab: any of Droidective's tool screens, one tap away. */
@Composable
internal fun ToolList(query: String, onPick: (Item) -> Unit) {
    val tools = Features.all.filter { it.matches(query) }
    val facts =
        NetworkFact.entries.filter {
            query.isBlank() ||
                it.title.contains(query, ignoreCase = true) ||
                NETWORK_WORDS.any { word -> word.contains(query.trim(), ignoreCase = true) }
        }
    LazyColumn {
        if (facts.isNotEmpty()) item(key = "network") { Header("Network") }
        items(facts, key = { "network-${it.name}" }) { fact ->
            ListItem(
                headlineContent = { Text(fact.title) },
                supportingContent = { Text("Shows it on the slot; a tap copies it") },
                leadingContent = {
                    Icon(painterResource(Symbols.byName.getValue(fact.symbol).outlined), null)
                },
                modifier = Modifier.clickable { onPick(Item.Network(key(), fact)) },
            )
        }
        tools
            .groupBy { it.category }
            .forEach { (category, inCategory) ->
                item(key = category.name) { Header(category.title) }
                items(inCategory, key = { it.id }) { feature ->
                    ListItem(
                        headlineContent = { Text(feature.title) },
                        supportingContent = {
                            Text(
                                feature.slotOpens?.let { "Opens the phone’s ${it.title}" }
                                    ?: feature.subtitle
                            )
                        },
                        leadingContent = {
                            Icon(
                                painterResource(Symbols.byName.getValue(feature.icon).outlined),
                                null,
                            )
                        },
                        // The phone's own screen needs no level.
                        trailingContent =
                            if (feature.slotOpens == null) {
                                { LevelChip(feature.level, missing = false) }
                            } else {
                                null
                            },
                        modifier = Modifier.clickable { onPick(Item.Tool(key(), feature.id)) },
                    )
                }
            }
    }
}

@Composable
private fun Note(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

/** What finds the network slots in a search, beside their own names. */
private val NETWORK_WORDS = listOf("network", "ip", "wifi", "wi-fi", "ssid", "address")
