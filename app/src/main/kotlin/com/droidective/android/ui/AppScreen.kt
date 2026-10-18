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

enum class Destination(val label: String, val icon: String) {
    Toggles("Toggles", "toggle_on"),
    Widgets("Widgets", "widgets"),
    Setup("Setup", "shield"),
}

/**
 * The app's frame: a bottom bar on phones, a rail on tablets and foldables.
 *
 * @param requested a destination asked for from outside, such as a tile or a widget's Setup tap.
 */
@Composable
fun AppScreen(app: DroidectiveApplication, requested: Destination?) {
    var destination by rememberSaveable { mutableStateOf(Destination.Toggles) }
    LaunchedEffect(requested) { requested?.let { destination = it } }
    NavigationSuiteScaffold(
        navigationSuiteItems = {
            Destination.entries.forEach { item ->
                val symbol = Symbols.byName.getValue(item.icon)
                item(
                    selected = item == destination,
                    onClick = { destination = item },
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
            Destination.Widgets -> WidgetsScreen(app)
            Destination.Setup -> SetupScreen(app)
        }
    }
}
