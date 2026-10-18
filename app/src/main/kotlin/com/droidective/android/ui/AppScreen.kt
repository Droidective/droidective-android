package com.droidective.android.ui

import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.painterResource
import com.droidective.android.DroidectiveApplication
import com.droidective.android.ui.tools.ToolsScreen

enum class Destination(val label: String, val icon: String) {
    Toggles("Toggles", "toggle_on"),
    Tools("Tools", "build"),
    Widgets("Widgets", "widgets"),
    Setup("Setup", "shield"),
}

/**
 * A place asked for from outside: a widget's Setup tap, a tile, a toggle that needs a value. Each
 * request is new, so asking for the same place twice goes there twice.
 *
 * @property tool the tool to open, for [Destination.Tools].
 */
data class Request(
    val destination: Destination,
    val tool: String? = null,
    val id: Long = System.nanoTime(),
)

/** The app's frame: a bottom bar on phones, a rail on tablets and foldables. */
@Composable
fun AppScreen(app: DroidectiveApplication, request: Request?) {
    var destination by rememberSaveable { mutableStateOf(Destination.Toggles) }
    var openTool by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(request) {
        request?.let {
            destination = it.destination
            it.tool?.let { tool -> openTool = tool }
        }
    }
    NavigationSuiteScaffold(
        navigationSuiteItems = {
            Destination.entries.forEach { item ->
                val symbol = Symbols.byName.getValue(item.icon)
                item(
                    selected = item == destination,
                    onClick = {
                        // Tapping Tools while in a tool goes back to the list.
                        if (item == destination && item == Destination.Tools) openTool = null
                        destination = item
                    },
                    icon = {
                        Icon(
                            painterResource(
                                if (item == destination) symbol.filled else symbol.outlined
                            ),
                            contentDescription = null,
                        )
                    },
                    label = { Text(item.label) },
                )
            }
        }
    ) {
        when (destination) {
            Destination.Toggles ->
                TogglesScreen(app, onOpenSetup = { destination = Destination.Setup })
            Destination.Tools ->
                ToolsScreen(
                    openTool,
                    onOpen = { openTool = it },
                    onOpenSetup = { destination = Destination.Setup },
                )
            Destination.Widgets -> WidgetsScreen(app)
            Destination.Setup -> SetupScreen(app)
        }
    }
}
