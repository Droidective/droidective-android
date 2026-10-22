package com.droidective.android.proxy

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Where file-backed mocks are read from, and that nothing outside it can be. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], application = Application::class)
class MockFolderTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val mocks = MockFolder(context)
    private val folder = assertNotNull(mocks.folder())

    @Test
    fun aFileInTheFolderIsReadAsItIsNow() {
        File(folder, "shop").mkdirs()
        val fixture = File(folder, "shop/menu.json").apply { writeText("""{"v":1}""") }
        assertEquals("""{"v":1}""", mocks.read("shop/menu.json").decodeToString())

        fixture.writeText("""{"v":2}""")

        assertEquals("""{"v":2}""", mocks.read("shop/menu.json").decodeToString())
    }

    @Test
    fun aFileThatIsntThereSaysWhereItWasLookedFor() {
        val missing = assertFailsWith<IOException> { mocks.read("nothing.json") }
        assertTrue(folder.path in missing.message.orEmpty())
        assertNull(mocks.find("nothing.json"))
    }

    @Test
    fun nothingOutsideTheFolderIsRead() {
        val secret = File(context.filesDir, "secret.txt").apply { writeText("private") }
        assertFailsWith<IOException> { mocks.read("../files/secret.txt") }
        assertFailsWith<IOException> { mocks.read(secret.path) }
        // A link that lives in the folder and points out of it.
        Files.createSymbolicLink(File(folder, "link.txt").toPath(), secret.toPath())
        assertNull(mocks.find("link.txt"))
        assertFailsWith<IOException> { mocks.read("link.txt") }
    }

    @Test
    fun aFileTooBigToAnswerWithSaysSo() {
        File(folder, "big.bin").outputStream().use { out ->
            val chunk = ByteArray(1024 * 1024)
            repeat(9) { out.write(chunk) }
        }
        val tooBig = assertFailsWith<IOException> { mocks.read("big.bin") }
        assertTrue("8 MB" in tooBig.message.orEmpty())
    }
}
