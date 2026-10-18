package com.droidective.platform

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The Bluetooth audio devices connected now: car stereos, headphones, speakers. Android tells an
 * app when a device connects, but asking each audio profile who's connected is the answer that
 * stays right after a restart or Bluetooth turning off.
 */
object BluetoothAudio {
    private const val PROXY_TIMEOUT_MILLIS = 2_000L

    /** Whether the app may read Bluetooth devices: Nearby devices, from Android 12. */
    fun allowed(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) ==
                PackageManager.PERMISSION_GRANTED

    /** Names of the connected audio devices, or null when the app may not ask. */
    suspend fun connectedNames(context: Context): Set<String>? {
        if (!allowed(context)) return null
        val adapter = adapter(context) ?: return emptySet()
        if (!adapter.isEnabled) return emptySet()
        val names = mutableSetOf<String>()
        for (profile in profiles()) {
            val proxy = proxy(context, adapter, profile) ?: continue
            try {
                proxy.connectedDevices.mapNotNullTo(names, ::nameOf)
            } catch (_: SecurityException) {
                return null
            } finally {
                adapter.closeProfileProxy(profile, proxy)
            }
        }
        return names
    }

    /** The paired devices' names, for picking one; null when the app may not ask. */
    fun pairedNames(context: Context): List<String>? {
        if (!allowed(context)) return null
        val adapter = adapter(context) ?: return emptyList()
        return try {
            adapter.bondedDevices.mapNotNull(::nameOf).distinct().sorted()
        } catch (_: SecurityException) {
            null
        }
    }

    private fun adapter(context: Context): BluetoothAdapter? =
        context.getSystemService(BluetoothManager::class.java)?.adapter

    private fun profiles(): List<Int> = buildList {
        add(BluetoothProfile.A2DP)
        add(BluetoothProfile.HEADSET)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(BluetoothProfile.LE_AUDIO)
    }

    private suspend fun proxy(
        context: Context,
        adapter: BluetoothAdapter,
        profile: Int,
    ): BluetoothProfile? =
        withTimeoutOrNull(PROXY_TIMEOUT_MILLIS) {
            suspendCancellableCoroutine { continuation ->
                val listener =
                    object : BluetoothProfile.ServiceListener {
                        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                            if (continuation.isActive) continuation.resume(proxy)
                        }

                        override fun onServiceDisconnected(profile: Int) = Unit
                    }
                if (!adapter.getProfileProxy(context, listener, profile)) {
                    continuation.resume(null)
                }
            }
        }

    private fun nameOf(device: BluetoothDevice): String? =
        try {
            device.name ?: device.address
        } catch (_: SecurityException) {
            null
        }
}
