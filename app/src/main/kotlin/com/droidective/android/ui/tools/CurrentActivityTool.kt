package com.droidective.android.ui.tools

import android.app.StatusBarManager
import android.content.ComponentName
import android.graphics.drawable.Icon
import android.os.Build
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.droidective.android.surface.ActivityLookup
import com.droidective.android.surface.CurrentActivities
import com.droidective.android.surface.CurrentActivityTile
import com.droidective.android.ui.Symbols
import com.droidective.android.ui.openUsageAccess

/**
 * The Mac's Current Activity. From inside Droidective the activity in front is Droidective's own,
 * so the screen shows the one you came from, and offers the tile that works over any app.
 */
@Composable
fun CurrentActivityTool(tool: ToolContext) {
    val context = LocalContext.current
    var resumes by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        resumes++
        onPauseOrDispose {}
    }
    var found by remember { mutableStateOf<ActivityLookup?>(null) }
    LaunchedEffect(resumes) {
        found = CurrentActivities.find(context, except = context.packageName)
    }

    ToolScaffold("Current Activity", tool) {
        ToolSection("The screen you came from") {
            when (val now = found) {
                null -> ToolNote("Reading…")
                is ActivityLookup.Found -> {
                    InfoRow("Activity", now.component, copyable = true, mono = true)
                    OutlinedButton(onClick = { CurrentActivities.copy(context, now.component) }) {
                        Text("Copy")
                    }
                }
                ActivityLookup.NoAccess -> {
                    ToolNote(
                        "Telling which screen you came from needs Usage access for Droidective."
                    )
                    OutlinedButton(onClick = { context.openUsageAccess() }) {
                        Text("Allow Usage access")
                    }
                }
                is ActivityLookup.Failed -> ToolNote(now.message)
            }
        }
        ToolSection("Quick Settings tile") {
            ToolNote(
                "Pull down Quick Settings over any app and tap Current activity to see which " +
                    "activity is on screen, and copy it. It uses Usage access, or Shell without it."
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                OutlinedButton(
                    onClick = {
                        context
                            .getSystemService(StatusBarManager::class.java)
                            .requestAddTileService(
                                ComponentName(context, CurrentActivityTile::class.java),
                                "Current activity",
                                Icon.createWithResource(
                                    context,
                                    Symbols.byName.getValue("layers").outlined,
                                ),
                                context.mainExecutor,
                            ) {}
                    }
                ) {
                    Text("Add the tile")
                }
            } else {
                ToolNote("Edit Quick Settings and drag Current activity in.")
            }
        }
    }
}
