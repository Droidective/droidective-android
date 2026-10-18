package com.droidective.android.ui.tools

import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.droidective.android.ui.Symbols
import com.droidective.android.ui.openSafely
import com.droidective.android.widget.AppIcons
import com.droidective.core.apps.AppAction
import com.droidective.core.apps.AppActions
import com.droidective.platform.AppCatalog
import com.droidective.platform.AppDetail
import com.droidective.platform.AppEntry
import com.droidective.platform.AppPermission
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The installed apps, and one app's actions and permissions. */
@Composable
fun AppsTool(tool: ToolContext) {
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    // Keeps the list's search and scroll while an app is open.
    val saved = rememberSaveableStateHolder()
    val current = selected
    if (current == null) {
        saved.SaveableStateProvider("list") { AppList(tool, onOpen = { selected = it }) }
    } else {
        val detailTool =
            remember(tool) { ToolContext(tool.snackbar, { selected = null }, tool.onOpenSetup) }
        AppDetailScreen(current, detailTool)
    }
}

@Composable
private fun AppList(tool: ToolContext, onOpen: (String) -> Unit) {
    val context = LocalContext.current
    var apps by remember { mutableStateOf<List<AppEntry>?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    var showSystem by rememberSaveable { mutableStateOf(false) }
    // Runs again on the way back from an app, so an uninstall or a disable shows.
    LaunchedEffect(Unit) { apps = AppCatalog.list(context) }
    val visible =
        apps.orEmpty().filter { app ->
            (showSystem || !app.system) &&
                (query.isBlank() ||
                    app.label.contains(query, ignoreCase = true) ||
                    app.packageName.contains(query, ignoreCase = true))
        }
    BackHandler(onBack = tool.onBack)
    Scaffold(snackbarHost = { SnackbarHost(tool.snackbar) }) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            item { ToolTitle("Apps", tool) }
            item {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("Search by name or package") },
                    leadingIcon = {
                        Icon(painterResource(Symbols.byName.getValue("search").outlined), null)
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    FilterChip(
                        selected = showSystem,
                        onClick = { showSystem = !showSystem },
                        label = { Text("System apps") },
                    )
                    Text(
                        when {
                            apps == null -> "Loading…"
                            visible.size == 1 -> "1 app"
                            else -> "${visible.size} apps"
                        },
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(visible, key = { it.packageName }) { app ->
                ListItem(
                    headlineContent = { Text(app.label) },
                    supportingContent = {
                        Text(
                            if (app.enabled) app.packageName else "${app.packageName} · Disabled",
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    },
                    leadingContent = { AppIcon(app.packageName, 40.dp) },
                    colors =
                        ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
                    modifier =
                        Modifier.clip(RoundedCornerShape(12.dp)).clickable {
                            onOpen(app.packageName)
                        },
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AppDetailScreen(packageName: String, tool: ToolContext) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val shell = rememberToolShell(tool)
    var detail by remember { mutableStateOf<AppDetail?>(null) }
    var loaded by remember { mutableStateOf(false) }
    var confirming by remember { mutableStateOf<AppAction?>(null) }
    suspend fun reload() {
        detail = AppCatalog.detail(context, packageName)
        loaded = true
    }
    LaunchedEffect(packageName) { reload() }
    val app = detail
    ToolScaffold(app?.entry?.label ?: packageName, tool) {
        if (app == null) {
            if (loaded) ToolNote("$packageName isn’t installed any more.")
            return@ToolScaffold
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            AppIcon(packageName, 56.dp)
            Column {
                Text(app.entry.label, style = MaterialTheme.typography.titleMedium)
                Text(
                    packageName,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (app.launchable && app.entry.enabled) {
                Button(
                    onClick = {
                        context.packageManager
                            .getLaunchIntentForPackage(packageName)
                            ?.let(context::openSafely)
                    }
                ) {
                    Text("Open")
                }
            }
            OutlinedButton(
                onClick = {
                    context.openSafely(
                        Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            "package:$packageName".toUri(),
                        )
                    )
                }
            ) {
                Text("App info")
            }
        }
        if (packageName == context.packageName) {
            ToolNote("Droidective doesn’t stop or clear itself from here.")
        } else {
            ToolSection("Actions") {
                actionsFor(app.entry).forEach { action ->
                    ActionRow(action) {
                        if (action.destructive) confirming = action
                        else
                            shell.run(
                                action.title,
                                listOf(AppActions.command(action, packageName)),
                                doneText(action, app.entry.label),
                            ) {
                                scope.launch { reload() }
                            }
                    }
                }
            }
            ToolSection("Permissions") {
                if (app.permissions.isEmpty()) ToolNote("It asks for no runtime permissions.")
                app.permissions.forEach { permission ->
                    PermissionRow(permission) { grant ->
                        shell.run(
                            if (grant) "Grant" else "Revoke",
                            listOf(AppActions.permission(packageName, permission.name, grant)),
                            shortName(permission.name) + if (grant) " granted" else " revoked",
                        ) {
                            scope.launch { reload() }
                        }
                    }
                }
            }
        }
        ToolSection("Details") {
            app.facts.forEach { InfoRow(it.label, it.value, copyable = true) }
        }
        ToolNote("Actions and permission changes run as the shell user, so they need Shell.")
    }
    confirming?.let { action ->
        val label = app?.entry?.label ?: packageName
        AlertDialog(
            onDismissRequest = { confirming = null },
            title = { Text("${action.title} $label?") },
            text = { Text(confirmText(action)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirming = null
                        shell.run(
                            action.title,
                            listOf(AppActions.command(action, packageName)),
                            doneText(action, label),
                        ) { succeeded ->
                            if (succeeded && action == AppAction.Uninstall) tool.onBack()
                            else scope.launch { reload() }
                        }
                    }
                ) {
                    Text(action.title)
                }
            },
            dismissButton = { TextButton(onClick = { confirming = null }) { Text("Cancel") } },
        )
    }
}

private fun actionsFor(app: AppEntry): List<AppAction> = buildList {
    add(AppAction.ForceStop)
    add(AppAction.ClearCache)
    add(AppAction.ClearData)
    add(if (app.enabled) AppAction.Disable else AppAction.Enable)
    // A system app can only be disabled; pm uninstall would just fail.
    if (!app.system) add(AppAction.Uninstall)
}

private fun doneText(action: AppAction, label: String): String =
    when (action) {
        AppAction.ForceStop -> "Stopped $label"
        AppAction.ClearCache -> "Cleared $label’s cache"
        AppAction.ClearData -> "Cleared $label’s data"
        AppAction.Disable -> "Disabled $label"
        AppAction.Enable -> "Enabled $label"
        AppAction.Uninstall -> "Uninstalled $label"
    }

private fun confirmText(action: AppAction): String =
    when (action) {
        AppAction.ClearData -> "Its accounts, settings, databases and files are deleted."
        AppAction.Disable -> "It leaves the launcher and stops running until you enable it here."
        AppAction.Uninstall -> "The app and its data are removed from this phone."
        AppAction.ForceStop,
        AppAction.ClearCache,
        AppAction.Enable -> "This takes effect at once."
    }

@Composable
private fun ActionRow(action: AppAction, onClick: () -> Unit) {
    Text(
        action.title,
        color =
            if (action.destructive) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.onSurface,
        modifier =
            Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClick = onClick)
                .padding(horizontal = 4.dp, vertical = 10.dp),
    )
}

@Composable
private fun PermissionRow(permission: AppPermission, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(shortName(permission.name))
            Text(
                permission.name,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = permission.granted, onCheckedChange = onChange)
    }
}

/** `android.permission.ACCESS_FINE_LOCATION` reads as "Access fine location". */
private fun shortName(permission: String): String =
    permission.substringAfterLast('.').lowercase().replace('_', ' ').replaceFirstChar {
        it.uppercase()
    }

@Composable
private fun AppIcon(packageName: String, size: Dp) {
    val context = LocalContext.current
    val icon by
        produceState<ImageBitmap?>(null, packageName) {
            value =
                withContext(Dispatchers.IO) {
                    AppIcons.bitmap(context, packageName).asImageBitmap()
                }
        }
    val current = icon
    if (current == null) Box(Modifier.size(size))
    else Image(current, contentDescription = null, modifier = Modifier.size(size))
}
