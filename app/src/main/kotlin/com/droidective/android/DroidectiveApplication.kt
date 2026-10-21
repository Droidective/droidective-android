package com.droidective.android

import android.app.Application
import android.content.Context
import androidx.core.content.edit
import com.droidective.android.automation.Automations
import com.droidective.android.crashes.OwnCrashes
import com.droidective.android.proxy.ProxyNotices
import com.droidective.android.proxy.TrafficProxy
import com.droidective.android.surface.AppPlatformToggles
import com.droidective.android.update.Updates
import com.droidective.core.apps.SavedIntents
import com.droidective.core.automations.AutomationLedger
import com.droidective.core.automations.AutomationRepository
import com.droidective.core.commands.CommandRepository
import com.droidective.core.commands.DeepLinkRepository
import com.droidective.core.device.ValueChanges
import com.droidective.core.layout.LayoutRepository
import com.droidective.core.toggles.Param
import com.droidective.core.toggles.ToggleRunner
import com.droidective.platform.AccessMonitor
import com.droidective.platform.AndroidPlatformToggles
import com.droidective.platform.AndroidSettingsStore
import com.droidective.platform.shell.AppShell
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Holds the app-wide objects every surface shares: the app, widgets, tiles and automations. */
class DroidectiveApplication : Application() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Droidective's own crashes, recorded before Android ends the process. */
    lateinit var ownCrashes: OwnCrashes
        private set

    lateinit var access: AccessMonitor
        private set

    lateinit var toggles: ToggleRunner
        private set

    lateinit var layouts: LayoutRepository
        private set

    lateinit var commands: CommandRepository
        private set

    lateinit var deepLinks: DeepLinkRepository
        private set

    lateinit var automations: AutomationRepository
        private set

    /** The intents the Intent Builder saved. */
    lateinit var savedIntents: SavedIntents
        private set

    /** What the Settings Editor changed, to undo. */
    lateinit var valueChanges: ValueChanges
        private set

    /** The toggles running Whiles hold, and History. */
    lateinit var ledger: AutomationLedger
        private set

    private val params by lazy { getSharedPreferences("params", Context.MODE_PRIVATE) }

    /** A value a parameterised toggle reuses, such as the proxy's host and port. */
    fun param(param: Param): String? = params.getString(param.key, null)

    fun setParam(param: Param, value: String?) {
        params.edit { if (value == null) remove(param.key) else putString(param.key, value) }
    }

    override fun onCreate() {
        super.onCreate()
        // First, so a crash anywhere after this is on record.
        ownCrashes =
            OwnCrashes(File(filesDir, "own-crashes")).also {
                it.install(Thread.getDefaultUncaughtExceptionHandler())
            }
        layouts = LayoutRepository(filesDir)
        commands = CommandRepository(filesDir)
        deepLinks = DeepLinkRepository(filesDir)
        automations = AutomationRepository(filesDir)
        ledger = AutomationLedger(filesDir)
        valueChanges = ValueChanges(filesDir)
        savedIntents = SavedIntents(filesDir)
        access = AccessMonitor(this, scope).also { it.start() }
        // A force stop clears the app's alarms; starting again puts the schedules back.
        scope.launch { Automations.reschedule(this@DroidectiveApplication) }
        scope.launch { Updates.schedule(this@DroidectiveApplication) }
        scope.launch {
            // A service Android is restarting gets going first: it may still be listening.
            delay(STRANDED_GRACE_MILLIS)
            access.settled()
            if (TrafficProxy.releaseIfStranded(this@DroidectiveApplication)) {
                ProxyNotices.released(this@DroidectiveApplication)
            }
        }
        toggles =
            ToggleRunner(
                settings = AndroidSettingsStore(this),
                platform = AppPlatformToggles(this, AndroidPlatformToggles(this)),
                appShell = AppShell(),
                shell = { access.shell() },
                access = { access.access.value },
                params = ::param,
            )
    }
}

val Context.droidective: DroidectiveApplication
    get() = applicationContext as DroidectiveApplication

/** How long a freshly started process leaves a restarting proxy before calling it stranded. */
private const val STRANDED_GRACE_MILLIS = 5_000L
