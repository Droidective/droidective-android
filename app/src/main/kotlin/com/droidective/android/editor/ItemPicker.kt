package com.droidective.android.editor

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.ResolveInfo
import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.graphics.drawable.toBitmap
import com.droidective.android.droidective
import com.droidective.android.ui.LevelChip
import com.droidective.android.ui.Symbols
import com.droidective.core.layout.Item
import com.droidective.core.layout.Layout
import com.droidective.core.layout.LayoutKind
import com.droidective.core.toggles.Toggles
import com.droidective.platform.hasHardwareFor
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private enum class PickerTab(val title: String) {
    Toggles("Toggles"),
    Apps("Apps"),
    Custom("Custom"),
}

/** Settings screens worth a slot. The action strings are Android's own intent actions. */
private val settingsScreens =
    listOf(
        "Wi-Fi" to "android.settings.WIFI_SETTINGS",
        "Bluetooth" to "android.settings.BLUETOOTH_SETTINGS",
        "Display" to "android.settings.DISPLAY_SETTINGS",
        "Sound" to "android.settings.SOUND_SETTINGS",
        "Battery" to "android.intent.action.POWER_USAGE_SUMMARY",
        "Developer options" to "android.settings.APPLICATION_DEVELOPMENT_SETTINGS",
        "Apps" to "android.settings.APPLICATION_SETTINGS",
        "Location" to "android.settings.LOCATION_SOURCE_SETTINGS",
        "Accessibility" to "android.settings.ACCESSIBILITY_SETTINGS",
        "Date & time" to "android.settings.DATE_SETTINGS",
        "Language" to "android.settings.LOCALE_SETTINGS",
        "VPN" to "android.settings.VPN_SETTINGS",
    )

private fun key() = UUID.randomUUID().toString()

/** Add Toggle: Toggles, Apps and Custom, with search, the way MicroToggles lays it out. */
@Composable
fun ItemPicker(kind: LayoutKind, onPick: (Item) -> Unit, onDismiss: () -> Unit) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var query by rememberSaveable { mutableStateOf("") }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        androidx.compose.material3.Surface(Modifier.fillMaxSize()) {
            Column {
                Text(
                    "Add",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(16.dp),
                )
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("Search") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                )
                PrimaryTabRow(selectedTabIndex = tab) {
                    PickerTab.entries.forEachIndexed { index, it ->
                        Tab(
                            selected = tab == index,
                            onClick = { tab = index },
                            text = { Text(it.title) },
                        )
                    }
                }
                when (PickerTab.entries[tab]) {
                    PickerTab.Toggles -> ToggleList(query, onPick)
                    PickerTab.Apps -> AppList(query, onPick)
                    PickerTab.Custom -> CustomList(kind, query, onPick)
                }
            }
        }
    }
}

@Composable
private fun ToggleList(query: String, onPick: (Item) -> Unit) {
    val context = LocalContext.current
    val toggles =
        Toggles.all.filter {
            it.isBuilt && context.hasHardwareFor(it) && it.title.contains(query, ignoreCase = true)
        }
    LazyColumn {
        toggles
            .groupBy { it.group }
            .forEach { (group, inGroup) ->
                item(key = group.name) {
                    Text(
                        group.title.uppercase(),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
                    )
                }
                items(inGroup, key = { it.id }) { toggle ->
                    ListItem(
                        headlineContent = { Text(toggle.title) },
                        leadingContent = {
                            Icon(
                                painterResource(Symbols.byName.getValue(toggle.icon).outlined),
                                null,
                            )
                        },
                        trailingContent = { LevelChip(toggle.level, missing = false) },
                        modifier = Modifier.clickable { onPick(Item.Toggle(key(), toggle.id)) },
                    )
                }
            }
    }
}

private data class Launchable(
    val label: String,
    val packageName: String,
    val activity: String,
    val icon: Bitmap,
)

private suspend fun launchables(context: Context, intent: Intent): List<Launchable> =
    withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val px = (32 * context.resources.displayMetrics.density).toInt()
        pm.queryIntentActivities(intent, 0)
            .map { info: ResolveInfo ->
                Launchable(
                    label = info.loadLabel(pm).toString(),
                    packageName = info.activityInfo.packageName,
                    activity = info.activityInfo.name,
                    icon = info.loadIcon(pm).toBitmap(px, px),
                )
            }
            .sortedBy { it.label.lowercase() }
    }

@Composable
private fun AppList(query: String, onPick: (Item) -> Unit) {
    val context = LocalContext.current
    val apps by
        produceState(emptyList<Launchable>()) {
            value =
                launchables(
                    context,
                    Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),
                )
        }
    LazyColumn {
        items(apps.filter { it.label.contains(query, ignoreCase = true) }, key = { it.activity }) {
            app ->
            ListItem(
                headlineContent = { Text(app.label) },
                leadingContent = {
                    Image(
                        app.icon.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.size(32.dp),
                    )
                },
                modifier =
                    Modifier.clickable {
                        onPick(Item.App(key(), app.packageName, app.activity, app.label))
                    },
            )
        }
    }
}

@Composable
private fun CustomList(kind: LayoutKind, query: String, onPick: (Item) -> Unit) {
    val context = LocalContext.current
    val layouts = context.droidective.layouts
    var askDeepLink by remember { mutableStateOf(false) }
    val providers by
        produceState(emptyList<Launchable>()) {
            value = launchables(context, Intent(Intent.ACTION_CREATE_SHORTCUT))
        }
    val shortcut =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result
            ->
            if (result.resultCode == Activity.RESULT_OK) {
                result.data?.let { ShortcutResult.toItem(context, it) }?.let(onPick)
            }
        }
    val folders = layouts.all().filter { it.kind == LayoutKind.Folder }
    LazyColumn {
        if (kind != LayoutKind.Folder && kind != LayoutKind.Notification) {
            item { Header("Folders") }
            items(folders, key = { "folder-${it.id}" }) { folder ->
                PickRow(folder.name, "folder") { onPick(Item.Folder(key(), folder.id)) }
            }
            item {
                PickRow("New folder", "add") {
                    val folder =
                        Layout(
                            id = "folder-${UUID.randomUUID().toString().take(8)}",
                            kind = LayoutKind.Folder,
                            name = "Folder ${folders.size + 1}",
                            editedAt = System.currentTimeMillis(),
                        )
                    layouts.save(folder)
                    onPick(Item.Folder(key(), folder.id))
                }
            }
        }
        item { Header("Droidective") }
        item { PickRow("Deep link", "link") { askDeepLink = true } }
        item { Header("Settings screens") }
        items(
            settingsScreens.filter { it.first.contains(query, ignoreCase = true) },
            key = { it.second },
        ) { (label, action) ->
            PickRow(label, "settings") { onPick(Item.SettingsScreen(key(), label, action)) }
        }
        item { Header("From other apps") }
        items(
            providers.filter { it.label.contains(query, ignoreCase = true) },
            key = { "p-${it.activity}" },
        ) { app ->
            ListItem(
                headlineContent = { Text(app.label) },
                leadingContent = {
                    Image(
                        app.icon.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.size(32.dp),
                    )
                },
                modifier =
                    Modifier.clickable {
                        shortcut.launch(
                            Intent(Intent.ACTION_CREATE_SHORTCUT)
                                .setClassName(app.packageName, app.activity)
                        )
                    },
            )
        }
    }
    if (askDeepLink) {
        DeepLinkDialog(onDismiss = { askDeepLink = false }) { label, uri ->
            askDeepLink = false
            onPick(Item.DeepLink(key(), label, uri))
        }
    }
}

@Composable
private fun Header(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun PickRow(label: String, icon: String, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(label) },
        leadingContent = { Icon(painterResource(Symbols.byName.getValue(icon).outlined), null) },
        modifier = Modifier.clickable(onClick = onClick),
    )
}

@Composable
private fun DeepLinkDialog(onDismiss: () -> Unit, onSave: (String, String) -> Unit) {
    var label by remember { mutableStateOf("") }
    var uri by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Deep link") },
        text = {
            Column {
                OutlinedTextField(
                    label,
                    { label = it },
                    label = { Text("Name") },
                    singleLine = true,
                )
                OutlinedTextField(
                    uri,
                    { uri = it },
                    label = { Text("Link, such as myapp://onboarding") },
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = label.isNotBlank() && "://" in uri,
                onClick = { onSave(label.trim(), uri.trim()) },
            ) {
                Text("Add")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Reads what an app's ACTION_CREATE_SHORTCUT activity returned. */
object ShortcutResult {
    @Suppress("DEPRECATION") // The legacy extras are the only ones a non-launcher receives.
    fun toItem(context: Context, data: Intent): Item.Shortcut? {
        val target =
            (if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                data.getParcelableExtra(Intent.EXTRA_SHORTCUT_INTENT, Intent::class.java)
            } else {
                data.getParcelableExtra(Intent.EXTRA_SHORTCUT_INTENT)
            }) ?: return null
        val label = data.getStringExtra(Intent.EXTRA_SHORTCUT_NAME) ?: "Shortcut"
        val icon =
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                data.getParcelableExtra(Intent.EXTRA_SHORTCUT_ICON, Bitmap::class.java)
            } else {
                data.getParcelableExtra(Intent.EXTRA_SHORTCUT_ICON)
            }
        val key = UUID.randomUUID().toString()
        val iconFile = icon?.let { saveIcon(context, key, it) }
        return Item.Shortcut(key, label, target.toUri(Intent.URI_INTENT_SCHEME), iconFile)
    }

    private fun saveIcon(context: Context, key: String, bitmap: Bitmap): String {
        val relative = "icons/$key.png"
        val file = File(context.filesDir, relative).apply { parentFile?.mkdirs() }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return relative
    }
}
