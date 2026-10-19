package com.droidective.android.ui

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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.droidective.android.DroidectiveApplication
import com.droidective.core.Access
import com.droidective.core.AccessLevel
import com.droidective.core.toggles.ToggleDef
import com.droidective.core.toggles.Toggles
import com.droidective.platform.hasHardwareFor

/** Every built toggle, searchable, grouped the way the widget picker groups them. */
@Composable
fun TogglesScreen(app: DroidectiveApplication, onOpenSetup: () -> Unit) {
    val access by app.access.access.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val built = remember { Toggles.all.filter { it.isBuilt && context.hasHardwareFor(it) } }
    val states = rememberToggleStates(built)
    var query by rememberSaveable { mutableStateOf("") }

    val visible = built.filter { toggle ->
        query.isBlank() ||
            toggle.title.contains(query, ignoreCase = true) ||
            toggle.group.title.contains(query, ignoreCase = true)
    }

    fun tap(toggle: ToggleDef) = scope.tapToggle(context, toggle, snackbar, onOpenSetup)

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
fun ToggleRow(
    toggle: ToggleDef,
    state: String?,
    access: Access,
    containerColor: Color = MaterialTheme.colorScheme.surface,
    onClick: () -> Unit,
) {
    val symbol = Symbols.byName.getValue(toggle.icon)
    val active = toggle.isActive(state)
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
        colors = ListItemDefaults.colors(containerColor = containerColor),
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
