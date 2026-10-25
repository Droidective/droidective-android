package com.droidective.platform

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.droidective.core.network.NetworkNow
import com.droidective.core.network.PublicIp
import com.droidective.core.network.Transport
import com.droidective.core.shell.Parse
import com.droidective.core.shell.Shell
import com.droidective.core.shell.ShellCommand
import com.droidective.core.shell.ShellUnavailableException
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
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
        return NetworkNow(
            transport = transport,
            wifiName = if (transport == Transport.Wifi) wifiName(shell) else null,
            localIp = addresses?.ipv4 ?: addresses?.ipv6?.firstOrNull(),
            publicIp = if (public) publicIp(network.toString()) else null,
        )
    }

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

    private suspend fun wifiName(shell: Shell?): String? {
        shell ?: return null
        return try {
            shell.run(WIFI_STATUS).takeIf { it.succeeded }?.stdout?.let(Parse::wifiSsid)
        } catch (_: ShellUnavailableException) {
            null
        }
    }

    /**
     * The address servers see from [network], kept for [KEEP_MILLIS]: it changes with the network,
     * rarely otherwise. Null when the lookup fails, such as behind a sign-in page.
     */
    private suspend fun publicIp(network: String): String? {
        val kept = lookedUp
        val now = System.currentTimeMillis()
        if (kept != null && kept.network == network && now - kept.at < KEEP_MILLIS) {
            return kept.address
        }
        val found = withContext(Dispatchers.IO) { fetch() } ?: return null
        lookedUp = LookedUp(network, found, now)
        return found
    }

    private fun fetch(): String? {
        val connection = URL(PublicIp.URL).openConnection() as HttpURLConnection
        return try {
            connection.connectTimeout = TIMEOUT_MILLIS
            connection.readTimeout = TIMEOUT_MILLIS
            connection.instanceFollowRedirects = false
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return null
            val body =
                connection.inputStream.use { String(readUpTo(it, MAX_BYTES), Charsets.UTF_8) }
            PublicIp.parse(body)
        } catch (_: IOException) {
            null
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
    private const val KEEP_MILLIS = 10 * 60 * 1000L
    private const val TIMEOUT_MILLIS = 5_000
    private const val MAX_BYTES = 64
}
