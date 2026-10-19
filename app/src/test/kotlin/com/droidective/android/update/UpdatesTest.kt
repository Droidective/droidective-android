package com.droidective.android.update

import android.app.Application
import android.app.NotificationManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], application = Application::class)
class UpdatesTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val notifications = context.getSystemService(NotificationManager::class.java)
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val endpoint = "http://127.0.0.1:${server.address.port}/latest"
    private var code = 200
    private var body = release("v0.2.0")

    init {
        server.createContext("/latest") { exchange ->
            val bytes = body.encodeToByteArray()
            exchange.sendResponseHeaders(code, if (bytes.isEmpty()) -1 else bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
    }

    @After fun stop() = server.stop(0)

    private fun check(fromBackground: Boolean, installed: String = "0.1.0") = runBlocking {
        Updates.check(context, fromBackground, installed, endpoint)
    }

    private fun posted() = shadowOf(notifications).allNotifications

    @Test
    fun aNewReleaseIsAnnouncedOnceFromTheBackground() {
        val status = assertIs<UpdateStatus.Available>(check(fromBackground = true))
        assertEquals("0.2.0", status.version)
        assertEquals(status, Updates.status(context))
        assertEquals(
            "Droidective 0.2.0 is available",
            posted().single().extras.getString("android.title"),
        )

        notifications.cancelAll()
        check(fromBackground = true)
        assertEquals(0, posted().size, "the same release must not be announced twice")

        body = release("v0.3.0")
        check(fromBackground = true)
        assertEquals(1, posted().size, "a newer release is announced again")
    }

    @Test
    fun aReleaseSeenOnScreenIsNotAnnouncedLater() {
        assertIs<UpdateStatus.Available>(check(fromBackground = false))
        assertEquals(0, posted().size)
        check(fromBackground = true)
        assertEquals(0, posted().size)
    }

    @Test
    fun theInstalledReleaseIsUpToDate() {
        assertIs<UpdateStatus.UpToDate>(check(fromBackground = true, installed = "0.2.0"))
        assertEquals(0, posted().size)
    }

    @Test
    fun noReleaseAndFailuresAreRecordedNotAnnounced() {
        code = 404
        body = """{"message": "Not Found"}"""
        assertIs<UpdateStatus.NoRelease>(check(fromBackground = true))
        assertIs<UpdateStatus.NoRelease>(Updates.status(context))

        code = 500
        body = ""
        val failed = assertIs<UpdateStatus.Failed>(check(fromBackground = true))
        assertEquals("GitHub answered HTTP 500.", failed.reason)
        assertEquals(failed, Updates.status(context))
        assertEquals(0, posted().size)
    }

    private fun release(tag: String) =
        """{
          "tag_name": "$tag",
          "html_url": "https://github.com/Droidective/droidective-android/releases/tag/$tag",
          "body": "Notes.",
          "assets": []
        }"""
}
