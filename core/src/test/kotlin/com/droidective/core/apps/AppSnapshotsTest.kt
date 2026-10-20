package com.droidective.core.apps

import com.droidective.core.shell.shellQuote
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class AppSnapshotsTest {
    private val utc = ZoneId.of("UTC")
    private val path =
        "/storage/emulated/0/Android/data/com.droidective.android/files/snapshots/a.tar"
    private val lower = "/data/media/0/Android/data/com.droidective.android/files/snapshots/a.tar"
    private val data = "/data/user/0/com.example.app"
    private val clear =
        "find . -mindepth 1 -maxdepth 1 ! -name lib ! -name cache ! -name code_cache " +
            "-exec rm -rf {} + && { find ./cache ./code_cache -mindepth 1 -delete 2>/dev/null; true; }"

    @Test
    fun namesASnapshotByAppAndTimeAndReadsItBack() {
        val name = AppSnapshots.fileName("com.example.app", 1_792_474_812_000, utc)
        assertEquals("com.example.app@2026-10-20-05-40-12.tar", name)
        assertEquals(
            AppSnapshot("com.example.app", 1_792_474_812_000, name),
            AppSnapshots.parse(name, utc),
        )
        listOf("notes.txt", "com.example.app@yesterday.tar", "../x@2026-10-20-05-40-12.tar")
            .forEach { assertNull(AppSnapshots.parse(it, utc), it) }
    }

    @Test
    fun asTheShellUserItRunsWhereItIs() {
        assertEquals(
            "set -o pipefail; run-as 'com.example.app' tar -cf - --exclude=./cache " +
                "--exclude=./code_cache . | cat > '$path'",
            AppSnapshots.save("com.example.app", SnapshotAccess.RunAs, data, path, asRoot = false)
                .line,
        )
        assertEquals(
            "am force-stop 'com.example.app' && cat '$path' | run-as 'com.example.app' sh -c " +
                shellQuote("$clear && tar -xf -"),
            AppSnapshots.restore(
                    "com.example.app",
                    SnapshotAccess.RunAs,
                    data,
                    path,
                    10_123,
                    asRoot = false,
                )
                .line,
        )
    }

    @Test
    fun asRootItWorksInInitsNamespaceWhereEveryAppsDataIs() {
        val save =
            AppSnapshots.save("com.example.app", SnapshotAccess.Root, data, path, asRoot = true)
                .line
        assertEquals(
            "nsenter -t 1 -m -- sh -c 'tar -cf '\\''$lower'\\'' -C '\\''$data'\\'' " +
                "--exclude=./cache --exclude=./code_cache .'",
            save,
        )
        val restore =
            AppSnapshots.restore(
                    "com.example.app",
                    SnapshotAccess.Root,
                    data,
                    path,
                    10_123,
                    asRoot = true,
                )
                .line
        listOf(
                "nsenter -t 1 -m -- sh -c ",
                "am force-stop",
                "cd '\\''$data'\\''",
                "! -name cache ! -name code_cache",
                "tar -xf '\\''$lower'\\''",
                "ctx=$(stat -c %C '\\''$data'\\'')",
                "-exec chown -R 10123:10123 {} + -exec chcon -R \"\$ctx\" {} +",
            )
            .forEach { assertEquals(true, it in restore, "missing: $it in $restore") }
        assertEquals(false, "restorecon" in restore, "restorecon drops the app's categories")
    }

    @Test
    fun neverEmptiesAFolderThatIsntTheAppsOwn() {
        listOf("/", "/data/data", "/data/data/com.other.app", "/sdcard", "$data/..").forEach { dir
            ->
            assertFailsWith<IllegalArgumentException>(dir) {
                AppSnapshots.restore(
                    "com.example.app",
                    SnapshotAccess.Root,
                    dir,
                    path,
                    10_123,
                    true,
                )
            }
        }
        assertFailsWith<IllegalArgumentException> {
            AppSnapshots.restore("com.example.app", SnapshotAccess.Root, data, path, 1000, true)
        }
        assertFailsWith<IllegalArgumentException> {
            AppSnapshots.save(
                "com.x; reboot",
                SnapshotAccess.RunAs,
                "/data/data/com.x",
                path,
                false,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            AppSnapshots.save("com.example.app", SnapshotAccess.RunAs, data, "/sdcard/x.zip", false)
        }
        assertFailsWith<IllegalArgumentException>("root needs a shared-storage path to map") {
            AppSnapshots.save("com.example.app", SnapshotAccess.Root, data, "/tmp/a.tar", true)
        }
    }
}
