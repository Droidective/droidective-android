package com.droidective.android.ui.automations

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.droidective.core.automations.Day
import com.droidective.core.toggles.ToggleDef
import com.droidective.platform.AppCatalog
import com.droidective.platform.AppEntry

/** A list to pick one from, in a dialog. */
@Composable
fun <T> ChoiceDialog(
    title: String,
    options: List<Pair<String, T>>,
    onDismiss: () -> Unit,
    onPick: (T) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                items(options) { (label, value) ->
                    Text(
                        label,
                        modifier =
                            Modifier.fillMaxWidth()
                                .clickable { onPick(value) }
                                .padding(vertical = 12.dp),
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** The installed apps people use, searchable; system apps are behind the search. */
@Composable
fun AppChooser(title: String, onDismiss: () -> Unit, onPick: (AppEntry) -> Unit) {
    val context = LocalContext.current
    var apps by remember { mutableStateOf<List<AppEntry>>(emptyList()) }
    var query by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(Unit) { apps = AppCatalog.list(context) }
    val visible = apps.filter { app ->
        if (query.isBlank()) !app.system
        else
            app.label.contains(query, ignoreCase = true) ||
                app.packageName.contains(query, ignoreCase = true)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("Search, including system apps") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                LazyColumn(Modifier.heightIn(max = 380.dp)) {
                    items(visible, key = { it.packageName }) { app ->
                        Column(
                            Modifier.fillMaxWidth()
                                .clickable { onPick(app) }
                                .padding(vertical = 8.dp)
                        ) {
                            Text(app.label)
                            Text(
                                app.packageName,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** A toggle, then one of its states. A toggle with a single state skips the second step. */
@Composable
fun ToggleStateChooser(
    title: String,
    toggles: List<ToggleDef>,
    onDismiss: () -> Unit,
    onPick: (toggleId: String, state: String) -> Unit,
) {
    var chosen by remember { mutableStateOf<ToggleDef?>(null) }
    val toggle = chosen
    if (toggle == null) {
        ChoiceDialog(
            title,
            toggles.sortedBy { it.title }.map { it.title to it },
            onPick = { picked ->
                if (picked.states.size == 1) onPick(picked.id, picked.states.single().id)
                else chosen = picked
            },
            onDismiss = onDismiss,
        )
    } else {
        ChoiceDialog(
            toggle.title,
            toggle.states.map { it.label to it.id },
            onPick = { state -> onPick(toggle.id, state) },
            onDismiss = onDismiss,
        )
    }
}

@Composable
fun TextDialog(
    title: String,
    label: String,
    initial: String = "",
    valid: (String) -> Boolean = { it.isNotBlank() },
    keyboard: KeyboardType = KeyboardType.Text,
    onDismiss: () -> Unit,
    onDone: (String) -> Unit,
) {
    var text by rememberSaveable { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text(label) },
                isError = text.isNotEmpty() && !valid(text),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = keyboard),
            )
        },
        confirmButton = {
            TextButton(enabled = valid(text), onClick = { onDone(text.trim()) }) { Text("Done") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun NumberDialog(
    title: String,
    label: String,
    range: IntRange,
    initial: Int? = null,
    onDismiss: () -> Unit,
    onDone: (Int) -> Unit,
) {
    TextDialog(
        title,
        label,
        initial = initial?.toString().orEmpty(),
        valid = { it.trim().toIntOrNull() in range },
        keyboard = KeyboardType.Number,
        onDone = { onDone(it.toInt()) },
        onDismiss = onDismiss,
    )
}

/** A 24-hour time, as minutes after midnight. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimeDialog(
    title: String,
    minuteOfDay: Int,
    onDismiss: () -> Unit,
    onDone: (Int) -> Unit,
) {
    val state =
        rememberTimePickerState(
            initialHour = minuteOfDay / 60,
            initialMinute = minuteOfDay % 60,
            is24Hour = true,
        )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { TimePicker(state = state) },
        confirmButton = {
            TextButton(onClick = { onDone(state.hour * 60 + state.minute) }) { Text("Done") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Seven chips, Monday first. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DayChips(days: Set<Day>, onChange: (Set<Day>) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Day.entries.forEach { day ->
            FilterChip(
                selected = day in days,
                onClick = { onChange(if (day in days) days - day else days + day) },
                label = { Text(day.short) },
            )
        }
    }
}
