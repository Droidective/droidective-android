package com.droidective.android.location

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationManager
import android.location.provider.ProviderProperties
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.ServiceCompat
import com.droidective.android.MainActivity
import com.droidective.android.NoticeIds
import com.droidective.android.R
import com.droidective.android.surface.Launch
import com.droidective.core.location.Coordinates
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Stands in for the phone's location providers while Mock Location is on, giving each a fresh fix
 * at the chosen place every second: an app waiting on updates gets them, and one asking for the
 * last known location finds it current.
 */
class MockLocationService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var feeding: Job? = null
    private val standingIn = mutableListOf<String>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ServiceCompat.startForeground(
            this,
            NoticeIds.MOCK_LOCATION,
            notice(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            } else {
                0
            },
        )
        if (intent?.action == ACTION_STOP || intent == null) {
            // Stopped, or restarted by Android with nothing to say where: either way, off.
            stopStandingIn()
            stopSelf()
            return START_NOT_STICKY
        }
        val where =
            Coordinates(
                intent.getDoubleExtra(EXTRA_LATITUDE, 0.0),
                intent.getDoubleExtra(EXTRA_LONGITUDE, 0.0),
            )
        val name = intent.getStringExtra(EXTRA_NAME)
        if (!standIn()) {
            stopSelf()
            return START_NOT_STICKY
        }
        MockLocation.state_.value = MockLocation.State.On(where, name)
        post(notice())
        feeding?.cancel()
        feeding = scope.launch { feed(where) }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        stopStandingIn()
        super.onDestroy()
    }

    /** Puts a test provider in place of each real one; whether Android allowed it. */
    private fun standIn(): Boolean {
        val manager = getSystemService(LocationManager::class.java)
        try {
            for (provider in providers()) {
                if (provider in standingIn) continue
                // One left behind by a process Android ended would refuse to be added again.
                runCatching { manager.removeTestProvider(provider) }
                add(manager, provider)
                manager.setTestProviderEnabled(provider, true)
                standingIn += provider
            }
        } catch (_: SecurityException) {
            stopStandingIn()
            MockLocation.state_.value = MockLocation.State.Failed(NOT_CHOSEN, notChosen = true)
            return false
        } catch (e: IllegalArgumentException) {
            stopStandingIn()
            MockLocation.state_.value =
                MockLocation.State.Failed(e.message ?: "Android refused it", notChosen = false)
            return false
        }
        return true
    }

    private fun add(manager: LocationManager, provider: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val properties =
                ProviderProperties.Builder()
                    .setHasAltitudeSupport(true)
                    .setHasSpeedSupport(true)
                    .setHasBearingSupport(true)
                    .setPowerUsage(ProviderProperties.POWER_USAGE_LOW)
                    .setAccuracy(ProviderProperties.ACCURACY_FINE)
                    .build()
            manager.addTestProvider(provider, properties)
        } else {
            addOnAndroid11(manager, provider)
        }
    }

    /**
     * Android 11's only form; ProviderProperties came in 12. Its power and accuracy constants are
     * ints the compiler copies in, the same values as Android 11's own Criteria ones.
     */
    @SuppressLint("InlinedApi")
    @Suppress("DEPRECATION")
    private fun addOnAndroid11(manager: LocationManager, provider: String) {
        manager.addTestProvider(
            provider,
            false,
            false,
            false,
            false,
            true,
            true,
            true,
            ProviderProperties.POWER_USAGE_LOW,
            ProviderProperties.ACCURACY_FINE,
        )
    }

    private suspend fun feed(where: Coordinates) {
        val manager = getSystemService(LocationManager::class.java)
        while (scope.isActive) {
            for (provider in standingIn.toList()) {
                try {
                    manager.setTestProviderLocation(provider, fix(provider, where))
                } catch (_: SecurityException) {
                    // Developer options picked another app since; it is that app's place now.
                    MockLocation.state_.value =
                        MockLocation.State.Failed(NOT_CHOSEN, notChosen = true)
                    stopSelf()
                    return
                } catch (_: IllegalArgumentException) {
                    // A provider taken away under it; the rest carry on.
                    standingIn -= provider
                }
            }
            delay(EVERY_MILLIS)
        }
    }

    private fun stopStandingIn() {
        val manager = getSystemService(LocationManager::class.java)
        for (provider in standingIn) {
            try {
                manager.removeTestProvider(provider)
            } catch (_: RuntimeException) {
                // Gone already, or no longer ours to remove: either way the real one is back.
            }
        }
        standingIn.clear()
        if (MockLocation.state_.value is MockLocation.State.On) {
            MockLocation.state_.value = MockLocation.State.Off
        }
    }

    private fun providers(): List<String> = buildList {
        add(LocationManager.GPS_PROVIDER)
        add(LocationManager.NETWORK_PROVIDER)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(LocationManager.FUSED_PROVIDER)
    }

    private fun notice(): Notification {
        val now = MockLocation.state.value as? MockLocation.State.On
        val text =
            now?.let { listOfNotNull(it.name, it.where.toString()).joinToString(" · ") }
                ?: "Starting"
        return builder()
            .setContentTitle("The phone says it is somewhere else")
            .setContentText(text)
            .setOngoing(true)
            .addAction(
                Notification.Action.Builder(
                        null,
                        "Stop",
                        PendingIntent.getService(
                            this,
                            0,
                            Intent(this, MockLocationService::class.java).setAction(ACTION_STOP),
                            PendingIntent.FLAG_IMMUTABLE,
                        ),
                    )
                    .build()
            )
            .build()
    }

    private fun builder(): Notification.Builder {
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(
                NotificationChannel(CHANNEL, "Mock Location", NotificationManager.IMPORTANCE_LOW)
                    .apply { description = "Mock Location, while the phone is somewhere else" }
            )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.sym_location_on)
            .setContentIntent(
                PendingIntent.getActivity(
                    this,
                    NoticeIds.MOCK_LOCATION,
                    Intent(this, MainActivity::class.java)
                        .putExtra(Launch.EXTRA_DESTINATION, Launch.DESTINATION_TOOL)
                        .putExtra(Launch.EXTRA_TOOL, MockLocation.TOOL),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
            )
            .setOnlyAlertOnce(true)
    }

    private fun post(notification: Notification) {
        try {
            getSystemService(NotificationManager::class.java)
                .notify(NoticeIds.MOCK_LOCATION, notification)
        } catch (_: SecurityException) {
            // Notifications are off; the screen shows the same.
        }
    }

    companion object {
        internal const val ACTION_STOP = "com.droidective.android.action.STOP_MOCK_LOCATION"
        internal const val EXTRA_LATITUDE = "com.droidective.android.extra.LATITUDE"
        internal const val EXTRA_LONGITUDE = "com.droidective.android.extra.LONGITUDE"
        internal const val EXTRA_NAME = "com.droidective.android.extra.PLACE_NAME"
        private const val CHANNEL = "mock-location"
        private const val EVERY_MILLIS = 1_000L
        private const val ACCURACY_METRES = 3f
        private const val NOT_CHOSEN =
            "Droidective isn't the mock location app. Android lets only that app stand in for " +
                "the phone's location."

        /** A fix as a provider would give it: now, here, still, and accurate to a few metres. */
        private fun fix(provider: String, where: Coordinates): Location =
            Location(provider).apply {
                latitude = where.latitude
                longitude = where.longitude
                altitude = 0.0
                accuracy = ACCURACY_METRES
                speed = 0f
                bearing = 0f
                time = System.currentTimeMillis()
                elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
                verticalAccuracyMeters = ACCURACY_METRES
                speedAccuracyMetersPerSecond = 0f
                bearingAccuracyDegrees = 0f
            }
    }
}
