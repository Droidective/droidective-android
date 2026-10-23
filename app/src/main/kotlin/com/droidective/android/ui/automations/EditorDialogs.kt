package com.droidective.android.ui.automations

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import com.droidective.android.droidective
import com.droidective.android.editor.ItemPicker
import com.droidective.core.apps.AppAction
import com.droidective.core.apps.AppActions
import com.droidective.core.automations.Action
import com.droidective.core.automations.Automation
import com.droidective.core.automations.AutomationRules
import com.droidective.core.automations.Button
import com.droidective.core.automations.Condition
import com.droidective.core.automations.Event
import com.droidective.core.automations.State
import com.droidective.core.automations.Trigger
import com.droidective.core.automations.isDestructive
import com.droidective.core.commands.DeepLinkRepository
import com.droidective.core.layout.Item
import com.droidective.core.layout.LayoutKind
import com.droidective.core.toggles.Toggles

/** One step of adding something to the draft, as the dialog showing it. */
internal sealed interface EditorDialog {
    data object ScheduleTime : EditorDialog

    data object ConditionKinds : EditorDialog

    data class Percent(val place: Place, val below: Boolean) : EditorDialog

    data class AppOpenPick(val place: Place) : EditorDialog

    data class ToggleIsPick(val place: Place) : EditorDialog

    data class WifiPick(val place: Place) : EditorDialog

    data object TriggerKinds : EditorDialog

    data object CrashPick : EditorDialog

    data class BluetoothPick(val place: Place) : EditorDialog

    data class WifiName(val place: Place) : EditorDialog

    data class WindowStart(val place: Place) : EditorDialog

    data class WindowEnd(val place: Place, val start: Int) : EditorDialog

    data object ActionKinds : EditorDialog

    data object ItemPick : EditorDialog

    data class ToggleState(val toggleId: String) : EditorDialog

    data object AppActionApp : EditorDialog

    data class AppActionPick(val packageName: String) : EditorDialog

    data object PermissionApp : EditorDialog

    data class PermissionName(val packageName: String) : EditorDialog

    data class PermissionGrant(val packageName: String, val permission: String) : EditorDialog

    data object NotifyTitle : EditorDialog

    data class NotifyText(val title: String) : EditorDialog

    data object WaitSeconds : EditorDialog

    data object RunAutomationPick : EditorDialog

    data object LocationPick : EditorDialog

    data object ReactotronPick : EditorDialog

    data object SnapshotPick : EditorDialog

    data class ConfirmDestructive(val action: Action) : EditorDialog

    data class ButtonTarget(val actionIndex: Int) : EditorDialog

    data class ButtonApp(val actionIndex: Int) : EditorDialog

    data class ButtonLink(val actionIndex: Int) : EditorDialog

    data class ButtonAutomation(val actionIndex: Int) : EditorDialog

    data class ButtonLabel(val actionIndex: Int, val action: Action) : EditorDialog
}

@Composable
internal fun EditorDialogs(
    dialog: EditorDialog,
    draft: Automation,
    onChange: (Automation) -> Unit,
    onDialog: (EditorDialog?) -> Unit,
) {
    val close = { onDialog(null) }
    fun add(condition: Condition) {
        onChange(draft.withCondition(condition))
        close()
    }
    fun add(action: Action) {
        // Clear data, uninstall and revoke ask once, here, and never again when it runs.
        if (action.isDestructive) onDialog(EditorDialog.ConfirmDestructive(action))
        else {
            onChange(draft.withAction(action))
            close()
        }
    }
    when (dialog) {
        EditorDialog.ScheduleTime -> {
            val schedule = (draft.trigger as Trigger.On).event as Event.Schedule
            TimeDialog(
                "Time",
                schedule.minuteOfDay,
                onDismiss = close,
                onDone = { minute ->
                    onChange(draft.copy(trigger = Trigger.On(schedule.copy(minuteOfDay = minute))))
                    close()
                },
            )
        }
        is EditorDialog.ConfirmDestructive ->
            ConfirmDestructive(dialog.action, close) {
                onChange(draft.withAction(dialog.action))
                close()
            }
        is EditorDialog.ButtonTarget,
        is EditorDialog.ButtonApp,
        is EditorDialog.ButtonLink,
        is EditorDialog.ButtonAutomation,
        is EditorDialog.ButtonLabel -> ButtonDialogs(dialog, draft, onChange, onDialog)
        EditorDialog.TriggerKinds,
        EditorDialog.CrashPick,
        is EditorDialog.Percent,
        is EditorDialog.AppOpenPick,
        is EditorDialog.ToggleIsPick,
        is EditorDialog.WifiPick,
        is EditorDialog.BluetoothPick,
        is EditorDialog.WifiName,
        is EditorDialog.WindowStart,
        is EditorDialog.WindowEnd -> StateDialogs(dialog, draft, onChange, onDialog)
        EditorDialog.ConditionKinds -> ConditionDialogs(onDialog, ::add)
        EditorDialog.LocationPick -> LocationChooser(onDismiss = close) { action, _ -> add(action) }
        EditorDialog.ReactotronPick -> ReactotronCommandChooser(onDismiss = close, onPick = ::add)
        EditorDialog.SnapshotPick -> SnapshotChooser(onDismiss = close, onPick = ::add)
        else -> ActionDialogs(dialog, draft, onDialog, ::add)
    }
}

@Composable
private fun ConditionDialogs(onDialog: (EditorDialog?) -> Unit, add: (Condition) -> Unit) {
    ChoiceDialog("Only if", conditionKinds, onDismiss = { onDialog(null) }) { pick ->
        pick.condition?.let(add) ?: onDialog(pick.next)
    }
}

/** A condition kind: one that needs nothing more, or the dialog that asks for the rest. */
private data class ConditionPick(val condition: Condition?, val next: EditorDialog? = null)

private val conditionKinds: List<Pair<String, ConditionPick>> =
    listOf(
        "Charging" to ConditionPick(Condition(State.Charging)),
        "Not charging" to ConditionPick(Condition(State.Charging, negate = true)),
        "Battery below…" to
            ConditionPick(null, EditorDialog.Percent(Place.Condition, below = true)),
        "Battery above…" to
            ConditionPick(null, EditorDialog.Percent(Place.Condition, below = false)),
        "The screen is on" to ConditionPick(Condition(State.ScreenOn)),
        "The screen is off" to ConditionPick(Condition(State.ScreenOn, negate = true)),
        "Headphones plugged in" to ConditionPick(Condition(State.Headphones)),
        "On Wi-Fi" to ConditionPick(Condition(State.OnWifi())),
        "Not on Wi-Fi" to ConditionPick(Condition(State.OnWifi(), negate = true)),
        "On a Wi-Fi network… (Shell)" to
            ConditionPick(null, EditorDialog.WifiName(Place.Condition)),
        "A Bluetooth device is connected…" to
            ConditionPick(null, EditorDialog.BluetoothPick(Place.Condition)),
        "An app is open…" to ConditionPick(null, EditorDialog.AppOpenPick(Place.Condition)),
        "A toggle is…" to ConditionPick(null, EditorDialog.ToggleIsPick(Place.Condition)),
        "Between two times…" to ConditionPick(null, EditorDialog.WindowStart(Place.Condition)),
        "Shell is available" to ConditionPick(Condition(State.ShellAvailable)),
        "Shell isn’t available" to ConditionPick(Condition(State.ShellAvailable, negate = true)),
    )

@Composable
private fun ActionDialogs(
    dialog: EditorDialog,
    draft: Automation,
    onDialog: (EditorDialog?) -> Unit,
    add: (Action) -> Unit,
) {
    val context = LocalContext.current
    val close = { onDialog(null) }
    when (dialog) {
        EditorDialog.ActionKinds ->
            ChoiceDialog("Add an action", actionKinds, onPick = { onDialog(it) }, onDismiss = close)
        EditorDialog.ItemPick ->
            ItemPicker(
                LayoutKind.Folder,
                slotsOnly = true,
                onDismiss = close,
                onPick = { item ->
                    actionFor(item)?.let(add)
                        ?: (item as? Item.Toggle)?.let {
                            onDialog(EditorDialog.ToggleState(it.toggleId))
                        }
                        ?: close()
                },
            )
        is EditorDialog.ToggleState -> {
            val toggle = Toggles.byId(dialog.toggleId) ?: return close()
            ChoiceDialog(toggle.title, toggle.states.map { it.label to it.id }, onDismiss = close) {
                add(Action.SetToggle(toggle.id, it))
            }
        }
        EditorDialog.AppActionApp ->
            AppChooser("Which app", onDismiss = close) {
                onDialog(EditorDialog.AppActionPick(it.packageName))
            }
        is EditorDialog.AppActionPick ->
            ChoiceDialog(
                dialog.packageName,
                AppAction.entries.map { it.title to it },
                onDismiss = close,
            ) {
                add(Action.ForApp(dialog.packageName, it))
            }
        EditorDialog.PermissionApp ->
            AppChooser("Which app", onDismiss = close) {
                onDialog(EditorDialog.PermissionName(it.packageName))
            }
        is EditorDialog.PermissionName ->
            TextDialog(
                "Permission",
                "Name",
                initial = "android.permission.",
                valid = AppActions::isPermissionName,
                onDismiss = close,
                onDone = { onDialog(EditorDialog.PermissionGrant(dialog.packageName, it)) },
            )
        is EditorDialog.PermissionGrant ->
            ChoiceDialog(
                dialog.permission,
                listOf("Grant" to true, "Revoke" to false),
                onDismiss = close,
            ) {
                add(Action.Permission(dialog.packageName, dialog.permission, it))
            }
        EditorDialog.NotifyTitle ->
            TextDialog("Notification", "Title", onDismiss = close) {
                onDialog(EditorDialog.NotifyText(it))
            }
        is EditorDialog.NotifyText ->
            TextDialog("Notification", "Text (optional)", valid = { true }, onDismiss = close) {
                add(Action.Notify(dialog.title, it))
            }
        EditorDialog.WaitSeconds ->
            NumberDialog(
                "Wait",
                "Seconds",
                1..AutomationRules.MAX_WAIT_SECONDS,
                onDismiss = close,
            ) {
                add(Action.Wait(it))
            }
        EditorDialog.RunAutomationPick -> {
            val others = context.droidective.automations.all().filter { it.id != draft.id }
            ChoiceDialog("Run", others.map { it.name to it.id }, onDismiss = close) {
                add(Action.RunAutomation(it))
            }
        }
        else -> Unit
    }
}

private val actionKinds: List<Pair<String, EditorDialog>> =
    listOf(
        "A toggle, an app, a link or a command" to EditorDialog.ItemPick,
        "Force stop, clear data, disable…" to EditorDialog.AppActionApp,
        "Grant or revoke a permission" to EditorDialog.PermissionApp,
        "Post a notification" to EditorDialog.NotifyTitle,
        "Wait" to EditorDialog.WaitSeconds,
        "Run another automation" to EditorDialog.RunAutomationPick,
        "Put the phone at a place, or back" to EditorDialog.LocationPick,
        "Run an app’s Reactotron command" to EditorDialog.ReactotronPick,
        "Put a Reactotron snapshot back" to EditorDialog.SnapshotPick,
    )

/** What a widget item does, as an action; a toggle needs its state first, so it's null here. */
private fun actionFor(item: Item): Action? =
    when (item) {
        is Item.App -> Action.OpenApp(item.packageName, item.label)
        is Item.Shortcut -> Action.OpenShortcut(item.label, item.intentUri)
        is Item.DeepLink -> Action.OpenLink(item.uri)
        is Item.SettingsScreen -> Action.OpenSettings(item.label, item.action)
        is Item.Command -> Action.RunCommand(item.commandId)
        is Item.Automation -> Action.RunAutomation(item.automationId)
        is Item.Action -> item.action
        is Item.Toggle,
        is Item.Folder,
        is Item.Tool -> null
    }

@Composable
private fun ButtonDialogs(
    dialog: EditorDialog,
    draft: Automation,
    onChange: (Automation) -> Unit,
    onDialog: (EditorDialog?) -> Unit,
) {
    val context = LocalContext.current
    val close = { onDialog(null) }
    when (dialog) {
        is EditorDialog.ButtonTarget ->
            ChoiceDialog(
                "The button",
                listOf(
                    "Opens an app" to EditorDialog.ButtonApp(dialog.actionIndex),
                    "Opens a link" to EditorDialog.ButtonLink(dialog.actionIndex),
                    "Runs an automation" to EditorDialog.ButtonAutomation(dialog.actionIndex),
                ),
                onPick = { onDialog(it) },
                onDismiss = close,
            )
        is EditorDialog.ButtonApp ->
            AppChooser("Opens", onDismiss = close) {
                onDialog(
                    EditorDialog.ButtonLabel(
                        dialog.actionIndex,
                        Action.OpenApp(it.packageName, it.label),
                    )
                )
            }
        is EditorDialog.ButtonLink ->
            TextDialog(
                "Opens",
                "Link",
                valid = DeepLinkRepository::isLink,
                keyboard = KeyboardType.Uri,
                onDismiss = close,
            ) {
                onDialog(EditorDialog.ButtonLabel(dialog.actionIndex, Action.OpenLink(it)))
            }
        is EditorDialog.ButtonAutomation -> {
            val others = context.droidective.automations.all().filter { it.id != draft.id }
            ChoiceDialog("Runs", others.map { it.name to it.id }, onDismiss = close) {
                onDialog(EditorDialog.ButtonLabel(dialog.actionIndex, Action.RunAutomation(it)))
            }
        }
        is EditorDialog.ButtonLabel ->
            TextDialog("Button", "Label", onDismiss = close) { label ->
                val notify = draft.actions[dialog.actionIndex] as Action.Notify
                val buttons = notify.buttons + Button(label, dialog.action)
                onChange(draft.replaceAction(dialog.actionIndex, notify.copy(buttons = buttons)))
                close()
            }
        else -> Unit
    }
}

@Composable
private fun ConfirmDestructive(action: Action, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("This can’t be undone") },
        text = {
            Text(
                when {
                    action is Action.ForApp && action.action == AppAction.ClearData ->
                        "Every time it runs, ${action.packageName} loses its accounts, settings " +
                            "and files. It won't ask again."
                    action is Action.ForApp && action.action == AppAction.Uninstall ->
                        "Every time it runs, it uninstalls ${action.packageName}. It won't ask again."
                    action is Action.ForApp ->
                        "Every time it runs, it'll ${action.action.title.lowercase()} " +
                            "${action.packageName}. It won't ask again."
                    action is Action.Permission ->
                        "Every time it runs, it revokes ${action.permission} from " +
                            "${action.packageName}. It won't ask again."
                    else -> "It won't ask again when it runs."
                }
            )
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Add it") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
