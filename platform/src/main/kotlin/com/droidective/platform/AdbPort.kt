package com.droidective.platform

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import java.net.Inet4Address
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Finds the port wireless debugging listens on. Android picks a new one each time it starts and
 * keeps it from apps, but adbd advertises it over mDNS as `_adb-tls-connect._tcp`, which is how
 * Shizuku and LADB find it too. Apps targeting Android 17 need ACCESS_LOCAL_NETWORK for this.
 */
object AdbPort {
    private const val SERVICE = "_adb-tls-connect._tcp"

    /**
     * `ip:port` for `adb connect`, or null when wireless debugging is off or nothing advertises it.
     * adbd's `service.adb.tls.port` property would be quicker, but SELinux keeps it from apps and
     * from the shell user alike, so mDNS is the one answer that works.
     */
    suspend fun address(context: Context): String? {
        val ip = NetworkFacts.current(context)?.ipv4 ?: return null
        val port = find(context, ip) ?: return null
        return "$ip:$port"
    }

    /** The port this phone's adbd advertises, or null within [timeoutMillis] if none shows up. */
    suspend fun find(context: Context, ownAddress: String?, timeoutMillis: Long = 6_000): Int? {
        // NsdManager checks INTERNET while it's being created, not when discovery starts.
        val nsd =
            try {
                context.getSystemService(NsdManager::class.java)
            } catch (_: SecurityException) {
                return null
            }
        val found = CompletableDeferred<Int>()
        val listener =
            object : NsdManager.DiscoveryListener {
                override fun onServiceFound(service: NsdServiceInfo) {
                    resolve(nsd, service) { resolved ->
                        val host = hostOf(resolved)
                        // Other phones on the network advertise too; only ours counts.
                        if (ownAddress == null || host == ownAddress) found.complete(resolved.port)
                    }
                }

                override fun onDiscoveryStarted(serviceType: String) = Unit

                override fun onDiscoveryStopped(serviceType: String) = Unit

                override fun onServiceLost(service: NsdServiceInfo) = Unit

                override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                    found.cancel()
                }

                override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            }
        return try {
            nsd.discoverServices(SERVICE, NsdManager.PROTOCOL_DNS_SD, listener)
            withTimeoutOrNull(timeoutMillis) { runCatching { found.await() }.getOrNull() }
        } catch (_: SecurityException) {
            null
        } finally {
            runCatching { nsd.stopServiceDiscovery(listener) }
        }
    }

    @Suppress("DEPRECATION") // resolveService is the only resolver before Android 14.
    private fun resolve(
        nsd: NsdManager,
        service: NsdServiceInfo,
        onResolved: (NsdServiceInfo) -> Unit,
    ) {
        nsd.resolveService(
            service,
            object : NsdManager.ResolveListener {
                override fun onServiceResolved(info: NsdServiceInfo) = onResolved(info)

                override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) = Unit
            },
        )
    }

    private fun hostOf(info: NsdServiceInfo): String? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            info.hostAddresses.filterIsInstance<Inet4Address>().firstOrNull()?.hostAddress
        } else {
            @Suppress("DEPRECATION") // hostAddresses arrived in Android 14.
            info.host?.hostAddress
        }
}
