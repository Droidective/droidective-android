package com.droidective.platform

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager.PERMISSION_GRANTED
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.droidective.core.network.NetworkNow
import com.droidective.core.network.PublicIp
import com.droidective.core.network.Transport
import com.droidective.core.shell.Parse
import com.droidective.core.shell.Shell
import com.droidective.core.shell.ShellCommand
import com.droidective.core.shell.ShellUnavailableException
import com.droidective.platform.shell.AppShell
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The phone's network as the network widget shows it: how it's connected, its address here, the
 * Wi-Fi name, and the address servers see.
 *
 * Android tells an app the Wi-Fi name only with location access, so it's read through the shell, as
 * automations read it, and left out without one. The public address is asked of a web service, so
 * it's kept per network for a while rather than asked on every redraw.
 */
object NetworkReader {
    @Volatile private var lookedUp: LookedUp? = null

    /** A public address and the network it was found on. */
    private class LookedUp(val network: String, val address: String, val at: Long)

    /**
     * The network now.
     *
     * @param public whether to look up the public address, which asks [PublicIp.URL].
     * @param shell the shell to read the Wi-Fi name through, or null when there's none.
     */
    suspend fun now(context: Context, public: Boolean, shell: Shell?): NetworkNow {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val network = connectivity.activeNetwork
        val caps = network?.let(connectivity::getNetworkCapabilities)
        val transport = transportOf(caps)
        if (transport == Transport.None) return NetworkNow(Transport.None)
        val addresses = NetworkFacts.current(context)
        val name = if (transport == Transport.Wifi) wifiName(context, shell) else Found(null)
        val looked = if (public) publicIp(network.toString()) else Found(null)
        return NetworkNow(
            transport = transport,
            wifiName = name.value,
            localIp = addresses?.ipv4 ?: addresses?.ipv6?.firstOrNull(),
            publicIp = looked.value,
            nameProblem = name.problem,
            publicProblem = looked.problem,
        )
    }

    /** A value, or why there isn't one. */
    private class Found(val value: String?, val problem: String? = null)

    /** The public address found last on any network, for a tap to copy without asking again. */
    fun lastPublic(): String? = lookedUp?.address

    private fun transportOf(caps: NetworkCapabilities?): Transport =
        when {
            caps == null -> Transport.None
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> Transport.Vpn
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> Transport.Wifi
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> Transport.Cellular
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> Transport.Ethernet
            else -> Transport.Other
        }

    /**
     * The Wi-Fi name: from `dumpsys wifi` as the app itself when it holds DUMP (the Granted level),
     * which takes no root and shows no root toast; else through the shell. Android tells an app
     * without location access only `<unknown ssid>`.
     */
    private suspend fun wifiName(context: Context, shell: Shell?): Found {
        val problems = mutableListOf<String>()
        if (context.checkSelfPermission(Manifest.permission.DUMP) == PERMISSION_GRANTED) {
            read(AppShell(), WIFI_DUMP, Parse::wifiSsidFromDump)?.let {
                if (it.value != null) return it
                problems += "dumpsys wifi: ${it.problem}"
            }
        }
        if (shell == null) {
            problems += "no Shell to ask Android through"
            return Found(null, problems.joinToString("; "))
        }
        for ((command, parse) in
            listOf(WIFI_STATUS to Parse::wifiSsid, WIFI_DUMP to Parse::wifiSsidFromDump)) {
            val found = read(shell, command, parse) ?: continue
            if (found.value != null) return found
            problems += "${command.line.substringBefore(" |")}: ${found.problem}"
        }
        return Found(null, problems.joinToString("; "))
    }

    /** What [command] says the name is, or why it says none; null when [shell] is gone. */
    private suspend fun read(
        shell: Shell,
        command: ShellCommand,
        parse: (String) -> String?,
    ): Found? =
        try {
            val result = shell.run(command)
            when {
                !result.succeeded ->
                    Found(
                        null,
                        "exit ${result.exitCode} ${result.stderr.lineSequence().firstOrNull().orEmpty()}"
                            .trim(),
                    )
                else ->
                    parse(result.stdout)?.let(::Found)
                        ?: Found(null, "no network name in what it printed")
            }
        } catch (_: ShellUnavailableException) {
            null
        }

    /**
     * The address servers see from [network], kept for [KEEP_MILLIS]: it changes with the network,
     * rarely otherwise. Asked directly, past any proxy: through Network Traffic's, the app's own
     * certificate would stand in for the service's.
     */
    private suspend fun publicIp(network: String): Found {
        val kept = lookedUp
        val now = System.currentTimeMillis()
        if (kept != null && kept.network == network && now - kept.at < KEEP_MILLIS) {
            return Found(kept.address)
        }
        val found = withContext(Dispatchers.IO) { fetch() }
        found.value?.let { lookedUp = LookedUp(network, it, now) }
        return found
    }

    private fun fetch(): Found {
        val host = PublicIp.URL.substringAfter("://")
        val connection = URL(PublicIp.URL).openConnection(Proxy.NO_PROXY) as HttpURLConnection
        return try {
            connection.connectTimeout = TIMEOUT_MILLIS
            connection.readTimeout = TIMEOUT_MILLIS
            connection.instanceFollowRedirects = false
            val code = connection.responseCode
            if (code != HttpURLConnection.HTTP_OK) return Found(null, "$host answered $code")
            val body =
                connection.inputStream.use { String(readUpTo(it, MAX_BYTES), Charsets.UTF_8) }
            PublicIp.parse(body)?.let(::Found)
                ?: Found(null, "$host answered something that isn't an address")
        } catch (e: IOException) {
            Found(null, "couldn't reach $host: ${e.message ?: e.javaClass.simpleName}")
        } finally {
            connection.disconnect()
        }
    }

    /** Up to [limit] bytes of [input]: an address is short, and a portal's page needn't be read. */
    private fun readUpTo(input: InputStream, limit: Int): ByteArray {
        val bytes = ByteArray(limit)
        var filled = 0
        var read = input.read(bytes)
        while (read > 0) {
            filled += read
            if (filled == limit) break
            read = input.read(bytes, filled, limit - filled)
        }
        return bytes.copyOf(filled)
    }

    private val WIFI_STATUS = ShellCommand.of("cmd", "wifi", "status")

    /** Only the lines that name a network: the whole dump is long. */
    private val WIFI_DUMP = ShellCommand("dumpsys wifi | grep 'mWifiInfo SSID'")
    private const val KEEP_MILLIS = 10 * 60 * 1000L
    private const val TIMEOUT_MILLIS = 5_000
    private const val MAX_BYTES = 64
}
