package com.droidective.core.apps

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FileSourcesTest {
    private val phone = PhoneFiles("/sdcard")

    @Test
    fun thePhoneIsReadAtQuotedAbsolutePaths() {
        assertEquals("ls -la '/sdcard/My Files'", phone.list("/sdcard/My Files").line)
        assertEquals("head -c 16 '/data/local/tmp/a.db'", phone.header("/data/local/tmp/a.db").line)
        assertEquals("head -c 262144 '/proc/meminfo'", phone.read("/proc/meminfo").line)
        assertEquals(
            "cat '/sdcard/it'\\''s.txt' > '/storage/x/copy'",
            phone.copy("/sdcard/it's.txt", "/storage/x/copy").line,
        )
    }

    @Test
    fun aPathIsBuiltOnlyFromNamesAListingGave() {
        assertEquals("/system", phone.child("/", "system"))
        assertEquals("/sdcard/Download", phone.child("/sdcard", "Download"))
        for (bad in listOf("..", ".", "a/b", "", "a\nb")) {
            assertFalse(PhoneFiles.isName(bad), bad)
            assertFailsWith<IllegalArgumentException>(bad) { phone.child("/sdcard", bad) }
        }
        for (bad in listOf("relative", "/a/../b", "/a\u0000")) {
            assertFailsWith<IllegalArgumentException>(bad) { phone.list(bad) }
        }
        assertTrue(PhoneFiles.isName("..hidden"))
    }

    @Test
    fun crumbsClimbBackToTheTop() {
        assertEquals(
            listOf(
                Crumb("/", "/"),
                Crumb("sdcard", "/sdcard"),
                Crumb("Download", "/sdcard/Download"),
            ),
            phone.crumbs("/sdcard/Download"),
        )
        assertEquals(listOf(Crumb("/", "/")), phone.crumbs("/"))
        val sandbox = SandboxSource("com.example.app", SandboxAccess.RunAs)
        assertEquals(
            listOf(Crumb("data", ""), Crumb("files", "files"), Crumb("layouts", "files/layouts")),
            sandbox.crumbs("files/layouts"),
        )
        assertEquals("files/a.json", sandbox.child("files", "a.json"))
        assertEquals("files", sandbox.child("", "files"))
    }

    @Test
    fun theSandboxSourceIsTheSandboxsCommands() {
        val sandbox = SandboxSource("com.example.app", SandboxAccess.RunAs)
        assertEquals(
            Sandbox.list("com.example.app", "files", SandboxAccess.RunAs),
            sandbox.list("files"),
        )
        assertEquals(
            Sandbox.copy("com.example.app", "a.db", SandboxAccess.RunAs, "/x"),
            sandbox.copy("a.db", "/x"),
        )
    }
}
