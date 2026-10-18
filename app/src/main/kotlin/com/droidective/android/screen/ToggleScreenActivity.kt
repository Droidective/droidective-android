package com.droidective.android.screen

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.droidective.android.folder.Scrim
import com.droidective.android.ui.DroidectiveTheme
import com.droidective.android.ui.openSafely
import com.droidective.android.ui.specialAccessIntent
import com.droidective.core.toggles.SpecialAccess

/** The screen behind toggles whose job is a screen: the brightness slider and discoverability. */
class ToggleScreenActivity : ComponentActivity() {
    private val askBluetooth =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startDiscoverable()
            finish()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        when (intent.getStringExtra(EXTRA_TOGGLE_ID)) {
            "brightness-slider" ->
                setContent {
                    DroidectiveTheme { Scrim(onDismiss = ::finish) { BrightnessPanel() } }
                }
            "bluetooth-discoverable" -> discoverable()
            else -> finish()
        }
    }

    private fun discoverable() {
        val needs = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
        if (
            needs &&
                checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE) !=
                    PackageManager.PERMISSION_GRANTED
        ) {
            askBluetooth.launch(Manifest.permission.BLUETOOTH_ADVERTISE)
        } else {
            startDiscoverable()
            finish()
        }
    }

    private fun startDiscoverable() {
        openSafely(Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE))
    }

    companion object {
        const val EXTRA_TOGGLE_ID = "com.droidective.android.extra.TOGGLE_ID"
    }
}

/** Brightness as a slider, written live. It also switches off auto brightness, as a slider must. */
@Composable
private fun BrightnessPanel() {
    val context = LocalContext.current
    val resolver = context.contentResolver
    val canWrite = remember { Settings.System.canWrite(context) }
    var level by remember {
        mutableFloatStateOf(
            Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS, 128) / 255f
        )
    }
    Surface(
        shape = RoundedCornerShape(24.dp),
        tonalElevation = 6.dp,
        modifier = Modifier.widthIn(max = 420.dp),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Brightness", style = MaterialTheme.typography.titleMedium)
            if (canWrite) {
                Slider(
                    value = level,
                    onValueChange = {
                        level = it
                        Settings.System.putInt(
                            resolver,
                            Settings.System.SCREEN_BRIGHTNESS_MODE,
                            Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL,
                        )
                        Settings.System.putInt(
                            resolver,
                            Settings.System.SCREEN_BRIGHTNESS,
                            (it * 255).toInt(),
                        )
                    },
                )
            } else {
                Text("Allow Modify system settings for Droidective to use the slider.")
                Button(
                    onClick = {
                        context.openSafely(
                            specialAccessIntent(context, SpecialAccess.ModifySystemSettings)
                        )
                    }
                ) {
                    Text("Allow")
                }
            }
        }
    }
}
