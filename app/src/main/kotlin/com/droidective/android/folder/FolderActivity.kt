package com.droidective.android.folder

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.droidective.android.droidective
import com.droidective.android.surface.CommandTaps
import com.droidective.android.surface.Launch
import com.droidective.android.surface.ShellRuns
import com.droidective.android.surface.Surfaces
import com.droidective.android.ui.CellButton
import com.droidective.android.ui.CommandOutput
import com.droidective.android.ui.DroidectiveTheme
import com.droidective.android.ui.openSafely
import com.droidective.android.ui.runInForeground
import com.droidective.core.layout.Cell
import com.droidective.core.layout.Tap
import com.droidective.core.toggles.Toggles
import kotlinx.coroutines.launch

/** A folder from a widget or the app: a small panel of more items over whatever was on screen. */
class FolderActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val folderId = intent.getStringExtra(EXTRA_FOLDER_ID)
        val folder = folderId?.let { droidective.layouts.get(it) }
        if (folder == null) {
            finish()
            return
        }
        setContent {
            DroidectiveTheme {
                Scrim(onDismiss = ::finish) {
                    FolderPanel(
                        folder.id,
                        folder.name,
                        folder.style.iconOn,
                        folder.style.iconOff,
                        ::finish,
                    )
                }
            }
        }
    }

    companion object {
        const val EXTRA_FOLDER_ID = "com.droidective.android.extra.FOLDER_ID"
    }
}

@Composable
private fun FolderPanel(id: String, name: String, on: Long, off: Long, onOpened: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf<String?>(null) }
    val cells by
        produceState(emptyList<Cell>(), id) {
            suspend fun load() =
                context.droidective.layouts.get(id)?.let { Surfaces.cells(context, it) }.orEmpty()
            value = load()
            Surfaces.changes.collect { value = load() }
        }
    Surface(
        shape = RoundedCornerShape(24.dp),
        tonalElevation = 6.dp,
        modifier = Modifier.widthIn(max = 420.dp),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(name, style = MaterialTheme.typography.titleMedium)
            LazyVerticalGrid(columns = GridCells.Adaptive(76.dp)) {
                items(cells, key = { it.key }) { cell ->
                    CellButton(cell, Color(on), Color(off), showLabel = true) {
                        when (val tap = cell.tap) {
                            is Tap.RunToggle ->
                                scope.launch {
                                    val toggle = Toggles.byId(tap.toggleId) ?: return@launch
                                    message = runInForeground(context, toggle).second
                                }
                            is Tap.RunCommand ->
                                scope.launch {
                                    val command =
                                        context.droidective.commands.get(tap.commandId)
                                            ?: return@launch
                                    val result = CommandTaps.execute(context, command)
                                    if (result is ShellRuns.Result.Ran)
                                        Surfaces.refreshBehind(context)
                                    message = CommandOutput.describe(command.name, result)
                                }
                            is Tap.Open -> {
                                Launch.intentFor(context, tap.target)?.let(context::openSafely)
                                onOpened()
                            }
                        }
                    }
                }
            }
            message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

/** A dimmed backdrop; tapping it closes the panel, as tapping outside a dialog does. */
@Composable
fun Scrim(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    Box(
        Modifier.fillMaxSize()
            .background(Color.Black.copy(alpha = 0.4f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss,
            )
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        // Taps inside the panel must not reach the scrim.
        Box(
            Modifier.clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) {}
        ) {
            content()
        }
    }
}
