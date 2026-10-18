package com.droidective.android.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.droidective.android.DroidectiveApplication
import com.droidective.core.Access
import com.droidective.core.AccessLevel
import com.droidective.core.toggles.Outcome
import com.droidective.core.toggles.SpecialAccess
import com.droidective.core.toggles.ToggleDef
import com.droidective.core.toggles.Toggles
import com.droidective.core.toggles.Write
import kotlinx.coroutines.launch

/** Every built toggle, searchable, grouped the way the widget picker groups them. */
@Composable
fun TogglesScreen(app: DroidectiveApplication, onOpenSetup: () -> Unit) {
    val access by app.access.access.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val states = remember { mutableStateMapOf<String, String>() }
    var query by rememberSaveable { mutableStateOf("") }

    LaunchedEffect(access) {
        Toggles.all
            .filter { it.isBuilt }
            .forEach { toggle -> app.toggles.read(toggle)?.let { states[toggle.id] = it } }
    }

    val visible =
        Toggles.all.filter { toggle ->
            toggle.isBuilt &&
                (query.isBlank() ||
                    toggle.title.contains(query, ignoreCase = true) ||
                    toggle.group.title.contains(query, ignoreCase = true))
        }

    fun tap(toggle: ToggleDef) {
        scope.launch {
            val write = toggle.write
            if (write is Write.Api && write.api.opensScreen) {
                snackbar.showSnackbar("${toggle.title} arrives with the widgets.")
                return@launch
            }
            val outcome = app.toggles.apply(toggle, toggle.next(states[toggle.id]).id)
            when (outcome) {
                is Outcome.Applied -> if (!toggle.isAction) states[toggle.id] = outcome.state
                is Outcome.UseFallback ->
                    context.openSafely(Intent(outcome.fallback.settingsAction))
                is Outcome.NeedsSpecialAccess ->
                    context.openSafely(specialAccessIntent(context, outcome.access))
                is Outcome.DidNotStick -> outcome.actual?.let { states[toggle.id] = it }
                is Outcome.NeedsLevel,
                is Outcome.Failed -> Unit
            }
            val message = OutcomeText.describe(toggle, outcome) ?: return@launch
            val result =
                snackbar.showSnackbar(
                    message,
                    actionLabel = if (outcome is Outcome.NeedsLevel) "Set up" else null,
                )
            if (result == SnackbarResult.ActionPerformed) onOpenSetup()
        }
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
            item {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("Search toggles") },
                    leadingIcon = {
                        Icon(painterResource(Symbols.byName.getValue("search").outlined), null)
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                )
            }
            visible
                .groupBy { it.group }
                .forEach { (group, toggles) ->
                    item(key = "header-${group.name}") {
                        Text(
                            group.title.uppercase(),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 4.dp),
                        )
                    }
                    items(toggles, key = { it.id }) { toggle ->
                        ToggleRow(toggle, states[toggle.id], access, onClick = { tap(toggle) })
                    }
                }
        }
    }
}

@Composable
private fun ToggleRow(toggle: ToggleDef, state: String?, access: Access, onClick: () -> Unit) {
    val symbol = Symbols.byName.getValue(toggle.icon)
    val active = state != null && state != "off"
    val runsInPlace = access.allows(toggle.level)
    val supporting =
        when {
            !runsInPlace -> toggle.fallback?.label ?: "Needs ${toggle.level.title}"
            toggle.isAction -> null
            else -> OutcomeText.label(toggle, state).takeIf { state != null }
        }
    ListItem(
        headlineContent = { Text(toggle.title) },
        supportingContent = supporting?.let { { Text(it) } },
        leadingContent = {
            Icon(
                painterResource(if (active) symbol.filled else symbol.outlined),
                contentDescription = null,
                tint =
                    if (active) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        trailingContent = { LevelChip(toggle.level, missing = !runsInPlace) },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
        modifier =
            Modifier.padding(horizontal = 4.dp)
                .clip(RoundedCornerShape(12.dp))
                .clickable(onClick = onClick)
                .semantics(mergeDescendants = true) {},
    )
}

@Composable
fun LevelChip(level: AccessLevel, missing: Boolean) {
    val color = LocalLevelColors.current.of(level)
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (missing) {
            Box(
                Modifier.padding(top = 6.dp)
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(LocalLevelColors.current.shell)
                    .semantics { contentDescription = "Running on its stand-in" }
            )
        }
        Surface(shape = RoundedCornerShape(50), color = color.copy(alpha = 0.14f)) {
            Text(
                level.title,
                style = MaterialTheme.typography.labelSmall,
                color = color,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            )
        }
    }
}

private fun specialAccessIntent(context: Context, access: SpecialAccess): Intent =
    when (access) {
        SpecialAccess.ModifySystemSettings ->
            Intent(
                Settings.ACTION_MANAGE_WRITE_SETTINGS,
                Uri.parse("package:${context.packageName}"),
            )
        SpecialAccess.DoNotDisturb -> Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
    }

/** Opens a system screen; OEMs drop some actions, so a missing one is not a crash. */
fun Context.openSafely(intent: Intent) {
    try {
        startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
        startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
