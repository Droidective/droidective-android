package com.droidective.android.surface

import android.app.NotificationManager
import android.appwidget.AppWidgetManager
import android.bluetooth.BluetoothAdapter
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.nfc.NfcAdapter
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.edit
import com.droidective.android.automation.AutomationService
import com.droidective.android.droidective
import com.droidective.android.widget.ToggleBarReceiver
import com.droidective.core.device.AppFreezers
import com.droidective.core.layout.LayoutKind
import com.droidective.core.layout.SurfaceWatch
import com.droidective.core.toggles.PlatformApi
import com.droidective.core.toggles.Probe
import com.droidective.core.toggles.ToggleDef

/**
 * Keeps placed surfaces live through the automation service. On a phone that holds a frozen app's
 * widget taps ([AppFreezers]), a running foreground service is what makes a tap land at once; while
 * it runs it also redraws the surfaces when what they show changes outside the app.
 */
object LiveSurfaces {
    private const val PREFS = "live-surfaces"
    private const val KEY_ON = "on"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** On by default where widget taps are held, off elsewhere; the Widgets tab changes it. */
    fun enabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ON, AppFreezers.holdsWidgetTaps(Build.MANUFACTURER))

    fun setEnabled(context: Context, on: Boolean) {
        prefs(context).edit { putBoolean(KEY_ON, on) }
        AutomationService.sync(context)
    }

    /** What the placed surfaces show, or null when none is placed or live surfaces are off. */
    fun watched(context: Context): SurfaceWatch? {
        if (!enabled(context)) return null
        val app = context.droidective
        val widgets =
            AppWidgetManager.getInstance(context)
                .getAppWidgetIds(ComponentName(context, ToggleBarReceiver::class.java))
                .toSet()
        val shown =
            app.layouts.all().filter { layout ->
                when (layout.kind) {
                    LayoutKind.Bar -> app.layouts.widgetsShowing(layout.id).any { it in widgets }
                    LayoutKind.Notification -> true
                    LayoutKind.Folder -> false
                }
            }
        val tiles = TileSlots.all(context).values
        if (shown.isEmpty() && tiles.isEmpty()) return null
        return SurfaceWatch.of(shown, tiles)
    }

    /** The settings Android notifies when [toggle]'s state changes, whoever changed it. */
    fun settingUris(toggle: ToggleDef): List<Uri> =
        when (val probe = toggle.read) {
            is Probe.Setting -> listOf(probe.uri())
            is Probe.Api -> apiSettings(probe.api)
            is Probe.Command,
            Probe.None -> emptyList()
        }

    /** The broadcasts Android sends when [toggle]'s state changes, for a running receiver. */
    fun broadcasts(toggle: ToggleDef): List<String> =
        when (val api = (toggle.read as? Probe.Api)?.api) {
            PlatformApi.WifiEnabled -> listOf(WifiManager.WIFI_STATE_CHANGED_ACTION)
            PlatformApi.AirplaneMode -> listOf(Intent.ACTION_AIRPLANE_MODE_CHANGED)
            PlatformApi.BluetoothEnabled -> listOf(BluetoothAdapter.ACTION_STATE_CHANGED)
            PlatformApi.NfcEnabled -> listOf(NfcAdapter.ACTION_ADAPTER_STATE_CHANGED)
            PlatformApi.DataSaver -> listOf(ConnectivityManager.ACTION_RESTRICT_BACKGROUND_CHANGED)
            PlatformApi.BatterySaver -> listOf(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
            PlatformApi.RingerMode -> listOf(AudioManager.RINGER_MODE_CHANGED_ACTION)
            PlatformApi.DoNotDisturb ->
                listOf(NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED)
            else -> emptyList()
        }

    /** The flashlight announces itself only through a torch callback. */
    fun followsTorch(toggle: ToggleDef): Boolean =
        (toggle.read as? Probe.Api)?.api == PlatformApi.Flashlight

    /**
     * Whether only looking again finds a change to [toggle]: a command's output, or an API with no
     * announcement (media volume, auto-sync). The app's own states (Keep screen on, Demo mode)
     * don't change behind its back.
     */
    fun needsPolling(toggle: ToggleDef): Boolean =
        when (val probe = toggle.read) {
            is Probe.Command -> true
            is Probe.Api -> probe.api in setOf(PlatformApi.MediaVolume, PlatformApi.AutoSync)
            is Probe.Setting,
            Probe.None -> false
        }

    private fun apiSettings(api: PlatformApi): List<Uri> =
        when (api) {
            PlatformApi.WifiEnabled -> listOf(Settings.Global.getUriFor("wifi_on"))
            PlatformApi.MobileData -> listOf(Settings.Global.getUriFor("mobile_data"))
            PlatformApi.AirplaneMode ->
                listOf(Settings.Global.getUriFor(Settings.Global.AIRPLANE_MODE_ON))
            PlatformApi.BluetoothEnabled -> listOf(Settings.Global.getUriFor("bluetooth_on"))
            PlatformApi.DarkTheme -> listOf(Settings.Secure.getUriFor("ui_night_mode"))
            PlatformApi.BatterySaver -> listOf(Settings.Global.getUriFor("low_power"))
            PlatformApi.SwitchKeyboard ->
                listOf(Settings.Secure.getUriFor(Settings.Secure.DEFAULT_INPUT_METHOD))
            else -> emptyList()
        }
}
