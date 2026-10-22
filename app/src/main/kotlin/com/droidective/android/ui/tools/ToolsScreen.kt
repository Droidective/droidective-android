package com.droidective.android.ui.tools

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.droidective.android.droidective
import com.droidective.android.shelf.Shelf
import com.droidective.android.shelf.ToolShortcuts
import com.droidective.android.ui.LevelChip
import com.droidective.android.ui.Symbols
import com.droidective.core.features.FeatureDef
import com.droidective.core.features.Features
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Each tool's screen, by the Mac's feature id. ToolScreensTest checks it covers every feature. */
val toolScreens: Map<String, @Composable (ToolContext) -> Unit> =
    mapOf(
        "dev-settings" to { DeveloperSettingsTool(it) },
        "logcat" to { LogcatTool(it) },
        "performance" to { PerformanceTool(it) },
        "react-native" to { ReactNativeTool(it) },
        "simulate" to { SimulateTool(it) },
        "demo-mode" to { DemoModeTool(it) },
        "system-restrictions" to { RestrictionsTool(it) },
        "network-traffic" to { NetworkTrafficTool(it) },
        "http-proxy" to { ProxyTool(it) },
        "private-dns" to { PrivateDnsTool(it) },
        "wireless-adb" to { WirelessAdbTool(it) },
        "wifi" to { WifiTool(it) },
        "network-speed" to { NetworkSpeedTool(it) },
        "network-check" to { NetworkCheckTool(it) },
        "get-ip" to { GetIpTool(it) },
        "apps" to { AppsTool(it) },
        "install-app" to { InstallAppTool(it) },
        "current-activity" to { CurrentActivityTool(it) },
        "ui-inspector" to { UiInspectorTool(it) },
        "intent-builder" to { IntentBuilderTool(it) },
        "meminfo" to { MemoryTool(it) },
        "sandbox-browser" to { SandboxTool(it) },
        "app-snapshots" to { AppSnapshotsTool(it) },
        "apk-inspector" to { ApkInspectorTool(it) },
        "background-work" to { BackgroundWorkTool(it) },
        "settings-editor" to { SettingsEditorTool(it) },
        "file-explorer" to { FileExplorerTool(it) },
        "locale" to { LanguageTool(it) },
        "monkey" to { MonkeyTool(it) },
        "notification-inspector" to { NotificationInspectorTool(it) },
        "crash-catcher" to { CrashCatcherTool(it) },
        "custom-commands" to { CustomCommandsTool(it) },
        "terminal" to { TerminalTool(it) },
        "deep-link" to { DeepLinksTool(it) },
        "device-info" to { DeviceInfoTool(it) },
        "bug-report" to { BugReportTool(it) },
        "screen-record" to { ScreenRecordTool(it) },
        "screenshot" to { ScreenshotTool(it) },
        "root-status" to { RootStatusTool(it) },
    )

/**
 * The Tools tab: the list, or the tool in [open].
 *
 * @param onOpen opens a tool by id, or goes back to the list with null.
 */
@Composable
fun ToolsScreen(open: String?, onOpen: (String?) -> Unit, onOpenSetup: () -> Unit) {
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    // Keeps the list's search and scroll while a tool is open.
    val saved = rememberSaveableStateHolder()
    val screen = open?.let(toolScreens::get)
    LaunchedEffect(open) {
        val opened = open?.takeIf { screen != null } ?: return@LaunchedEffect
        withContext(Dispatchers.IO) { Shelf.opened(context, opened) }
    }
    if (open != null && screen != null) {
        val tool = remember(open) { ToolContext(snackbar, onBack = { onOpen(null) }, onOpenSetup) }
        key(open) { screen(tool) }
    } else {
        saved.SaveableStateProvider("list") { ToolList(onOpen, snackbar) }
    }
}

@Composable
private fun ToolList(onOpen: (String) -> Unit, snackbar: SnackbarHostState) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val access by context.droidective.access.access.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { Shelf.load(context) } }
    val shelf by Shelf.shelf.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    val visible = Features.all.filter { it.matches(query) }
    val actions =
        ToolActions(
            isPinned = shelf::isPinned,
            onPin = { id, on -> scope.launch(Dispatchers.IO) { Shelf.pin(context, id, on) } },
            onHome = { feature ->
                if (!ToolShortcuts.pinToHome(context, feature)) {
                    scope.launch { snackbar.showSnackbar("This launcher doesn't take shortcuts.") }
                }
            },
        )
    val row: @Composable (FeatureDef) -> Unit = { feature ->
        ToolRow(feature, missing = !access.allows(feature.level), actions) { onOpen(feature.id) }
    }
    LazyColumn(Modifier.fillMaxSize().windowInsetsPadding(ScaffoldDefaults.contentWindowInsets)) {
        item {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Search tools") },
                leadingIcon = {
                    Icon(painterResource(Symbols.byName.getValue("search").outlined), null)
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(16.dp),
            )
        }
        if (query.isBlank()) {
            shelfSection("pinned", "Pinned", shelf.pinned, row)
            shelfSection("recent", "Recent", shelf.recentNotPinned(RECENT_SHOWN), row)
        }
        visible
            .groupBy { it.category }
            .forEach { (category, features) ->
                item(key = "header-${category.name}") { Header(category.title) }
                items(features, key = { it.id }) { feature -> row(feature) }
            }
    }
}

/** A row of the shelf's tools above the categories; keyed apart, since they appear twice. */
private fun LazyListScope.shelfSection(
    key: String,
    title: String,
    ids: List<String>,
    row: @Composable (FeatureDef) -> Unit,
) {
    val features = ids.mapNotNull(Features::byId)
    if (features.isEmpty()) return
    item(key = "header-$key") { Header(title) }
    items(features, key = { "$key-${it.id}" }) { feature -> row(feature) }
}

@Composable
private fun Header(title: String) {
    Text(
        title.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 4.dp),
    )
}

/** What a long press on a tool offers. */
private class ToolActions(
    val isPinned: (String) -> Boolean,
    val onPin: (String, Boolean) -> Unit,
    val onHome: (FeatureDef) -> Unit,
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ToolRow(
    feature: FeatureDef,
    missing: Boolean,
    actions: ToolActions,
    onClick: () -> Unit,
) {
    val context = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    Box {
        ListItem(
            headlineContent = { Text(feature.title) },
            supportingContent = { Text(feature.subtitle) },
            leadingContent = {
                Icon(
                    painterResource(Symbols.byName.getValue(feature.icon).outlined),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            trailingContent = { LevelChip(feature.level, missing) },
            colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
            modifier =
                Modifier.padding(horizontal = 4.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .combinedClickable(onClick = onClick, onLongClick = { menu = true }),
        )
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            val pinned = actions.isPinned(feature.id)
            DropdownMenuItem(
                text = { Text(if (pinned) "Unpin" else "Pin to the top") },
                onClick = {
                    actions.onPin(feature.id, !pinned)
                    menu = false
                },
            )
            if (ToolShortcuts.canPin(context)) {
                DropdownMenuItem(
                    text = { Text("Add to home screen") },
                    onClick = {
                        actions.onHome(feature)
                        menu = false
                    },
                )
            }
        }
    }
}

/** Under Pinned: enough to be the tools in use this week, not a second list of everything. */
private const val RECENT_SHOWN = 4
