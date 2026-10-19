package com.droidective.android.automation

import android.app.ForegroundServiceStartNotAllowedException
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.database.ContentObserver
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.droidective.android.MainActivity
import com.droidective.android.R
import com.droidective.android.crashes.CrashAlerts
import com.droidective.android.droidective
import com.droidective.android.surface.LiveSurfaces
import com.droidective.android.surface.uri
import com.droidective.core.automations.Event
import com.droidective.core.automations.Matcher
import com.droidective.core.automations.State
import com.droidective.core.automations.Trigger
import com.droidective.core.automations.Wake
import com.droidective.core.layout.SurfaceWatch
import com.droidective.core.toggles.Probe
import com.droidective.core.toggles.Toggles
import com.droidective.platform.CrashLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The automation service: runs only while an enabled automation watches something Android won't
 * start the app for (an app opening, charging, the screen, the battery level, headphones, Wi-Fi, a
 * toggle changing, an install, an unlock), while Crash Catcher watches an app, or while placed
 * widgets, tiles and notification bars are kept live ([LiveSurfaces]). Its notification names what
 * it's doing.
 */
class AutomationService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val main = Handler(Looper.getMainLooper())
    private var listening: Listening? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val watched = watchedHere(this)
        if (watched.isEmpty()) {
            stopSelf()
            return START_NOT_STICKY
        }
        // Every startForegroundService needs a startForeground, even for a service already up.
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification(watched),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            } else {
                0
            },
        )
        // Surfaces ask again after every tap; listening that's already right stays up.
        if (listening?.watched != watched) {
            listening?.stop()
            listening = Listening(watched).also { it.start() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        listening?.stop()
        scope.cancel()
        super.onDestroy()
    }

    /** What the enabled automations, Crash Catcher's alerts and live surfaces need this for. */
    private data class Watched(
        val states: Set<State>,
        val events: Set<Event>,
        val crashAlerts: Set<String>,
        val surfaces: SurfaceWatch?,
    ) {
        fun isEmpty() =
            states.isEmpty() && events.isEmpty() && crashAlerts.isEmpty() && surfaces == null
    }

    /** The receivers, callbacks and polls for one set of watched things; stopped as a whole. */
    private inner class Listening(val watched: Watched) {
        private val receiver =
            object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) = onBroadcast(intent)
            }
        private val packages =
            object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) = onPackage(intent)
            }
        private val network =
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = check()

                override fun onLost(network: Network) = check()
            }
        private val settings =
            object : ContentObserver(main) {
                override fun onChange(selfChange: Boolean) = check()
            }
        private val audio =
            object : AudioDeviceCallback() {
                override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) = check()

                override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) = check()
            }
        private var polls: List<Job> = emptyList()
        private var stopCrashes: (() -> Unit)? = null
        private val surfaces = watched.surfaces?.let { SurfaceListener(this@AutomationService, it) }

        fun start() {
            val service = this@AutomationService
            ContextCompat.registerReceiver(
                service,
                receiver,
                IntentFilter().apply {
                    addAction(Intent.ACTION_POWER_CONNECTED)
                    addAction(Intent.ACTION_POWER_DISCONNECTED)
                    addAction(Intent.ACTION_SCREEN_ON)
                    addAction(Intent.ACTION_SCREEN_OFF)
                    addAction(Intent.ACTION_USER_PRESENT)
                    addAction(Intent.ACTION_BATTERY_CHANGED)
                },
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            if (watched.events.any { it is Event.AppInstalled }) {
                ContextCompat.registerReceiver(
                    service,
                    packages,
                    IntentFilter().apply {
                        addAction(Intent.ACTION_PACKAGE_ADDED)
                        addAction(Intent.ACTION_PACKAGE_REPLACED)
                        addDataScheme("package")
                    },
                    ContextCompat.RECEIVER_NOT_EXPORTED,
                )
            }
            if (watched.states.any { it is State.OnWifi }) {
                val request =
                    NetworkRequest.Builder()
                        .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                        .build()
                service
                    .getSystemService(ConnectivityManager::class.java)
                    .registerNetworkCallback(request, network)
            }
            if (State.Headphones in watched.states) {
                service
                    .getSystemService(AudioManager::class.java)
                    .registerAudioDeviceCallback(audio, main)
            }
            if (watched.crashAlerts.isNotEmpty() || watched.events.any { it is Event.AppCrashed }) {
                stopCrashes =
                    CrashLog(service).listen { report ->
                        CrashAlerts.onCrash(service, report)
                        Automations.onEvent(service, Event.AppCrashed(report.packageName))
                    }
            }
            observeSettings()
            polls = listOfNotNull(pollApps(), pollToggles())
            surfaces?.start()
            check()
        }

        fun stop() {
            val service = this@AutomationService
            runCatching { service.unregisterReceiver(receiver) }
            runCatching { service.unregisterReceiver(packages) }
            runCatching {
                service
                    .getSystemService(ConnectivityManager::class.java)
                    .unregisterNetworkCallback(network)
            }
            service.getSystemService(AudioManager::class.java).unregisterAudioDeviceCallback(audio)
            service.contentResolver.unregisterContentObserver(settings)
            polls.forEach { it.cancel() }
            stopCrashes?.invoke()
            surfaces?.stop()
        }

        private fun onBroadcast(intent: Intent) {
            if (intent.action == Intent.ACTION_USER_PRESENT && Event.Unlocked in watched.events) {
                Automations.onEvent(this@AutomationService, Event.Unlocked)
            }
            check()
        }

        private fun onPackage(intent: Intent) {
            // An update arrives as ADDED with EXTRA_REPLACING, then REPLACED: count it once.
            val replacingAdd =
                intent.action == Intent.ACTION_PACKAGE_ADDED &&
                    intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)
            val packageName = intent.data?.schemeSpecificPart ?: return
            if (!replacingAdd) {
                Automations.onEvent(this@AutomationService, Event.AppInstalled(packageName))
            }
        }

        /** Toggles read from a setting change it when the setting does; observe those keys. */
        private fun observeSettings() {
            val resolver = this@AutomationService.contentResolver
            watched.states
                .filterIsInstance<State.ToggleIs>()
                .mapNotNull { Toggles.byId(it.toggleId)?.read as? Probe.Setting }
                .forEach { probe -> resolver.registerContentObserver(probe.uri(), false, settings) }
        }

        /**
         * The app in front changes with no broadcast; look every two seconds while the screen's on.
         */
        private fun pollApps(): Job? {
            val apps = watched.states.filterIsInstance<State.AppOpen>().toSet()
            if (apps.isEmpty()) return null
            val power = getSystemService(PowerManager::class.java)
            return scope.launch {
                while (isActive) {
                    if (power.isInteractive) StateSignals.check(this@AutomationService, only = apps)
                    delay(APP_POLL_MILLIS)
                }
            }
        }

        /** Toggles read through a command or an API announce nothing; look every half minute. */
        private fun pollToggles(): Job? {
            val toggles =
                watched.states
                    .filterIsInstance<State.ToggleIs>()
                    .filter { Toggles.byId(it.toggleId)?.read !is Probe.Setting }
                    .toSet<State>()
            if (toggles.isEmpty()) return null
            return scope.launch {
                while (isActive) {
                    delay(TOGGLE_POLL_MILLIS)
                    StateSignals.check(this@AutomationService, only = toggles)
                }
            }
        }

        private fun check() {
            scope.launch { StateSignals.check(this@AutomationService) }
        }
    }

    private fun notification(watched: Watched): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Automation service", NotificationManager.IMPORTANCE_MIN)
                .apply { description = "Shown while automations watch the phone" }
        )
        val names =
            watched.states.map { it.describe(holds = true) } +
                watched.events.map { it.describe() } +
                watched.crashAlerts.map { "When ${CrashAlerts.label(this, it)} crashes" } +
                listOfNotNull(watched.surfaces?.let { "Widgets and tiles answer at once" })
        val open =
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE,
            )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.sym_bolt)
            .setContentTitle(
                when {
                    watched.states.isNotEmpty() || watched.events.isNotEmpty() ->
                        "Automations are watching"
                    watched.crashAlerts.isNotEmpty() -> "Crash Catcher is watching"
                    else -> "Keeping widgets live"
                }
            )
            .setContentText(names.joinToString(", "))
            .setStyle(Notification.BigTextStyle().bigText(names.joinToString("\n")))
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL = "automation-service"
        private const val NOTIFICATION_ID = 7_302
        private const val APP_POLL_MILLIS = 2_000L
        private const val TOGGLE_POLL_MILLIS = 30_000L

        private fun watchedHere(context: Context): Watched {
            val automations = context.droidective.automations.all()
            val states = Matcher.watched(automations).filter { it.wakes == Wake.Service }.toSet()
            val events =
                automations
                    .filter { it.enabled }
                    .mapNotNull { (it.trigger as? Trigger.On)?.event }
                    .filter { it.wakes == Wake.Service }
                    .toSet()
            return Watched(
                states,
                events,
                CrashAlerts.watched(context),
                LiveSurfaces.watched(context),
            )
        }

        /**
         * Starts the service when an enabled automation, a crash alert or a live surface needs it,
         * and stops it when nothing does. From the background Android 12+ may refuse the start; the
         * boot and update broadcasts are allowed to, and opening the app starts it again.
         */
        fun sync(context: Context) {
            val intent = Intent(context, AutomationService::class.java)
            val needed =
                Matcher.needsService(context.droidective.automations.all()) ||
                    CrashAlerts.watched(context).isNotEmpty() ||
                    LiveSurfaces.watched(context) != null
            if (!needed) {
                context.stopService(intent)
                return
            }
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (e: IllegalStateException) {
                // Android 12+ refuses a background start; opening the app starts it again.
                if (
                    Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                        e !is ForegroundServiceStartNotAllowedException
                ) {
                    throw e
                }
            }
        }
    }
}
