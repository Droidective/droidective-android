package com.droidective.platform

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import com.droidective.core.Access
import com.droidective.core.Grants
import com.droidective.core.ShellProvider
import com.droidective.core.shell.Shell
import com.droidective.core.shell.ShellUnavailableException
import com.droidective.platform.shell.RootShell
import com.droidective.platform.shell.ShizukuShell
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import rikka.shizuku.Shizuku

/** Where Shizuku stands on this phone, in the order Setup walks someone through it. */
enum class ShizukuStatus {
    NotInstalled,
    NotRunning,
    NeedsPermission,
    Ready,
}

/**
 * Tracks the app's [Access] and hands out the matching privileged shell. Root wins when the user
 * has allowed it and su grants uid 0; Shizuku is next. While either is up, the app grants itself
 * the Granted level so those toggles keep working after a restart.
 */
class AccessMonitor(private val context: Context, private val scope: CoroutineScope) {
    private val shizuku = ShizukuShell(context.packageName)
    private val root = RootShell()
    private val prefs = context.getSharedPreferences("access", Context.MODE_PRIVATE)
    private val state = MutableStateFlow(Access.Nothing)

    val access: StateFlow<Access> = state.asStateFlow()

    /** Whether the user chose to use root. Probing su before that would show a root prompt. */
    var rootAllowed: Boolean
        get() = prefs.getBoolean(KEY_ROOT_ALLOWED, false)
        set(value) {
            prefs.edit().putBoolean(KEY_ROOT_ALLOWED, value).apply()
            scope.launch { refresh() }
        }

    private val onBinderReceived = Shizuku.OnBinderReceivedListener { scope.launch { refresh() } }
    private val onBinderDead = Shizuku.OnBinderDeadListener { scope.launch { refresh() } }
    private val onPermissionResult = Shizuku.OnRequestPermissionResultListener { _, _ ->
        scope.launch { refresh() }
    }

    fun start() {
        Shizuku.addBinderReceivedListenerSticky(onBinderReceived)
        Shizuku.addBinderDeadListener(onBinderDead)
        Shizuku.addRequestPermissionResultListener(onPermissionResult)
        scope.launch { refresh() }
    }

    /** The privileged shell for the current access, or null at App and Granted level. */
    fun shell(): Shell? =
        when (state.value.shell) {
            ShellProvider.Su -> root
            ShellProvider.Shizuku,
            ShellProvider.ShizukuRoot -> shizuku
            null -> null
        }

    fun shizukuStatus(): ShizukuStatus =
        when {
            !isInstalled(SHIZUKU_PACKAGE) -> ShizukuStatus.NotInstalled
            !Shizuku.pingBinder() -> ShizukuStatus.NotRunning
            Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED ->
                ShizukuStatus.NeedsPermission
            else -> ShizukuStatus.Ready
        }

    fun requestShizukuPermission() {
        if (Shizuku.pingBinder()) Shizuku.requestPermission(SHIZUKU_REQUEST_CODE)
    }

    suspend fun refresh() {
        val provider =
            when {
                rootAllowed && RootShell.isGranted() -> ShellProvider.Su
                shizukuStatus() == ShizukuStatus.Ready -> shizuku.provider
                else -> null
            }
        val granted = hasGrants() || (provider != null && grantSelf(provider))
        state.value = Access(granted = granted, shell = provider)
    }

    private suspend fun grantSelf(provider: ShellProvider): Boolean {
        val shell = if (provider == ShellProvider.Su) root else shizuku
        return try {
            Grants.commands(context.packageName).all { shell.run(it).succeeded } && hasGrants()
        } catch (_: ShellUnavailableException) {
            false
        }
    }

    private fun hasGrants(): Boolean =
        context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) ==
            PackageManager.PERMISSION_GRANTED

    private fun isInstalled(packageName: String): Boolean =
        try {
            context.packageManager.getPackageInfo(packageName, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }

    private companion object {
        const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
        const val SHIZUKU_REQUEST_CODE = 7001
        const val KEY_ROOT_ALLOWED = "root_allowed"
    }
}
