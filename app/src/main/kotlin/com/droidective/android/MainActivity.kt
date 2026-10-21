package com.droidective.android

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.lifecycleScope
import com.droidective.android.automation.AutomationService
import com.droidective.android.crashes.CrashAlerts
import com.droidective.android.surface.Incoming
import com.droidective.android.surface.Launch
import com.droidective.android.surface.Surfaces
import com.droidective.android.ui.AppScreen
import com.droidective.android.ui.Destination
import com.droidective.android.ui.DroidectiveTheme
import com.droidective.android.ui.Request
import com.droidective.core.features.Features
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val request = mutableStateOf<Request?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // After a rotation the screen restores itself; the launch intent was already handled.
        if (savedInstanceState == null) request.value = requestOf(intent)
        setContent { DroidectiveTheme { AppScreen(droidective, request.value) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        request.value = requestOf(intent)
    }

    override fun onResume() {
        super.onResume()
        // Access can change while the app is away: Shizuku started, a grant from the Mac,
        // Modify system settings turned on. Every surface redraws with the new answer.
        lifecycleScope.launch {
            droidective.access.refresh()
            Surfaces.refresh(this@MainActivity)
        }
        // Android may have refused to start it from the background; on screen, it can't.
        AutomationService.sync(this)
    }

    private fun requestOf(intent: Intent?): Request? =
        when (intent?.getStringExtra(Launch.EXTRA_DESTINATION)) {
            Launch.DESTINATION_SETUP -> Request(Destination.Setup)
            Launch.DESTINATION_TILES -> Request(Destination.Widgets)
            Launch.DESTINATION_TOOL -> {
                CrashAlerts.refFrom(intent)?.let { CrashAlerts.focus.value = it }
                // Any app can start this activity, so only a real tool id opens anything.
                intent
                    .getStringExtra(Launch.EXTRA_TOOL)
                    ?.takeIf { Features.byId(it) != null }
                    ?.let { Request(Destination.Tools, tool = it) }
            }
            // Opened from Files or shared from another app: a package to install, a link to try.
            else ->
                Incoming.route(intent ?: return null)?.let { Request(Destination.Tools, tool = it) }
        }
}
