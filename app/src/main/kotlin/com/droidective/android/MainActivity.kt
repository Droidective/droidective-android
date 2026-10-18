package com.droidective.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import com.droidective.android.ui.AppScreen
import com.droidective.android.ui.DroidectiveTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { DroidectiveTheme { AppScreen(droidective) } }
    }

    override fun onResume() {
        super.onResume()
        // Access can change while the app is away: Shizuku started, a grant from the Mac,
        // Modify system settings turned on.
        lifecycleScope.launch { droidective.access.refresh() }
    }
}
