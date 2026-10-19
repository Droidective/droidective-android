package com.droidective.android.ui.tools

import android.os.Environment
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.droidective.android.droidective
import com.droidective.core.apps.PhoneFiles

/** A place worth a tap: a name, and its folder. */
private data class Place(val name: String, val path: String)

/**
 * The Mac's File Explorer, on the phone itself: any folder the shell user (or root) can read, with
 * the same viewers as the Sandbox Browser: preferences, databases, text, and a copy saved anywhere.
 * Nothing here writes to the files it shows.
 */
@Composable
fun FileExplorerTool(tool: ToolContext) {
    val context = LocalContext.current
    val access by context.droidective.access.access.collectAsStateWithLifecycle()
    val places = remember { places() }
    var path by rememberSaveable { mutableStateOf(places.first().path) }
    val files = remember { Files(PhoneFiles(places.first().path), copies = "files") }

    ToolScaffold("File Explorer", tool) {
        if (access.shell == null) {
            ToolNote(
                "Reading the phone's folders needs Shell, through Shizuku or root. Root also " +
                    "reaches every app's data."
            )
            OutlinedButton(onClick = tool.onOpenSetup) { Text("Open Setup") }
            return@ToolScaffold
        }
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            places.forEach { place ->
                FilterChip(
                    selected = path == place.path,
                    onClick = { path = place.path },
                    label = { Text(place.name) },
                )
            }
        }
        FileBrowser(files, path, onPath = { path = it }, tool)
    }
}

@Suppress("DEPRECATION") // The shell needs the paths themselves; MediaStore has none to give.
private fun places(): List<Place> =
    listOf(
        Place("Storage", Environment.getExternalStorageDirectory().path),
        Place(
            "Downloads",
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).path,
        ),
        Place("Shell's folder", "/data/local/tmp"),
        Place("System", "/system"),
        Place("Processes", "/proc"),
        Place("Kernel", "/sys"),
        Place("Top", "/"),
    )
