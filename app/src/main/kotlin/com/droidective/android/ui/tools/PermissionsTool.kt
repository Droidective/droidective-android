package com.droidective.android.ui.tools

import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.droidective.core.apps.AppActions
import com.droidective.core.apps.AppGrants
import com.droidective.core.apps.GroupHolder
import com.droidective.core.apps.PermissionGroup
import com.droidective.core.apps.PermissionGroups
import com.droidective.platform.AppCatalog
import kotlinx.coroutines.launch

/**
 * The phone's runtime permissions the other way round from the Apps tool: a kind of permission, and
 * every app that asks for it, held or not. The way to see who can use the camera, or to take one
 * away and watch how an app copes.
 */
@Composable
fun PermissionsTool(tool: ToolContext) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var grants by remember { mutableStateOf<List<AppGrants>?>(null) }
    var system by rememberSaveable { mutableStateOf(false) }
    var open by rememberSaveable { mutableStateOf<String?>(null) }
    val groups = remember { PermissionGroups.on(Build.VERSION.SDK_INT) }
    val reload: () -> Unit = { scope.launch { grants = AppCatalog.grants(context) } }
    LaunchedEffect(Unit) { grants = AppCatalog.grants(context) }
    val apps = grants?.filter { system || !it.system }
    val chosen = groups.firstOrNull { it.id == open }
    if (chosen != null && apps != null) {
        GroupScreen(
            chosen,
            apps,
            ToolContext(tool.snackbar, onBack = { open = null }, tool.onOpenSetup),
            reload,
        )
        return
    }
    ToolScaffold("Permissions", tool) {
        ToolNote(
            "Every app that asks for each kind of permission, and how many hold it. Granting and " +
                "revoking run as the shell user."
        )
        SwitchLine("Include system apps", system) { system = it }
        if (apps == null) {
            ToolNote("Reading the apps…")
            return@ToolScaffold
        }
        ToolSection("Kinds") {
            PermissionGroups.counts(groups, apps).forEach { count ->
                Column(
                    Modifier.fillMaxWidth()
                        .clickable(enabled = count.asking > 0) { open = count.group.id }
                        .padding(vertical = 8.dp, horizontal = 4.dp)
                ) {
                    Text(count.group.title)
                    Text(
                        if (count.asking == 0) "No app asks for it"
                        else "Allowed for ${count.allowed} of ${count.asking} that ask",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** One kind of permission: each app that asks for it, and a switch for each of its parts. */
@Composable
private fun GroupScreen(
    group: PermissionGroup,
    apps: List<AppGrants>,
    tool: ToolContext,
    onChanged: () -> Unit,
) {
    val shell = rememberToolShell(tool)
    val holders = remember(group, apps) { PermissionGroups.holders(group, apps) }
    ToolScaffold(group.title, tool) {
        val allowed = holders.count { it.allowed }
        ToolNote("Allowed for $allowed of the ${holders.size} apps that ask.")
        holders.forEach { holder ->
            ToolSection(holder.app.label) {
                Text(
                    holder.app.packageName,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                holder.permissions.forEach { (member, granted) ->
                    SwitchLine(title(holder, member), granted) { grant ->
                        shell.run(
                            if (grant) "Grant" else "Revoke",
                            listOf(
                                AppActions.permission(holder.app.packageName, member.name, grant)
                            ),
                            "${member.short} " + if (grant) "granted" else "revoked",
                        ) {
                            onChanged()
                        }
                    }
                }
            }
        }
    }
}

/** A switch's name: the part of the permission, or the group's when it has only the one. */
private fun title(holder: GroupHolder, member: PermissionGroup.Member): String =
    if (holder.permissions.size == 1) "Allowed" else member.short

@Composable
private fun SwitchLine(title: String, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!on) }.padding(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, modifier = Modifier.weight(1f))
        Switch(checked = on, onCheckedChange = onChange)
    }
}
