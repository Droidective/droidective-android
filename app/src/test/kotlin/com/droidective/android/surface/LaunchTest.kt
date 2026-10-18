package com.droidective.android.surface

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], application = Application::class)
class LaunchTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()

    private fun uri(intent: Intent) = intent.toUri(Intent.URI_INTENT_SCHEME)

    @Test
    fun aShortcutCantPointBackIntoThisApp() {
        // Another app's CREATE_SHORTCUT result aimed at one of our private activities would
        // otherwise run with our identity.
        val sneaky =
            Intent()
                .setComponent(
                    ComponentName(
                        context.packageName,
                        "com.droidective.android.folder.FolderActivity",
                    )
                )
        assertNull(Launch.shortcutIntent(context, uri(sneaky)))
        assertNull(Launch.shortcutIntent(context, uri(Intent("x").setPackage(context.packageName))))
    }

    @Test
    fun aShortcutLosesItsUriGrantFlags() {
        val chat =
            Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/123"))
                .setPackage("com.whatsapp")
                .addFlags(
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
        val cleaned = assertNotNull(Launch.shortcutIntent(context, uri(chat)))
        assertEquals(
            0,
            cleaned.flags and
                (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION),
        )
        assertEquals("com.whatsapp", cleaned.`package`)
    }

    @Test
    fun anUnparseableShortcutOpensNothing() {
        assertNull(Launch.shortcutIntent(context, "intent:#Intent;launchFlags=zz;end"))
        assertNull(Launch.shortcutIntent(context, "intent:#Intent;%zz;end"))
    }
}
