package com.droidective.platform

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.PowerManager
import com.droidective.core.automations.Clock.holdsAt
import com.droidective.core.automations.State
import com.droidective.core.shell.Parse
import com.droidective.core.shell.Shell
import com.droidective.core.shell.ShellCommand
import com.droidective.core.shell.ShellUnavailableException
import java.time.LocalDateTime

/**
 * Whether a state holds right now, for Only if conditions and for a While noticed after a restart.
 * Null means it can't be told, which an automation treats as not holding.
 *
 * @param shell the shell, when there is one; the Wi-Fi network's name and the app in front need it.
 * @param toggleState a toggle's current state, the way every surface reads it.
 */
class StateReader(
    private val context: Context,
    private val shell: () -> Shell?,
    private val toggleState: suspend (String) -> String?,
) {
    suspend fun holds(state: State): Boolean? =
        when (state) {
            is State.OnWifi -> onWifi(state.network)
            // The connection broadcasts arrive with the Bluetooth triggers; until then, unknown.
            is State.BluetoothConnected -> null
            is State.AppOpen ->
                inShell(DUMPSYS_ACTIVITIES)?.let(Parse::resumedPackage)?.let {
                    it == state.packageName
                }
            State.Charging -> battery()?.let { it.plugged != 0 }
            State.ScreenOn -> context.getSystemService(PowerManager::class.java).isInteractive
            State.Headphones -> headphones()
            is State.BatteryBelow -> battery()?.let { it.percent < state.percent }
            is State.BatteryAbove -> battery()?.let { it.percent > state.percent }
            is State.ToggleIs -> toggleState(state.toggleId)?.let { it == state.state }
            is State.TimeBetween -> state.holdsAt(LocalDateTime.now())
            State.ShellAvailable -> shell() != null
        }

    private suspend fun onWifi(network: String?): Boolean? {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val caps = connectivity.activeNetwork?.let(connectivity::getNetworkCapabilities)
        val wifi = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        if (network == null || !wifi) return wifi
        // Without location access Android hides the name from apps; the shell can read it.
        val ssid = inShell(WIFI_STATUS) ?: return null
        return Parse.wifiSsid(ssid) == network
    }

    private data class Battery(val percent: Int, val plugged: Int)

    private fun battery(): Battery? {
        val sticky =
            context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                ?: return null
        val level = sticky.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = sticky.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level < 0 || scale <= 0) return null
        return Battery(level * 100 / scale, sticky.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0))
    }

    private fun headphones(): Boolean {
        val audio = context.getSystemService(AudioManager::class.java)
        return audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { it.type in wired }
    }

    private suspend fun inShell(command: ShellCommand): String? {
        val shell = shell() ?: return null
        return try {
            shell.run(command).takeIf { it.succeeded }?.stdout
        } catch (_: ShellUnavailableException) {
            null
        }
    }

    private companion object {
        val WIFI_STATUS = ShellCommand.of("cmd", "wifi", "status")
        val DUMPSYS_ACTIVITIES = ShellCommand.of("dumpsys", "activity", "activities")
        val wired =
            setOf(
                AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                AudioDeviceInfo.TYPE_WIRED_HEADSET,
                AudioDeviceInfo.TYPE_USB_HEADSET,
            )
    }
}
