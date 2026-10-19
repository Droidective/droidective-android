package com.droidective.android.automation

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.hardware.camera2.CameraManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import androidx.core.content.ContextCompat
import com.droidective.android.surface.LiveSurfaces
import com.droidective.android.surface.Surfaces
import com.droidective.core.layout.SurfaceWatch
import com.droidective.core.toggles.Toggles
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Redraws placed surfaces when what they show changes outside the app: settings through a content
 * observer, the broadcasts Android sends a running receiver, the torch callback, and a look every
 * half minute for what announces nothing. A change settles for a moment first, since one switch can
 * write several settings.
 */
internal class SurfaceListener(private val service: Service, private val watch: SurfaceWatch) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val main = Handler(Looper.getMainLooper())
    private val toggles = watch.toggleIds.mapNotNull { Toggles.byId(it) }
    private var pending: Job? = null
    private var torchFollowed = false

    private val observer =
        object : ContentObserver(main) {
            override fun onChange(selfChange: Boolean) = changed()
        }
    private val receiver =
        object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) = changed()
        }
    private val torch =
        object : CameraManager.TorchCallback() {
            override fun onTorchModeChanged(cameraId: String, enabled: Boolean) = changed()
        }

    fun start() {
        val resolver = service.contentResolver
        toggles.flatMap(LiveSurfaces::settingUris).toSet().forEach {
            resolver.registerContentObserver(it, false, observer)
        }
        // The screen coming on catches whatever changed while nobody could see it.
        val actions =
            toggles.flatMap(LiveSurfaces::broadcasts).toSet() +
                setOf(Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT)
        ContextCompat.registerReceiver(
            service,
            receiver,
            IntentFilter().apply { actions.forEach(::addAction) },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        if (toggles.any(LiveSurfaces::followsTorch)) {
            service.getSystemService(CameraManager::class.java).registerTorchCallback(torch, main)
            torchFollowed = true
        }
        if (toggles.any(LiveSurfaces::needsPolling) || watch.commandIds.isNotEmpty()) poll()
    }

    fun stop() {
        service.contentResolver.unregisterContentObserver(observer)
        runCatching { service.unregisterReceiver(receiver) }
        if (torchFollowed) {
            service.getSystemService(CameraManager::class.java).unregisterTorchCallback(torch)
        }
        scope.cancel()
    }

    private fun poll() {
        val power = service.getSystemService(PowerManager::class.java)
        scope.launch {
            while (isActive) {
                delay(POLL_MILLIS)
                if (power.isInteractive) Surfaces.redraw(service)
            }
        }
    }

    private fun changed() {
        pending?.cancel()
        pending = scope.launch {
            delay(SETTLE_MILLIS)
            Surfaces.redraw(service)
        }
    }

    private companion object {
        const val SETTLE_MILLIS = 300L
        const val POLL_MILLIS = 30_000L
    }
}
