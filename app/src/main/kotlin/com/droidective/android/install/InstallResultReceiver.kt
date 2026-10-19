package com.droidective.android.install

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import com.droidective.platform.AppInstaller
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Where an install session ended up. */
sealed interface InstallOutcome {
    /** Android's confirm dialog is up. */
    data object Confirming : InstallOutcome

    data class Installed(val packageName: String?) : InstallOutcome

    data class Failed(val message: String) : InstallOutcome
}

/** Each session's latest outcome, by session id, for the Install App screen. */
object InstallResults {
    private val outcomes = MutableStateFlow<Map<Int, InstallOutcome>>(emptyMap())
    val all: StateFlow<Map<Int, InstallOutcome>> = outcomes.asStateFlow()

    fun record(session: Int, outcome: InstallOutcome) = outcomes.update {
        it + (session to outcome)
    }

    /** What a session's status means, in words; PackageInstaller's own message adds the detail. */
    fun describe(status: Int, message: String?): InstallOutcome =
        when (status) {
            PackageInstaller.STATUS_SUCCESS -> InstallOutcome.Installed(null)
            PackageInstaller.STATUS_FAILURE_ABORTED ->
                InstallOutcome.Failed("The install was cancelled.")
            PackageInstaller.STATUS_FAILURE_CONFLICT ->
                InstallOutcome.Failed(
                    "A version signed by a different key is installed. Uninstall it first, then " +
                        "install this one."
                )
            PackageInstaller.STATUS_FAILURE_INCOMPATIBLE ->
                InstallOutcome.Failed(
                    "This app doesn't run on this phone. ${message.orEmpty()}".trim()
                )
            PackageInstaller.STATUS_FAILURE_STORAGE ->
                InstallOutcome.Failed("There isn't room for it on this phone.")
            PackageInstaller.STATUS_FAILURE_BLOCKED ->
                InstallOutcome.Failed("Android blocked the install. ${message.orEmpty()}".trim())
            else -> InstallOutcome.Failed(message ?: "The install failed (status $status).")
        }
}

/**
 * Android's answer about an install session. A pending one carries the confirm dialog to show; the
 * person just tapped Install in Droidective, so the app is in front and may start it.
 */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val session = intent.getIntExtra(AppInstaller.EXTRA_SESSION, -1)
        val status =
            intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            confirmIntent(intent)?.let {
                context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                InstallResults.record(session, InstallOutcome.Confirming)
            }
            return
        }
        val outcome =
            InstallResults.describe(
                status,
                intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE),
            )
        InstallResults.record(
            session,
            if (outcome is InstallOutcome.Installed) {
                InstallOutcome.Installed(intent.getStringExtra(PackageInstaller.EXTRA_PACKAGE_NAME))
            } else {
                outcome
            },
        )
    }

    private fun confirmIntent(intent: Intent): Intent? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
        } else {
            @Suppress("DEPRECATION") // The typed overload is API 33+.
            intent.getParcelableExtra(Intent.EXTRA_INTENT)
        }
}
