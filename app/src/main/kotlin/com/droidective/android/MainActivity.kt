package com.droidective.android

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.lifecycleScope
import com.droidective.android.surface.Launch
import com.droidective.android.surface.Surfaces
import com.droidective.android.ui.AppScreen
import com.droidective.android.ui.Destination
import com.droidective.android.ui.DroidectiveTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val requested = mutableStateOf<Destination?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requested.value = destinationOf(intent)
        setContent { DroidectiveTheme { AppScreen(droidective, requested.value) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        requested.value = destinationOf(intent)
    }

    override fun onResume() {
        super.onResume()
        // Access can change while the app is away: Shizuku started, a grant from the Mac,
        // Modify system settings turned on. Every surface redraws with the new answer.
        lifecycleScope.launch {
            droidective.access.refresh()
            Surfaces.refresh(this@MainActivity)
        }
    }

    private fun destinationOf(intent: Intent?): Destination? =
        when (intent?.getStringExtra(Launch.EXTRA_DESTINATION)) {
            Launch.DESTINATION_SETUP -> Destination.Setup
            Launch.DESTINATION_TILES -> Destination.Widgets
            else -> null
        }
}
