package com.droidective.android

import android.app.Application
import android.content.Context
import androidx.core.content.edit
import com.droidective.core.commands.CommandRepository
import com.droidective.core.commands.DeepLinkRepository
import com.droidective.core.layout.LayoutRepository
import com.droidective.core.toggles.Param
import com.droidective.core.toggles.ToggleRunner
import com.droidective.platform.AccessMonitor
import com.droidective.platform.AndroidPlatformToggles
import com.droidective.platform.AndroidSettingsStore
import com.droidective.platform.shell.AppShell
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Holds the app-wide objects every surface shares: the app, widgets, tiles and automations. */
class DroidectiveApplication : Application() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

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

    private val params by lazy { getSharedPreferences("params", Context.MODE_PRIVATE) }

    /** A value a parameterised toggle reuses, such as the proxy's host and port. */
    fun param(param: Param): String? = params.getString(param.key, null)

    fun setParam(param: Param, value: String?) {
        params.edit { if (value == null) remove(param.key) else putString(param.key, value) }
    }

    override fun onCreate() {
        super.onCreate()
        layouts = LayoutRepository(filesDir)
        commands = CommandRepository(filesDir)
        deepLinks = DeepLinkRepository(filesDir)
        access = AccessMonitor(this, scope).also { it.start() }
        toggles =
            ToggleRunner(
                settings = AndroidSettingsStore(this),
                platform = AndroidPlatformToggles(this),
                appShell = AppShell(),
                shell = { access.shell() },
                access = { access.access.value },
                params = ::param,
            )
    }
}

val Context.droidective: DroidectiveApplication
    get() = applicationContext as DroidectiveApplication
