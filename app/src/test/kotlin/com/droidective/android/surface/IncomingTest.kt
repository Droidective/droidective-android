package com.droidective.android.surface

import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], application = Application::class)
class IncomingTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val apk = Uri.parse("content://com.android.externalstorage.documents/document/app.apk")

    @Before
    fun clear() {
        Incoming.packageFile.value = null
        Incoming.link.value = null
    }

    @Test
    fun aPackageOpenedFromFilesGoesToInstallApp() {
        val open = Intent(Intent.ACTION_VIEW).setDataAndType(apk, APK)
        assertEquals("install-app", Incoming.route(open))
        assertEquals(apk, Incoming.packageFile.value)
    }

    @Test
    fun aSharedPackageGoesToInstallApp() {
        val share = Intent(Intent.ACTION_SEND).setType(APK).putExtra(Intent.EXTRA_STREAM, apk)
        assertEquals("install-app", Incoming.route(share))
        assertEquals(apk, Incoming.packageFile.value)
    }

    @Test
    fun aFileUriIsNeverRead() {
        // It would read the path it names with this app's own access, private files included.
        val own = Uri.parse("file:///data/data/com.droidective.android/files/proxy-ca.p12")
        assertNull(Incoming.route(Intent(Intent.ACTION_VIEW).setDataAndType(own, APK)))
        assertNull(
            Incoming.route(
                Intent(Intent.ACTION_SEND).setType(APK).putExtra(Intent.EXTRA_STREAM, own)
            )
        )
        assertNull(Incoming.packageFile.value)
    }

    @Test
    fun aSharedLinkGoesToDeepLinksAndIsNotOpened() {
        val share =
            Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, "Try (myapp://checkout?id=42) please")
        assertEquals("deep-link", Incoming.route(share))
        assertEquals("myapp://checkout?id=42", Incoming.link.value)
    }

    @Test
    fun sharedTextWithNoLinkIsOfNoUse() {
        val share =
            Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "hello")
        assertNull(Incoming.route(share))
        assertNull(Incoming.link.value)
    }

    @Test
    fun aStreamThatIsNotAPackageIsNotTakenForOne() {
        val photo = Uri.parse("content://media/external/images/media/9")
        val share =
            Intent(Intent.ACTION_SEND).setType("image/png").putExtra(Intent.EXTRA_STREAM, photo)
        assertNull(Incoming.route(share))
        assertNull(Incoming.packageFile.value)
    }

    @Test
    fun theseAreWhatOtherAppsCanHandOver() {
        val reachable =
            listOf(
                Intent(Intent.ACTION_VIEW).setDataAndType(apk, APK),
                Intent(Intent.ACTION_SEND).setType(APK),
                Intent(Intent.ACTION_SEND).setType("application/xapk-package-archive"),
                Intent(Intent.ACTION_SEND).setType("application/vnd.apkm"),
                Intent(Intent.ACTION_SEND).setType("text/plain"),
            )
        reachable.forEach { assertTrue(resolves(it), it.toString()) }
        // Not every file: a photo shared from the gallery is not offered to Droidective.
        assertTrue(!resolves(Intent(Intent.ACTION_SEND).setType("image/png")))
    }

    private fun resolves(intent: Intent): Boolean =
        context.packageManager
            .queryIntentActivities(intent.setPackage(context.packageName), PackageManager.MATCH_ALL)
            .any { it.activityInfo.name == "com.droidective.android.MainActivity" }

    private companion object {
        const val APK = "application/vnd.android.package-archive"
    }
}
