package com.droidective.platform.shell

import android.content.ComponentName
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.os.RemoteException
import com.droidective.core.ShellProvider
import com.droidective.core.shell.Shell
import com.droidective.core.shell.ShellCommand
import com.droidective.core.shell.ShellResult
import com.droidective.core.shell.ShellUnavailableException
import com.droidective.platform.BuildConfig
import com.droidective.platform.IShellService
import kotlin.system.exitProcess
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import rikka.shizuku.Shizuku

private const val COMMAND_TIMEOUT_MILLIS = 30_000L
private const val BIND_TIMEOUT_MILLIS = 5_000L

/** Runs commands as the app itself. For reads that need no privilege, such as `getprop`. */
class AppShell : Shell {
    override suspend fun run(command: ShellCommand): ShellResult =
        withContext(Dispatchers.IO) {
            ProcessExec.run(listOf("sh", "-c", command.line), COMMAND_TIMEOUT_MILLIS)
        }
}

/** Runs commands through `su -c`, for rooted phones. Magisk and KernelSU remember the grant. */
class RootShell : Shell {
    override suspend fun run(command: ShellCommand): ShellResult =
        withContext(Dispatchers.IO) {
            try {
                ProcessExec.run(listOf("su", "-c", command.line), COMMAND_TIMEOUT_MILLIS)
            } catch (e: java.io.IOException) {
                throw ShellUnavailableException("su isn’t available: ${e.message}", e)
            }
        }

    companion object {
        /** Whether su grants uid 0. On a rooted phone the first call shows the root prompt. */
        suspend fun isGranted(): Boolean =
            try {
                RootShell().run(ShellCommand.of("id", "-u")).let {
                    it.succeeded && it.stdout.trim() == "0"
                }
            } catch (_: ShellUnavailableException) {
                false
            }
    }
}

/**
 * Runs commands in a [ShellUserService] that Shizuku starts as the shell user, or as root when
 * Shizuku itself runs as root. The service is bound on first use and rebound if it dies.
 */
class ShizukuShell(packageName: String) : Shell {
    /** Which kind of Shizuku is running: started from root, or from adb as the shell user. */
    val provider: ShellProvider
        get() = if (Shizuku.getUid() == 0) ShellProvider.ShizukuRoot else ShellProvider.Shizuku

    private val args =
        Shizuku.UserServiceArgs(ComponentName(packageName, ShellUserService::class.java.name))
            .daemon(false)
            .processNameSuffix("shell")
            .debuggable(BuildConfig.DEBUG)
            .version(SERVICE_VERSION)

    private val bindLock = Mutex()
    @Volatile private var service: IShellService? = null

    override suspend fun run(command: ShellCommand): ShellResult {
        val bound = bound()
        return withContext(Dispatchers.IO) {
            try {
                bound.exec(command.line, COMMAND_TIMEOUT_MILLIS).toResult()
            } catch (e: RemoteException) {
                service = null
                throw ShellUnavailableException("Shizuku stopped responding", e)
            }
        }
    }

    private suspend fun bound(): IShellService = bindLock.withLock {
        service
            ?.takeIf { it.asBinder().isBinderAlive }
            ?.let {
                return it
            }
        if (!Shizuku.pingBinder()) throw ShellUnavailableException("Shizuku isn’t running")
        val connected = CompletableDeferred<IShellService>()
        val connection =
            object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                    connected.complete(IShellService.Stub.asInterface(binder))
                }

                override fun onServiceDisconnected(name: ComponentName) {
                    service = null
                }
            }
        try {
            Shizuku.bindUserService(args, connection)
            withTimeout(BIND_TIMEOUT_MILLIS) { connected.await() }.also { service = it }
        } catch (e: TimeoutCancellationException) {
            throw ShellUnavailableException("Shizuku didn’t start the shell service", e)
        } catch (e: RuntimeException) {
            throw ShellUnavailableException("Shizuku refused to start the shell service", e)
        }
    }

    private fun Bundle.toResult() =
        ShellResult(
            exitCode = getInt(KEY_EXIT),
            stdout = getString(KEY_STDOUT).orEmpty(),
            stderr = getString(KEY_STDERR).orEmpty(),
        )

    private companion object {
        // Bump when ShellUserService changes, so Shizuku replaces a running old copy.
        const val SERVICE_VERSION = 1
    }
}

internal const val KEY_EXIT = "exit"
internal const val KEY_STDOUT = "stdout"
internal const val KEY_STDERR = "stderr"

/**
 * The service Shizuku runs outside the app, as the shell user. It can't use app resources or the
 * app's Context; it only runs commands.
 */
class ShellUserService : IShellService.Stub() {
    override fun destroy() {
        exitProcess(0)
    }

    override fun exec(command: String, timeoutMillis: Long): Bundle {
        val result = ProcessExec.run(listOf("sh", "-c", command), timeoutMillis)
        return Bundle().apply {
            putInt(KEY_EXIT, result.exitCode)
            putString(KEY_STDOUT, result.stdout)
            putString(KEY_STDERR, result.stderr)
        }
    }
}
