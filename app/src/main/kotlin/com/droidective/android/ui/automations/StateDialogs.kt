package com.droidective.android.ui.automations

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.droidective.core.automations.Automation
import com.droidective.core.automations.Condition
import com.droidective.core.automations.Event
import com.droidective.core.automations.State
import com.droidective.core.automations.Trigger
import com.droidective.core.toggles.Probe
import com.droidective.core.toggles.Toggles
import com.droidective.platform.BluetoothAudio

/** Where a chosen state goes: an Only if, or the trigger as a start, an end or a While. */
internal enum class Place {
    Condition,
    Starts,
    Ends,
    While,
}

internal fun Automation.place(state: State, place: Place): Automation =
    when (place) {
        Place.Condition -> withCondition(Condition(state))
        Place.Starts -> copy(trigger = Trigger.Starts(state))
        Place.Ends -> copy(trigger = Trigger.Ends(state))
        Place.While -> copy(trigger = Trigger.While(state))
    }

/** A trigger that needs nothing more, or the dialog that asks for the rest. */
private data class TriggerPick(val trigger: Trigger?, val next: EditorDialog? = null)

private val triggerKinds: List<Pair<String, TriggerPick>> =
    listOf(
        "When I run it" to TriggerPick(Trigger.On(Event.Manual)),
        "On a schedule" to TriggerPick(Trigger.On(Event.Schedule(23 * 60 + 30))),
        "When the phone restarts" to TriggerPick(Trigger.On(Event.Restarted)),
        "When a Bluetooth device connects…" to
            TriggerPick(null, EditorDialog.BluetoothPick(Place.Starts)),
        "When a Bluetooth device disconnects…" to
            TriggerPick(null, EditorDialog.BluetoothPick(Place.Ends)),
        "While a Bluetooth device is connected…" to
            TriggerPick(null, EditorDialog.BluetoothPick(Place.While)),
        "While it’s between two times…" to TriggerPick(null, EditorDialog.WindowStart(Place.While)),
        "When an app opens…" to TriggerPick(null, EditorDialog.AppOpenPick(Place.Starts)),
        "When an app closes…" to TriggerPick(null, EditorDialog.AppOpenPick(Place.Ends)),
        "While an app is open…" to TriggerPick(null, EditorDialog.AppOpenPick(Place.While)),
        "When charging starts" to TriggerPick(Trigger.Starts(State.Charging)),
        "When charging stops" to TriggerPick(Trigger.Ends(State.Charging)),
        "While charging" to TriggerPick(Trigger.While(State.Charging)),
        "When the screen turns on" to TriggerPick(Trigger.Starts(State.ScreenOn)),
        "When the screen turns off" to TriggerPick(Trigger.Ends(State.ScreenOn)),
        "When the phone is unlocked" to TriggerPick(Trigger.On(Event.Unlocked)),
        "When the battery drops below…" to
            TriggerPick(null, EditorDialog.Percent(Place.Starts, below = true)),
        "While the battery is below…" to
            TriggerPick(null, EditorDialog.Percent(Place.While, below = true)),
        "When headphones are plugged in" to TriggerPick(Trigger.Starts(State.Headphones)),
        "When headphones are unplugged" to TriggerPick(Trigger.Ends(State.Headphones)),
        "While headphones are plugged in" to TriggerPick(Trigger.While(State.Headphones)),
        "When a toggle changes to…" to TriggerPick(null, EditorDialog.ToggleIsPick(Place.Starts)),
        "While a toggle is…" to TriggerPick(null, EditorDialog.ToggleIsPick(Place.While)),
        "When the phone joins Wi-Fi…" to TriggerPick(null, EditorDialog.WifiPick(Place.Starts)),
        "When the phone leaves Wi-Fi…" to TriggerPick(null, EditorDialog.WifiPick(Place.Ends)),
        "While on Wi-Fi…" to TriggerPick(null, EditorDialog.WifiPick(Place.While)),
        "When an app is installed or updated" to TriggerPick(Trigger.On(Event.AppInstalled())),
        "When an app crashes…" to TriggerPick(null, EditorDialog.CrashPick),
        "When any app crashes" to TriggerPick(Trigger.On(Event.AppCrashed())),
    )

/** The trigger list, and the Bluetooth, Wi-Fi and time pickers a trigger or condition uses. */
@Composable
internal fun StateDialogs(
    dialog: EditorDialog,
    draft: Automation,
    onChange: (Automation) -> Unit,
    onDialog: (EditorDialog?) -> Unit,
) {
    val close = { onDialog(null) }
    fun place(state: State, place: Place) {
        onChange(draft.place(state, place))
        close()
    }
    when (dialog) {
        EditorDialog.TriggerKinds ->
            ChoiceDialog("When it runs", triggerKinds, onDismiss = close) { pick ->
                if (pick.trigger != null) {
                    onChange(draft.copy(trigger = pick.trigger))
                    close()
                } else {
                    onDialog(pick.next)
                }
            }
        is EditorDialog.BluetoothPick ->
            BluetoothPicker(onDismiss = close) { place(State.BluetoothConnected(it), dialog.place) }
        is EditorDialog.Percent ->
            NumberDialog(
                if (dialog.below) "Battery below" else "Battery above",
                "Percent",
                1..99,
                onDismiss = close,
            ) { n ->
                place(
                    if (dialog.below) State.BatteryBelow(n) else State.BatteryAbove(n),
                    dialog.place,
                )
            }
        EditorDialog.CrashPick ->
            AppChooser("Which app", onDismiss = close) {
                onChange(draft.copy(trigger = Trigger.On(Event.AppCrashed(it.packageName))))
                close()
            }
        is EditorDialog.AppOpenPick ->
            AppChooser("Which app", onDismiss = close) {
                place(State.AppOpen(it.packageName), dialog.place)
            }
        is EditorDialog.ToggleIsPick ->
            ToggleStateChooser(
                "A toggle is",
                Toggles.all.filter { it.isBuilt && !it.isAction && it.read != Probe.None },
                onDismiss = close,
            ) { id, state ->
                place(State.ToggleIs(id, state), dialog.place)
            }
        is EditorDialog.WifiPick ->
            ChoiceDialog(
                "Which Wi-Fi",
                listOf("Any Wi-Fi network" to false, "A network by name… (Shell)" to true),
                onDismiss = close,
            ) { byName ->
                if (byName) onDialog(EditorDialog.WifiName(dialog.place))
                else place(State.OnWifi(), dialog.place)
            }
        is EditorDialog.WifiName ->
            TextDialog("Wi-Fi network", "Network name", onDismiss = close) {
                place(State.OnWifi(it), dialog.place)
            }
        is EditorDialog.WindowStart ->
            TimeDialog("From", 22 * 60, onDismiss = close) {
                onDialog(EditorDialog.WindowEnd(dialog.place, it))
            }
        is EditorDialog.WindowEnd ->
            TimeDialog("Until", 7 * 60, onDismiss = close) { end ->
                if (end != dialog.start) place(State.TimeBetween(dialog.start, end), dialog.place)
                else close()
            }
        else -> Unit
    }
}

/**
 * A paired device by name, or any. Reading them needs Nearby devices from Android 12, so it asks
 * first and says why.
 */
@Composable
private fun BluetoothPicker(onDismiss: () -> Unit, onPick: (String?) -> Unit) {
    val context = LocalContext.current
    var allowed by remember { mutableStateOf(BluetoothAudio.allowed(context)) }
    val ask =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
            allowed = it
        }
    if (!allowed && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Nearby devices") },
            text = {
                Text(
                    "Droidective needs Nearby devices to see which Bluetooth devices are " +
                        "connected. It reads their names and nothing else. If nothing happens " +
                        "on Allow, turn it on in App info."
                )
            },
            confirmButton = {
                TextButton(onClick = { ask.launch(Manifest.permission.BLUETOOTH_CONNECT) }) {
                    Text("Allow")
                }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        )
        return
    }
    val names = remember { BluetoothAudio.pairedNames(context).orEmpty() }
    ChoiceDialog(
        "Which device",
        listOf<Pair<String, String?>>("Any Bluetooth audio device" to null) +
            names.map { it to it },
        onDismiss = onDismiss,
        onPick = onPick,
    )
}
