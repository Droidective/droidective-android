package com.droidective.android.ui.tools

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import com.droidective.android.ui.LevelChip
import com.droidective.android.ui.Symbols
import com.droidective.core.features.FeatureDef
import com.droidective.core.features.Features

/** Each tool's screen, by the Mac's feature id. ToolScreensTest checks it covers every feature. */
val toolScreens: Map<String, @Composable (ToolContext) -> Unit> =
    mapOf(
        "dev-settings" to { DeveloperSettingsTool(it) },
        "simulate" to { SimulateTool(it) },
        "demo-mode" to { DemoModeTool(it) },
        "system-restrictions" to { RestrictionsTool(it) },
        "http-proxy" to { ProxyTool(it) },
        "private-dns" to { PrivateDnsTool(it) },
        "wireless-adb" to { WirelessAdbTool(it) },
        "wifi" to { WifiTool(it) },
        "network-speed" to { NetworkSpeedTool(it) },
        "get-ip" to { GetIpTool(it) },
        "apps" to { AppsTool(it) },
        "custom-commands" to { CustomCommandsTool(it) },
        "deep-link" to { DeepLinksTool(it) },
        "device-info" to { DeviceInfoTool(it) },
        "root-status" to { RootStatusTool(it) },
    )

/**
 * The Tools tab: the list, or the tool in [open].
 *
 * @param onOpen opens a tool by id, or goes back to the list with null.
 */
@Composable
fun ToolsScreen(open: String?, onOpen: (String?) -> Unit, onOpenSetup: () -> Unit) {
    val snackbar = remember { SnackbarHostState() }
    // Keeps the list's search and scroll while a tool is open.
    val saved = rememberSaveableStateHolder()
    val screen = open?.let(toolScreens::get)
    if (open != null && screen != null) {
        val tool = remember(open) { ToolContext(snackbar, onBack = { onOpen(null) }, onOpenSetup) }
        key(open) { screen(tool) }
    } else {
        saved.SaveableStateProvider("list") { ToolList(onOpen) }
    }
}

@Composable
private fun ToolList(onOpen: (String) -> Unit) {
    val access by LocalContext.current.droidective.access.access.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    val visible = Features.all.filter { it.matches(query) }
    LazyColumn(Modifier.fillMaxSize()) {
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
        visible
            .groupBy { it.category }
            .forEach { (category, features) ->
                item(key = "header-${category.name}") {
                    Text(
                        category.title.uppercase(),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 4.dp),
                    )
                }
                items(features, key = { it.id }) { feature ->
                    ToolRow(feature, missing = !access.allows(feature.level)) { onOpen(feature.id) }
                }
            }
    }
}

@Composable
private fun ToolRow(feature: FeatureDef, missing: Boolean, onClick: () -> Unit) {
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
                .clickable(onClick = onClick),
    )
}
