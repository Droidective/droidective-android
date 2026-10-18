package com.droidective.android.ui

import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.painterResource
import com.droidective.android.DroidectiveApplication

private enum class Destination(val label: String, val icon: String) {
    Toggles("Toggles", "toggle_on"),
    Setup("Setup", "shield"),
}

/** The app's frame: a bottom bar on phones, a rail on tablets and foldables. */
@Composable
fun AppScreen(app: DroidectiveApplication) {
    var destination by rememberSaveable { mutableStateOf(Destination.Toggles) }
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
            Destination.Setup -> SetupScreen(app)
        }
    }
}
