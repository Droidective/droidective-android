package com.droidective.android.surface

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import androidx.core.content.IntentCompat
import com.droidective.core.apps.bundles.AppPackageFormat
import com.droidective.core.commands.SharedLinks
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * What another app handed over — an app package opened from Files, a link shared from a chat — and
 * which tool it is for. The tool takes it and clears it.
 *
 * Everything here arrives from an app this one knows nothing about, so it is only ever shown: a
 * package goes to Install App, which reads it and waits for Install; a link is put in Deep Links'
 * field, and nothing opens it until someone presses Open.
 */
object Incoming {
    const val INSTALL_APP = "install-app"
    const val DEEP_LINK = "deep-link"

    /** An app package waiting for Install App. */
    val packageFile = MutableStateFlow<Uri?>(null)

    /** A link waiting for Deep Links. */
    val link = MutableStateFlow<String?>(null)

    /**
     * The tool [intent] is for, having handed it its file or link; null for an intent of no use.
     */
    fun route(intent: Intent): String? =
        when (intent.action) {
            Intent.ACTION_VIEW -> intent.data?.let(::takePackage)
            Intent.ACTION_SEND -> shared(intent)
            else -> null
        }

    private fun shared(intent: Intent): String? {
        val stream = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
        if (stream != null && AppPackageFormat.ofMediaType(intent.type) != null) {
            return takePackage(stream)
        }
        val found = intent.getStringExtra(Intent.EXTRA_TEXT)?.let(SharedLinks::first) ?: return null
        link.value = found
        return DEEP_LINK
    }

    /**
     * Only a content URI, which the app that sent it granted. A `file://` one would read whatever
     * path it names with this app's own access — this app's private files included.
     */
    private fun takePackage(uri: Uri): String? {
        if (uri.scheme != ContentResolver.SCHEME_CONTENT) return null
        packageFile.value = uri
        return INSTALL_APP
    }
}
