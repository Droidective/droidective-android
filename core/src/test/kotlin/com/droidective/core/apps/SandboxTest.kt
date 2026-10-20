package com.droidective.core.apps

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Against `ls -la` and SharedPreferences files from an API 36 emulator (fixtures/sandbox/). The
 * `-crlf-links-spaces` listing and `prefs-mixed.xml` are those with CRLF, a link, a name with a
 * space and every value type added by hand.
 */
class SandboxTest {
    private val dir = File(System.getProperty("fixtures.dir"), "sandbox")

    private fun fixture(name: String) = File(dir, name).readText()

    private val root = SandboxAccess.Root("/data/user/0/com.example.app")

    @Test
    fun listsAnAppsDataFolderWithoutDotAndDotDot() {
        val entries = SandboxListing.parse(fixture("ls-data-dir.txt"))
        assertEquals(
            listOf("cache", "code_cache", "databases", "files", "no_backup", "shared_prefs"),
            entries.map { it.name },
        )
        assertTrue(entries.all { it.isDirectory })
        assertEquals("2026-10-20 01:53", entries.first { it.name == "files" }.modified)
    }

    @Test
    fun keepsSpacesInNamesDropsLinkTargetsAndSurvivesCrlf() {
        val entries = SandboxListing.parse(fixture("ls-crlf-links-spaces.txt"))
        assertEquals(
            listOf(
                "androidx.work.workdb",
                "androidx.work.workdb-shm",
                "androidx.work.workdb-wal",
                "lib",
                "my notes.txt",
            ),
            entries.map { it.name },
        )
        val lib = entries.first { it.name == "lib" }
        assertTrue(lib.isLink)
        assertFalse(lib.isDirectory)
        assertEquals(106496L, entries.first().size)
    }

    @Test
    fun aDeviceNodeHasNoSize() {
        val entries = SandboxListing.parse("crw-rw-rw- 1 root root 1,   3 2026-10-19 12:27 null\n")
        assertEquals(1, entries.size)
        assertNull(entries.single().size)
    }

    @Test
    fun runAsWorksInsideTheFolderAndRootNamesItInFull() {
        assertEquals(
            "run-as 'com.example.app' ls -la 'shared_prefs'",
            Sandbox.list("com.example.app", "shared_prefs", SandboxAccess.RunAs).line,
        )
        assertEquals(
            "run-as 'com.example.app' ls -la '.'",
            Sandbox.list("com.example.app", "", SandboxAccess.RunAs).line,
        )
        assertEquals(
            "nsenter -t 1 -m -- ls -la '/data/user/0/com.example.app/files/my notes.txt'",
            Sandbox.list("com.example.app", "files/my notes.txt", root).line,
        )
        assertEquals(
            "run-as 'com.example.app' head -c 262144 'files/a.json'",
            Sandbox.read("com.example.app", "files/a.json", SandboxAccess.RunAs).line,
        )
    }

    @Test
    fun aCopyRedirectsOutsideRunAsAndQuotesBothEnds() {
        assertEquals(
            "run-as 'com.example.app' cat 'databases/app.db' | cat > '/sdcard/Android/data/x/it'\\''s.db'",
            Sandbox.copy(
                    "com.example.app",
                    "databases/app.db",
                    SandboxAccess.RunAs,
                    "/sdcard/Android/data/x/it's.db",
                )
                .line,
        )
    }

    @Test
    fun rootReadsInInitsNamespaceAndWritesInThisAppsOwn() {
        assertEquals(
            "nsenter -t 1 -m -- cat '/data/user/0/com.example.app/databases/app.db' > '/sdcard/x.db'",
            Sandbox.copy("com.example.app", "databases/app.db", root, "/sdcard/x.db").line,
        )
    }

    @Test
    fun aPathCantLeaveTheAppsFolder() {
        for (bad in listOf("../other", "files/../../etc", "/etc/hosts", "a\nb", "a\u0000b")) {
            assertFalse(Sandbox.isSafePath(bad), bad)
            assertFailsWith<IllegalArgumentException>(bad) {
                Sandbox.list("com.example.app", bad, root)
            }
        }
        assertTrue(Sandbox.isSafePath("files/..hidden"))
        assertTrue(Sandbox.isSafePath("files/it's here; reboot"))
        assertEquals(
            "nsenter -t 1 -m -- ls -la '/data/user/0/com.example.app/files/it'\\''s here; reboot'",
            Sandbox.list("com.example.app", "files/it's here; reboot", root).line,
        )
        assertFailsWith<IllegalArgumentException> {
            Sandbox.list("com.example.app; reboot", "", SandboxAccess.RunAs)
        }
    }

    @Test
    fun rootNeedsARealDataFolder() {
        for (bad in listOf("relative/dir", "/sdcard/x", "/data/../etc", "/data/user/0/a\nb")) {
            assertFailsWith<IllegalArgumentException>(bad) { SandboxAccess.Root(bad) }
        }
    }

    @Test
    fun aDatabaseIsCopiedWithItsWriteAheadLog() {
        assertEquals(
            listOf("no_backup/a.db", "no_backup/a.db-wal", "no_backup/a.db-shm"),
            Sandbox.databaseParts("no_backup/a.db", listOf("a.db", "a.db-shm", "a.db-wal", "b")),
        )
        assertEquals(listOf("solo.db"), Sandbox.databaseParts("solo.db", listOf("solo.db")))
    }

    @Test
    fun aDatabaseIsKnownByItsHeaderNotItsName() {
        assertTrue(Sandbox.isDatabaseHeader("SQLite format 3\u0000"))
        assertTrue(Sandbox.isDatabaseHeader("SQLite format 3"), "the shell may drop the NUL")
        assertFalse(Sandbox.isDatabaseHeader("{\"json\": 1}"))
    }

    @Test
    fun readsPreferencesWithTheirTypesInFileOrder() {
        val updates = PreferencesFile.parse(fixture("prefs-updates.xml"))
        assertEquals(
            listOf("at", "page", "notified-tag", "version", "kind"),
            updates?.map { it.name },
        )
        assertEquals(Preference("at", "long", "1792422560258"), updates?.first())

        val mixed = PreferencesFile.parse(fixture("prefs-mixed.xml"))
        assertEquals(
            listOf(
                Preference("onboarded", "boolean", "true"),
                Preference("launches", "int", "42"),
                Preference("volume", "float", "0.5"),
                Preference("watched", "set", "com.example.one, com.example.two"),
                Preference("empty", "set", ""),
                Preference("json", "string", "{\"a\":1,\"b\":<2>}"),
                Preference("blank", "string", ""),
            ),
            mixed,
        )
    }

    @Test
    fun aFileThatIsntPreferencesSaysSo() {
        assertNull(PreferencesFile.parse("{\"not\": \"xml\"}"))
        assertNull(PreferencesFile.parse("<other><string name=\"a\">b</string></other>"))
        assertNull(PreferencesFile.parse("<!DOCTYPE x [<!ENTITY e SYSTEM \"file:///etc\">]><map/>"))
        assertNull(PreferencesFile.parse("<?xml version='1.0'?><!doctype map><map/>"))
    }
}
