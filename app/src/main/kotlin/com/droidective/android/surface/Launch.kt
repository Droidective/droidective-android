package com.droidective.android.surface

import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import com.droidective.android.MainActivity
import com.droidective.android.folder.FolderActivity
import com.droidective.android.screen.ToggleScreenActivity
import com.droidective.core.layout.Target
import java.net.URISyntaxException

/** Turns a tap's target into the activity intent that opens it. */
object Launch {
    const val EXTRA_DESTINATION = "com.droidective.android.extra.DESTINATION"
    const val DESTINATION_SETUP = "setup"
    const val DESTINATION_TILES = "tiles"
    const val DESTINATION_TOOL = "tool"
    const val EXTRA_TOOL = "com.droidective.android.extra.TOOL"

    private val grantFlags =
        Intent.FLAG_GRANT_READ_URI_PERMISSION or
            Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
            Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
            Intent.FLAG_GRANT_PREFIX_URI_PERMISSION

    /**
     * @return null when the target can't be opened safely, such as a shortcut aimed at this app.
     */
    fun intentFor(context: Context, target: Target): Intent? =
        when (target) {
            // Looked up when tapped: the app may have been uninstalled, or reinstalled, since.
            is Target.App ->
                LaunchAppActivity.intent(
                    context,
                    target.packageName,
                    target.activityName,
                    target.label,
                )
            is Target.Shortcut -> shortcutIntent(context, target.intentUri)
            is Target.DeepLink -> Intent(Intent.ACTION_VIEW, target.uri.toUri())
            is Target.SystemScreen -> Intent(target.action)
            is Target.Folder ->
                Intent(context, FolderActivity::class.java)
                    .putExtra(FolderActivity.EXTRA_FOLDER_ID, target.folderId)
                    // One task per folder, so a second folder doesn't replace the first.
                    .setData("droidective://folder/${target.folderId}".toUri())
            is Target.ToggleScreen ->
                Intent(context, ToggleScreenActivity::class.java)
                    .putExtra(ToggleScreenActivity.EXTRA_TOGGLE_ID, target.toggleId)
            is Target.Setup ->
                Intent(context, MainActivity::class.java)
                    .putExtra(EXTRA_DESTINATION, DESTINATION_SETUP)
            is Target.Tool ->
                Intent(context, MainActivity::class.java)
                    .putExtra(EXTRA_DESTINATION, DESTINATION_TOOL)
                    .putExtra(EXTRA_TOOL, target.featureId)
        }?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /**
     * Another app's shortcut is an intent we didn't write. It's only ever started as an activity,
     * with URI-grant flags, clip data and selector removed, and never when it points back into this
     * app: that would let any app reach our private activities with our identity.
     */
    internal fun shortcutIntent(context: Context, uri: String): Intent? {
        val intent =
            try {
                Intent.parseUri(uri, Intent.URI_INTENT_SCHEME)
            } catch (_: URISyntaxException) {
                return null
            } catch (_: IllegalArgumentException) {
                // parseUri throws NumberFormatException for a malformed launchFlags, for example.
                return null
            }
        val target = intent.component?.packageName ?: intent.`package`
        if (target == context.packageName) return null
        intent.removeFlags(grantFlags)
        intent.clipData = null
        intent.selector = null
        return intent
    }
}
