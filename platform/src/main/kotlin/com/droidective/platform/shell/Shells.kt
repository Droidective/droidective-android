package com.droidective.platform.shell

import android.content.ComponentName
import android.content.ServiceConnection
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.RemoteException
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import com.droidective.core.ShellProvider
import com.droidective.core.shell.ResultFrame
import com.droidective.core.shell.Shell
import com.droidective.core.shell.ShellCommand
import com.droidective.core.shell.ShellResult
import com.droidective.core.shell.ShellSession
import com.droidective.core.shell.ShellUnavailableException
import com.droidective.platform.BuildConfig
import com.droidective.platform.IShellService
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.system.exitProcess
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import rikka.shizuku.Shizuku

private const val COMMAND_TIMEOUT_MILLIS = 30_000L
private const val BIND_TIMEOUT_MILLIS = 10_000L

/** Runs commands as the app itself. For reads that need no privilege, such as `getprop`. */
class AppShell : Shell {
    override suspend fun run(command: ShellCommand): ShellResult =
        withContext(Dispatchers.IO) {
            ProcessExec.run(listOf("sh", "-c", command.line), COMMAND_TIMEOUT_MILLIS)
        }

    override fun stream(command: ShellCommand): Flow<String> =
        LineStream.process(streamArgv(command.line)).flowOn(Dispatchers.IO)
}

/**
 * Runs commands as root, all through one `su` session that starts with the first command: Magisk
 * and KernelSU ask, and show their toast, once per `su`, and a session per command showed one every
 * few seconds while the proxy or an automation ran in the background. A long-running [stream] still
 * starts its own.
 *
 * @param onLost called when `su` stops answering, refused or gone, so access can be asked again.
 */
class RootShell(private val onLost: () -> Unit = {}) : Shell {
    private val session = ShellSession(listOf("su"), COMMAND_TIMEOUT_MILLIS)

    override suspend fun run(command: ShellCommand): ShellResult =
        withContext(Dispatchers.IO) {
            try {
                session.run(command)
            } catch (e: ShellUnavailableException) {
                onLost()
                throw e
            }
        }

    override fun stream(command: ShellCommand): Flow<String> =
        LineStream.process(streamArgv(command.line, shell = "su"))
            .catch { e ->
                if (e is java.io.IOException) {
                    throw ShellUnavailableException("su isn’t available: ${e.message}", e)
                }
                throw e
            }
            .flowOn(Dispatchers.IO)

    /**
     * Whether su grants uid 0, asked in this shell's session so the answer and the commands after
     * it share one grant. On a rooted phone the first ask shows the root prompt.
     */
    suspend fun isGranted(): Boolean =
        withContext(Dispatchers.IO) {
            try {
                session.run(ShellCommand.of("id", "-u")).let {
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
 *
 * It's a daemon: it outlives the app's process. Starting it means a new app_process JVM; as a
 * daemon that happens once per boot, so a widget tap on a cold app only rebinds. Measured on an API
 * 36 emulator: 327 to 504 ms from the tap to Wi-Fi changing.
 */
class ShizukuShell(packageName: String) : Shell {
    /** Which kind of Shizuku is running: started from root, or from adb as the shell user. */
    val provider: ShellProvider
        get() = if (Shizuku.getUid() == 0) ShellProvider.ShizukuRoot else ShellProvider.Shizuku

    private val args =
        Shizuku.UserServiceArgs(ComponentName(packageName, ShellUserService::class.java.name))
            .daemon(true)
            .processNameSuffix("shell")
            .debuggable(BuildConfig.DEBUG)
            .version(SERVICE_VERSION)

    private val bindLock = Mutex()
    @Volatile private var service: IShellService? = null

    override suspend fun run(command: ShellCommand): ShellResult {
        val bound = bound()
        return withContext(Dispatchers.IO) {
            try {
                val pipe = bound.exec(command.line, COMMAND_TIMEOUT_MILLIS)
                ParcelFileDescriptor.AutoCloseInputStream(pipe).use(ResultFrame::read)
            } catch (e: RemoteException) {
                service = null
                throw ShellUnavailableException("Shizuku stopped responding", e)
            } catch (e: IOException) {
                throw ShellUnavailableException("The shell service stopped mid-command", e)
            }
        }
    }

    /**
     * The service runs the command and hands back the read end of a pipe. Closing it, which a
     * stopped collector does, breaks the pipe, and the service ends the command.
     */
    override fun stream(command: ShellCommand): Flow<String> =
        LineStream.lines {
                val bound = bound()
                val pipe =
                    try {
                        bound.stream(command.line)
                    } catch (e: RemoteException) {
                        service = null
                        throw ShellUnavailableException("Shizuku stopped responding", e)
                    }
                LineStream.Source(ParcelFileDescriptor.AutoCloseInputStream(pipe)) {}
            }
            .flowOn(Dispatchers.IO)

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

    private companion object {
        // Bump when ShellUserService changes, so Shizuku replaces a running old copy.
        const val SERVICE_VERSION = 4
    }
}

/**
 * `exec`, so the command replaces the shell: stopping the process stops the command itself rather
 * than orphaning it under a killed `sh`. A stream is one long-running command, such as logcat.
 */
internal fun streamArgv(command: String, shell: String = "sh"): List<String> =
    listOf(shell, "-c", "exec $command")

private const val WATCH_MILLIS = 1_000
private const val STOP_GRACE_SECONDS = 8L

/**
 * The service Shizuku runs outside the app, as the shell user. It can't use app resources or the
 * app's Context; it only runs commands.
 */
class ShellUserService : IShellService.Stub() {
    override fun destroy() {
        exitProcess(0)
    }

    override fun stream(command: String): ParcelFileDescriptor {
        val (read, write) = ParcelFileDescriptor.createPipe()
        val process = ProcessBuilder(streamArgv(command)).redirectErrorStream(true).start()
        process.outputStream.close()
        thread(isDaemon = true, name = "stream") {
            try {
                ParcelFileDescriptor.AutoCloseOutputStream(write).use { out ->
                    process.inputStream.copyTo(out)
                }
            } catch (_: IOException) {
                // The app closed its end: it stopped reading, so the command stops too.
            } finally {
                process.destroy()
                runCatching { process.inputStream.close() }
            }
        }
        // A quiet command writes nothing that could fail, so the app leaving would go unnoticed;
        // a pipe whose reader has gone reports POLLERR without a write.
        thread(isDaemon = true, name = "stream-watch") {
            val pipe = StructPollfd().apply { fd = write.fileDescriptor }
            while (process.isAlive) {
                runCatching { Os.poll(arrayOf(pipe), WATCH_MILLIS) }
                if (pipe.revents.toInt() and (OsConstants.POLLERR or OsConstants.POLLHUP) != 0) {
                    // The poll answers at once from here on, so wait rather than loop: a wrapped
                    // command's group gets its own few seconds to end (Stoppable).
                    process.destroy()
                    if (!process.waitFor(STOP_GRACE_SECONDS, TimeUnit.SECONDS)) {
                        process.destroyForcibly()
                    }
                }
            }
        }
        // Returned to the app, which now holds the only read end that matters.
        return read
    }

    override fun exec(command: String, timeoutMillis: Long): ParcelFileDescriptor {
        val (read, write) = ParcelFileDescriptor.createPipe()
        thread(isDaemon = true, name = "exec") {
            // Closing the write end without a whole frame is how the app learns it failed.
            ParcelFileDescriptor.AutoCloseOutputStream(write).use { out ->
                try {
                    ResultFrame.write(
                        ProcessExec.run(listOf("sh", "-c", command), timeoutMillis),
                        out,
                    )
                } catch (_: IOException) {
                    // The app stopped reading, so nobody is waiting for the result.
                }
            }
        }
        return read
    }
}
