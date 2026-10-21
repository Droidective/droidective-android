package com.droidective.android.surface

import android.content.Context
import android.provider.Settings
import com.droidective.android.droidective
import com.droidective.android.floating.FloatingControlsService
import com.droidective.android.performance.PerformanceOverlayService
import com.droidective.android.proxy.ProxyRun
import com.droidective.android.proxy.TrafficProxy
import com.droidective.android.screenrecord.RecordRequest
import com.droidective.android.screenrecord.ScreenRecorder
import com.droidective.android.uiinspect.UiCaptures
import com.droidective.core.AccessLevel
import com.droidective.core.Grants
import com.droidective.core.device.RecordOptions
import com.droidective.core.shell.ShellUnavailableException
import com.droidective.core.toggles.Outcome
import com.droidective.core.toggles.PlatformApi
import com.droidective.core.toggles.PlatformToggles

/**
 * The toggles that are this app's own features, answered here so a tile, a widget slot or an
 * automation can start a screen recording, show the performance overlay, capture the screen's views
 * or run the debugging proxy. Everything else goes on to the platform's toggles.
 */
class AppPlatformToggles(private val context: Context, private val platform: PlatformToggles) :
    PlatformToggles {
    override suspend fun read(api: PlatformApi): String? =
        when (api) {
            PlatformApi.ScreenRecording -> onOff(ScreenRecorder.recording)
            PlatformApi.PerformanceOverlay -> onOff(PerformanceOverlayService.isRunning.value)
            PlatformApi.CaptureViews -> null
            PlatformApi.FloatingControls -> onOff(FloatingControlsService.isRunning.value)
            PlatformApi.TrafficProxy ->
                onOff(
                    TrafficProxy.state.value.let { it !is ProxyRun.Off && it !is ProxyRun.Failed }
                )
            else -> platform.read(api)
        }

    override suspend fun write(api: PlatformApi, state: String): Outcome =
        when (api) {
            PlatformApi.ScreenRecording -> {
                if (state == ON) ScreenRecorder.start(context, QUICK_RECORDING)
                else ScreenRecorder.stop(context)
                Outcome.Applied(state, confirmed = false)
            }
            PlatformApi.PerformanceOverlay -> overlay(state == ON)
            PlatformApi.FloatingControls -> floatingControls(state == ON)
            PlatformApi.CaptureViews -> {
                UiCaptures.capture(context, CAPTURE_DELAY_SECONDS)
                Outcome.Applied(state, confirmed = false)
            }
            PlatformApi.TrafficProxy -> {
                // Pointed here when the app can change the setting, as the tool's Start does.
                val point = context.droidective.access.access.value.allows(AccessLevel.Granted)
                if (state == ON) TrafficProxy.start(context, point) else TrafficProxy.stop(context)
                Outcome.Applied(state, confirmed = false)
            }
            else -> platform.write(api, state)
        }

    private suspend fun overlay(on: Boolean): Outcome {
        if (!on) {
            PerformanceOverlayService.stop(context)
            return Outcome.Applied(OFF, confirmed = false)
        }
        if (!canDrawOverlays()) {
            return Outcome.Failed("Allow Display over other apps in the Performance Monitor first.")
        }
        PerformanceOverlayService.start(context)
        return Outcome.Applied(ON, confirmed = false)
    }

    private suspend fun floatingControls(on: Boolean): Outcome {
        if (!on) {
            FloatingControlsService.stop(context)
            return Outcome.Applied(OFF, confirmed = false)
        }
        if (!canDrawOverlays()) {
            return Outcome.Failed(
                "Allow Display over other apps for Droidective in Settings ▸ Apps ▸ Special access."
            )
        }
        FloatingControlsService.start(context)
        return Outcome.Applied(ON, confirmed = false)
    }

    /**
     * Display over other apps is an app-op the shell can allow; without one, it has to be asked.
     */
    private suspend fun canDrawOverlays(): Boolean {
        if (!Settings.canDrawOverlays(context)) {
            try {
                context.droidective.access.shell()?.run(Grants.overlayCommand(context.packageName))
            } catch (_: ShellUnavailableException) {
                // Reported by the caller, as the permission still missing.
            }
        }
        return Settings.canDrawOverlays(context)
    }

    private fun onOff(on: Boolean) = if (on) ON else OFF

    private companion object {
        const val ON = "on"
        const val OFF = "off"
        const val CAPTURE_DELAY_SECONDS = 3

        /**
         * What a tap from a tile or widget records with: the screen tool's defaults, three seconds
         * to close the shade, and a ten-minute limit so a forgotten recording stops.
         */
        val QUICK_RECORDING =
            RecordRequest(
                RecordOptions(limitSeconds = 600),
                showTaps = true,
                cleanStatusBar = false,
                delaySeconds = 3,
            )
    }
}
