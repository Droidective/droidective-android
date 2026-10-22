package com.droidective.android.location

import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.os.Process
import com.droidective.android.proxy.KeptList
import com.droidective.core.location.Coordinates
import com.droidective.core.location.Place
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Mock Location: the phone reporting a place of the person's choosing to every app that asks where
 * it is. Android lets one app at a time do this — the mock location app picked in Developer options
 * — through test providers that stand in for GPS, the network and the fused provider.
 */
object MockLocation {
    const val TOOL = "mock-location"

    sealed interface State {
        data object Off : State

        /** The phone says it is at [where], [name] when the place has one. */
        data class On(val where: Coordinates, val name: String?) : State

        /** It couldn't stand in; [notChosen] when Droidective isn't the mock location app. */
        data class Failed(val why: String, val notChosen: Boolean) : State
    }

    internal val state_ = MutableStateFlow<State>(State.Off)
    val state: StateFlow<State> = state_.asStateFlow()

    internal val places = KeptList("mock-places.json", Place.serializer(), Place::id) {}

    fun start(context: Context, where: Coordinates, name: String?) {
        context.startForegroundService(
            Intent(context, MockLocationService::class.java)
                .putExtra(MockLocationService.EXTRA_LATITUDE, where.latitude)
                .putExtra(MockLocationService.EXTRA_LONGITUDE, where.longitude)
                .putExtra(MockLocationService.EXTRA_NAME, name)
        )
    }

    fun stop(context: Context) {
        context.startService(
            Intent(context, MockLocationService::class.java)
                .setAction(MockLocationService.ACTION_STOP)
        )
    }

    /** Whether Droidective is the mock location app, the one Android lets stand in. */
    fun chosen(context: Context): Boolean {
        val ops = context.getSystemService(AppOpsManager::class.java)
        val mode =
            ops.checkOpNoThrow(
                AppOpsManager.OPSTR_MOCK_LOCATION,
                Process.myUid(),
                context.packageName,
            )
        return mode == AppOpsManager.MODE_ALLOWED
    }
}
