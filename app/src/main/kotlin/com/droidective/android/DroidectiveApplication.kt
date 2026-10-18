package com.droidective.android

import android.app.Application
import android.content.Context
import com.droidective.core.layout.LayoutRepository
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

    override fun onCreate() {
        super.onCreate()
        layouts = LayoutRepository(filesDir)
        access = AccessMonitor(this, scope).also { it.start() }
        toggles =
            ToggleRunner(
                settings = AndroidSettingsStore(this),
                platform = AndroidPlatformToggles(this),
                appShell = AppShell(),
                shell = { access.shell() },
                access = { access.access.value },
            )
    }
}

val Context.droidective: DroidectiveApplication
    get() = applicationContext as DroidectiveApplication
