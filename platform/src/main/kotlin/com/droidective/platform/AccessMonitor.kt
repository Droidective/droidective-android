package com.droidective.platform

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.RemoteException
import android.util.Log
import androidx.core.content.edit
import com.droidective.core.Access
import com.droidective.core.Grants
import com.droidective.core.ShellProvider
import com.droidective.core.shell.Shell
import com.droidective.core.shell.ShellUnavailableException
import com.droidective.platform.shell.RootShell
import com.droidective.platform.shell.ShizukuShell
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import rikka.shizuku.Shizuku

/** Where Shizuku stands on this phone, in the order Setup walks someone through it. */
enum class ShizukuStatus {
    NotInstalled,
    NotRunning,
    /**
     * Running, but not talking to this process yet. Shizuku's library hands out its binder a moment
     * before it attaches the app, and a call in that gap throws "Not an attached client". The
     * binder-received listener fires once the attach lands, and refreshes access.
     */
    Attaching,
    NeedsPermission,
    Ready,
}

/** The person's choice to use root, and what su said about it. */
enum class RootUse {
    Off,
    Asking,
    Granted,
    Refused,
}

/**
 * Tracks the app's [Access] and hands out the matching privileged shell. Root wins when the user
 * has allowed it and su grants uid 0; Shizuku is next. While either is up, the app grants itself
 * the Granted level so those toggles keep working after a restart.
 */
class AccessMonitor(private val context: Context, private val scope: CoroutineScope) {
    private val shizuku = ShizukuShell(context.packageName)
    private val rootShell = RootShell()
    private val prefs = context.getSharedPreferences("access", Context.MODE_PRIVATE)
    private val state = MutableStateFlow(Access.Nothing)
    private val firstRefresh = CompletableDeferred<Unit>()
    private val refreshing = Mutex()

    /**
     * su's answer in this process. Asking can show the root manager's prompt, so it's asked once.
     */
    @Volatile private var rootAnswer: Boolean? = null
    private val rootUse = MutableStateFlow(storedRootUse())

    /** Where the person's choice to use root stands, for Setup to show. */
    val root: StateFlow<RootUse> = rootUse.asStateFlow()

    val access: StateFlow<Access> = state.asStateFlow()

    /**
     * The access once the first check has finished. A widget drawn straight after a cold start
     * would otherwise see no access and send every Shell tap to its stand-in.
     */
    suspend fun settled(): Access {
        withTimeoutOrNull(SETTLE_TIMEOUT_MILLIS) { firstRefresh.await() }
        return state.value
    }

    /**
     * Whether the user chose to use root. Probing su before that would show a root prompt. Turning
     * it on asks su again, even after a refusal.
     */
    var rootAllowed: Boolean
        get() = prefs.getBoolean(KEY_ROOT_ALLOWED, false)
        set(value) {
            prefs.edit {
                putBoolean(KEY_ROOT_ALLOWED, value)
                remove(KEY_ROOT_REFUSED)
            }
            rootAnswer = null
            rootUse.value = if (value) RootUse.Asking else RootUse.Off
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
            ShellProvider.Su -> rootShell
            ShellProvider.Shizuku,
            ShellProvider.ShizukuRoot -> shizuku
            null -> null
        }

    fun shizukuStatus(): ShizukuStatus =
        when {
            !isInstalled(SHIZUKU_PACKAGE) -> ShizukuStatus.NotInstalled
            !Shizuku.pingBinder() -> ShizukuStatus.NotRunning
            else ->
                attached {
                    if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED)
                        ShizukuStatus.Ready
                    else ShizukuStatus.NeedsPermission
                } ?: ShizukuStatus.Attaching
        }

    fun requestShizukuPermission() {
        if (Shizuku.pingBinder()) attached { Shizuku.requestPermission(SHIZUKU_REQUEST_CODE) }
    }

    /**
     * [call] to Shizuku, or null when Shizuku refused it because it hasn't attached this process
     * yet, or because it died mid-call. Either way a listener refreshes access once it settles.
     */
    private inline fun <T> attached(call: () -> T): T? =
        try {
            call()
        } catch (e: IllegalStateException) {
            Log.i(LOG_TAG, "Shizuku isn't talking to this process yet: ${e.message}")
            null
        } catch (e: RuntimeException) {
            if (e.cause !is RemoteException) throw e
            Log.i(LOG_TAG, "Shizuku stopped mid-call: ${e.cause?.message}")
            null
        }

    /** One refresh at a time, so two callers can't put two root prompts on screen at once. */
    suspend fun refresh() = refreshing.withLock {
        val root = useRoot()
        val shizukuNow = if (root) null else shizukuStatus()
        val shizukuProvider =
            if (shizukuNow == ShizukuStatus.Ready) attached { shizuku.provider } else null
        val provider = if (root) ShellProvider.Su else shizukuProvider
        val attaching =
            shizukuNow == ShizukuStatus.Attaching ||
                (shizukuNow == ShizukuStatus.Ready && shizukuProvider == null)
        // With Shell, grant whatever's missing: a grant from an older Phone setup may lack the
        // crash-report permission even when the Granted level is there.
        if (provider != null && !hasAllGrants()) grantSelf(provider)
        val granted = hasGrants()
        state.value = Access(granted = granted, shell = provider)
        // A cold widget tap waits for access to settle; an attaching Shizuku settles on the
        // refresh its binder-received listener makes, not this one.
        if (!attaching) firstRefresh.complete(Unit)
    }

    /**
     * Whether to run as root. su isn't asked again after it said no, until Use root is turned off
     * and on: every resume refreshes access, and asking each time put a new prompt over the one
     * just answered.
     */
    private suspend fun useRoot(): Boolean {
        val stored = storedRootUse()
        if (stored == RootUse.Off || stored == RootUse.Refused) {
            rootUse.value = stored
            return false
        }
        val granted =
            rootAnswer
                ?: run {
                    rootUse.value = RootUse.Asking
                    RootShell.isGranted().also { rootAnswer = it }
                }
        if (!granted) prefs.edit { putBoolean(KEY_ROOT_REFUSED, true) }
        rootUse.value = if (granted) RootUse.Granted else RootUse.Refused
        return granted
    }

    private fun storedRootUse(): RootUse =
        when {
            !rootAllowed -> RootUse.Off
            prefs.getBoolean(KEY_ROOT_REFUSED, false) -> RootUse.Refused
            else -> RootUse.Asking
        }

    private suspend fun grantSelf(provider: ShellProvider) {
        val shell = if (provider == ShellProvider.Su) rootShell else shizuku
        try {
            // Each grant on its own: one Android refuses mustn't stop the rest.
            Grants.commands(context.packageName, Build.VERSION.SDK_INT).forEach { shell.run(it) }
        } catch (e: ShellUnavailableException) {
            Log.i(LOG_TAG, "Couldn't grant the app its permissions: ${e.message}")
        }
    }

    private fun hasAllGrants(): Boolean =
        Grants.permissions(Build.VERSION.SDK_INT).all {
            context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
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
        const val KEY_ROOT_REFUSED = "root_refused"
        const val SETTLE_TIMEOUT_MILLIS = 3_000L
        const val LOG_TAG = "DroidectiveAccess"
    }
}
