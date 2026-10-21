package com.droidective.platform

import android.app.NotificationManager
import android.app.UiModeManager
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import android.nfc.NfcAdapter
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.telephony.TelephonyManager
import androidx.core.content.edit
import com.droidective.core.toggles.DemoMode
import com.droidective.core.toggles.Keyboards
import com.droidective.core.toggles.Outcome
import com.droidective.core.toggles.PlatformApi
import com.droidective.core.toggles.PlatformToggles
import com.droidective.core.toggles.SpecialAccess
import kotlin.math.roundToInt

private const val ON = "on"
private const val OFF = "off"

private fun onOff(value: Boolean) = if (value) ON else OFF

/** The toggles Android exposes through APIs rather than settings keys or shell commands. */
class AndroidPlatformToggles(private val context: Context) : PlatformToggles {
    private val prefs = context.getSharedPreferences("platform-toggles", Context.MODE_PRIVATE)
    private val audio = context.getSystemService(AudioManager::class.java)
    private val notifications = context.getSystemService(NotificationManager::class.java)
    private val camera = context.getSystemService(CameraManager::class.java)
    @Volatile private var torchOn: Boolean? = null

    init {
        camera.registerTorchCallback(
            object : CameraManager.TorchCallback() {
                override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
                    if (cameraId == torchCameraId()) torchOn = enabled
                }
            },
            null,
        )
    }

    override suspend fun read(api: PlatformApi): String? =
        try {
            readUnchecked(api)
        } catch (_: SecurityException) {
            // An OEM can guard a read that AOSP leaves open; unknown beats a crash.
            null
        }

    private fun readUnchecked(api: PlatformApi): String? =
        when (api) {
            PlatformApi.WifiEnabled ->
                onOff(context.getSystemService(WifiManager::class.java).isWifiEnabled)
            PlatformApi.MobileData -> readMobileData()
            PlatformApi.AirplaneMode -> onOff(globalInt(Settings.Global.AIRPLANE_MODE_ON) == 1)
            PlatformApi.BluetoothEnabled -> onOff(globalInt("bluetooth_on") == 1)
            PlatformApi.NfcEnabled -> NfcAdapter.getDefaultAdapter(context)?.isEnabled?.let(::onOff)
            PlatformApi.AutoSync -> onOff(ContentResolver.getMasterSyncAutomatically())
            PlatformApi.MediaVolume -> readVolume()
            PlatformApi.RingerMode ->
                when (audio.ringerMode) {
                    AudioManager.RINGER_MODE_NORMAL -> "ring"
                    AudioManager.RINGER_MODE_VIBRATE -> "vibrate"
                    else -> "silent"
                }
            PlatformApi.DoNotDisturb ->
                onOff(
                    notifications.currentInterruptionFilter !=
                        NotificationManager.INTERRUPTION_FILTER_ALL
                )
            PlatformApi.KeepScreenOn -> onOff(prefs.contains(KEY_SAVED_TIMEOUT))
            PlatformApi.Flashlight -> torchOn?.let(::onOff)
            PlatformApi.DarkTheme -> readDarkTheme()
            PlatformApi.DataSaver ->
                onOff(
                    context
                        .getSystemService(ConnectivityManager::class.java)
                        .restrictBackgroundStatus !=
                        ConnectivityManager.RESTRICT_BACKGROUND_STATUS_DISABLED
                )
            PlatformApi.BatterySaver ->
                onOff(context.getSystemService(PowerManager::class.java).isPowerSaveMode)
            PlatformApi.DemoMode -> prefs.getString(KEY_DEMO, OFF)
            PlatformApi.SyncNow,
            PlatformApi.SwitchKeyboard,
            PlatformApi.BluetoothDiscoverable,
            PlatformApi.BrightnessSlider -> null
            // The app's own features: the app reads these before they get here.
            PlatformApi.ScreenRecording,
            PlatformApi.PerformanceOverlay,
            PlatformApi.CaptureViews,
            PlatformApi.TrafficProxy -> null
        }

    override suspend fun write(api: PlatformApi, state: String): Outcome =
        try {
            when (api) {
                PlatformApi.AutoSync ->
                    applied(state) { ContentResolver.setMasterSyncAutomatically(state == ON) }
                PlatformApi.SyncNow -> applied(state) { requestSyncEverything() }
                PlatformApi.MediaVolume -> applied(state) { writeVolume(state.toInt()) }
                PlatformApi.RingerMode -> writeRingerMode(state)
                PlatformApi.DoNotDisturb -> writeDoNotDisturb(state == ON)
                PlatformApi.KeepScreenOn -> writeKeepScreenOn(state == ON)
                PlatformApi.Flashlight ->
                    applied(state) { torchCameraId()?.let { camera.setTorchMode(it, state == ON) } }
                PlatformApi.DemoMode -> applied(state) { writeDemoMode(state == ON) }
                PlatformApi.SwitchKeyboard -> writeNextKeyboard()
                else ->
                    Outcome.Failed("${api.name} is read-only here; its toggle writes another way")
            }
        } catch (e: SecurityException) {
            Outcome.Failed("Android refused: ${e.message}")
        }

    private inline fun applied(state: String, block: () -> Unit): Outcome {
        block()
        return Outcome.Applied(state, confirmed = false)
    }

    private fun globalInt(key: String): Int =
        Settings.Global.getInt(context.contentResolver, key, 0)

    private fun readMobileData(): String? =
        try {
            onOff(context.getSystemService(TelephonyManager::class.java).isDataEnabled)
        } catch (_: SecurityException) {
            onOff(globalInt("mobile_data") == 1)
        }

    private fun readVolume(): String {
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val percent = audio.getStreamVolume(AudioManager.STREAM_MUSIC) * 100f / max
        return listOf(0, 33, 66, 100).minBy { kotlin.math.abs(it - percent) }.toString()
    }

    private fun writeVolume(percent: Int) {
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, (max * percent / 100f).roundToInt(), 0)
    }

    private fun writeRingerMode(state: String): Outcome {
        if (!notifications.isNotificationPolicyAccessGranted) {
            // Entering or leaving silent changes Do Not Disturb, which needs this access.
            return Outcome.NeedsSpecialAccess(SpecialAccess.DoNotDisturb)
        }
        audio.ringerMode =
            when (state) {
                "ring" -> AudioManager.RINGER_MODE_NORMAL
                "vibrate" -> AudioManager.RINGER_MODE_VIBRATE
                else -> AudioManager.RINGER_MODE_SILENT
            }
        return Outcome.Applied(state, confirmed = false)
    }

    private fun writeDoNotDisturb(on: Boolean): Outcome {
        if (!notifications.isNotificationPolicyAccessGranted) {
            return Outcome.NeedsSpecialAccess(SpecialAccess.DoNotDisturb)
        }
        notifications.setInterruptionFilter(
            if (on) NotificationManager.INTERRUPTION_FILTER_PRIORITY
            else NotificationManager.INTERRUPTION_FILTER_ALL
        )
        return Outcome.Applied(onOff(on), confirmed = false)
    }

    /** Raises the timeout to its maximum, keeping the user's own value to put back. */
    private fun writeKeepScreenOn(on: Boolean): Outcome {
        if (!Settings.System.canWrite(context)) {
            return Outcome.NeedsSpecialAccess(SpecialAccess.ModifySystemSettings)
        }
        val resolver = context.contentResolver
        val key = Settings.System.SCREEN_OFF_TIMEOUT
        if (on && !prefs.contains(KEY_SAVED_TIMEOUT)) {
            prefs.edit {
                putInt(KEY_SAVED_TIMEOUT, Settings.System.getInt(resolver, key, DEFAULT_TIMEOUT))
            }
            Settings.System.putInt(resolver, key, Int.MAX_VALUE)
        } else if (!on && prefs.contains(KEY_SAVED_TIMEOUT)) {
            Settings.System.putInt(resolver, key, prefs.getInt(KEY_SAVED_TIMEOUT, DEFAULT_TIMEOUT))
            prefs.edit { remove(KEY_SAVED_TIMEOUT) }
        }
        return Outcome.Applied(onOff(on), confirmed = false)
    }

    private fun readDarkTheme(): String =
        when (context.getSystemService(UiModeManager::class.java).nightMode) {
            UiModeManager.MODE_NIGHT_YES -> ON
            UiModeManager.MODE_NIGHT_NO -> OFF
            else ->
                onOff(
                    context.resources.configuration.uiMode and
                        android.content.res.Configuration.UI_MODE_NIGHT_MASK ==
                        android.content.res.Configuration.UI_MODE_NIGHT_YES
                )
        }

    /**
     * Needs WRITE_SECURE_SETTINGS for the setting and DUMP for SystemUI to accept the broadcasts.
     */
    private fun writeDemoMode(on: Boolean) {
        if (on) {
            Settings.Global.putInt(context.contentResolver, DemoMode.ALLOWED_SETTING, 1)
            DemoMode.enter.forEach(::sendDemo)
        } else {
            sendDemo(DemoMode.exit)
        }
        prefs.edit { putString(KEY_DEMO, onOff(on)) }
    }

    private fun sendDemo(extras: Map<String, String>) {
        val intent = Intent(DemoMode.ACTION)
        extras.forEach { (key, value) -> intent.putExtra(key, value) }
        context.sendBroadcast(intent)
    }

    private fun writeNextKeyboard(): Outcome {
        val resolver = context.contentResolver
        val next =
            Keyboards.next(
                Settings.Secure.getString(resolver, Settings.Secure.ENABLED_INPUT_METHODS),
                Settings.Secure.getString(resolver, Settings.Secure.DEFAULT_INPUT_METHOD),
            ) ?: return Outcome.Failed("Only one keyboard is turned on")
        Settings.Secure.putString(resolver, Settings.Secure.DEFAULT_INPUT_METHOD, next)
        return Outcome.Applied("run", confirmed = false)
    }

    private fun requestSyncEverything() {
        val extras =
            Bundle().apply {
                putBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, true)
                putBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED, true)
            }
        ContentResolver.requestSync(null, null, extras)
    }

    private fun torchCameraId(): String? =
        camera.cameraIdList.firstOrNull { id ->
            val traits = camera.getCameraCharacteristics(id)
            traits.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true &&
                traits.get(CameraCharacteristics.LENS_FACING) ==
                    CameraCharacteristics.LENS_FACING_BACK
        }

    private companion object {
        const val KEY_SAVED_TIMEOUT = "saved_screen_off_timeout"
        const val KEY_DEMO = "demo_mode"
        const val DEFAULT_TIMEOUT = 30_000
    }
}
