package com.droidective.core.apps

import com.droidective.core.shell.ShellCommand
import com.droidective.core.shell.shellQuote
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/** How an app's private files are reached: as the app through `run-as`, or as root. */
enum class SnapshotAccess {
    /** A debuggable build: `run-as` runs as the app, in its data folder. */
    RunAs,

    /** Any app, through su. */
    Root,
}

/** One saved copy of an app's data: which app, and when. */
data class AppSnapshot(val packageName: String, val takenAt: Long, val fileName: String)

/**
 * Saves an app's private data to a tar file and puts it back, so a test can start again from a
 * state that took a while to reach: signed in, onboarded, with a cart full. The app's cache is left
 * out, and it's stopped before a restore so it can't write over what's coming back.
 */
object AppSnapshots {
    private val NAME = DateTimeFormatter.ofPattern("yyyy-MM-dd-HH-mm-ss")
    private val file = Regex("""^([A-Za-z][\w.]*)@(\d{4}-\d{2}-\d{2}-\d{2}-\d{2}-\d{2})\.tar$""")
    private val dataFolder = Regex("""^/data/(data|user/\d+)/([A-Za-z][\w.]*)$""")
    private val sharedStorage = Regex("""^/storage/emulated/(\d+)/(.+)$""")

    /** `com.example.app@2026-10-20-06-40-00.tar`, in the phone's own time zone. */
    fun fileName(
        packageName: String,
        atMillis: Long,
        zone: ZoneId = ZoneId.systemDefault(),
    ): String {
        requirePackage(packageName)
        return "$packageName@" + NAME.withZone(zone).format(Instant.ofEpochMilli(atMillis)) + ".tar"
    }

    /** The snapshot [name] is, or null when it isn't one of these files. */
    fun parse(name: String, zone: ZoneId = ZoneId.systemDefault()): AppSnapshot? {
        val (packageName, stamp) = file.find(name)?.destructured ?: return null
        val at =
            try {
                LocalDateTime.parse(stamp, NAME).atZone(zone).toInstant().toEpochMilli()
            } catch (_: DateTimeParseException) {
                return null
            }
        return AppSnapshot(packageName, at, name)
    }

    /**
     * Writes the app's data folder to [path]. Through run-as, tar's output is piped through `cat`:
     * a redirect straight from run-as left the file empty. `pipefail` keeps run-as refusing from
     * reading as success, which `cat`'s status alone would.
     *
     * @param path where this app sees the file, under `/storage/emulated/<user>`.
     * @param asRoot whether the shell is root, which then works in init's mount namespace.
     */
    fun save(
        packageName: String,
        access: SnapshotAccess,
        dataDir: String,
        path: String,
        asRoot: Boolean,
    ): ShellCommand {
        val pkg = requirePackage(packageName)
        val file = shellQuote(place(path, asRoot))
        val excludes = "--exclude=./cache --exclude=./code_cache"
        val line =
            when (access) {
                SnapshotAccess.RunAs ->
                    "set -o pipefail; run-as $pkg tar -cf - $excludes . | cat > $file"
                SnapshotAccess.Root ->
                    "tar -cf $file -C ${shellQuote(requireDataDir(dataDir, packageName))} $excludes ."
            }
        return inNamespace(line, asRoot)
    }

    /**
     * Stops the app, empties its data folder but for its cache folders, which are emptied in place,
     * and unpacks [path] into it. As root, what came back is then given to the app's [uid] and
     * labelled like the folder itself. Not with restorecon: it labels from file_contexts and drops
     * the app's MLS categories (`app_data_file:s0`, not `s0:c87,c257,…`), and the app can then read
     * its own files but not write them. The `lib` link some apps keep is left alone.
     */
    fun restore(
        packageName: String,
        access: SnapshotAccess,
        dataDir: String,
        path: String,
        uid: Int,
        asRoot: Boolean,
    ): ShellCommand {
        val pkg = requirePackage(packageName)
        val file = shellQuote(place(path, asRoot))
        val clear =
            "find . -mindepth 1 -maxdepth 1 $KEPT -exec rm -rf {} + && " +
                "{ find ./cache ./code_cache -mindepth 1 -delete 2>/dev/null; true; }"
        val stop = "am force-stop $pkg"
        val line =
            when (access) {
                SnapshotAccess.RunAs ->
                    "$stop && cat $file | run-as $pkg sh -c ${shellQuote("$clear && tar -xf -")}"
                SnapshotAccess.Root -> {
                    require(uid >= FIRST_APP_UID) { "$uid isn't an app's uid" }
                    val dir = shellQuote(requireDataDir(dataDir, packageName))
                    val restored = "find . -mindepth 1 -maxdepth 1 $KEPT"
                    "$stop && cd $dir && { $clear; } && tar -xf $file -C $dir && " +
                        "ctx=\$(stat -c %C $dir) && " +
                        "$restored -exec chown -R $uid:$uid {} + -exec chcon -R \"\$ctx\" {} +"
                }
            }
        return inNamespace(line, asRoot)
    }

    /**
     * Root's own mount namespace, inherited from this app's, hides every other app's data folder
     * (Android 11's app data isolation): `ls /data/user/0/<package>` says it doesn't exist. Init's
     * namespace has them all, at their real paths.
     */
    private fun inNamespace(line: String, asRoot: Boolean): ShellCommand =
        if (asRoot) ShellCommand("nsenter -t 1 -m -- sh -c ${shellQuote(line)}")
        else ShellCommand(line)

    /**
     * Where the snapshot file is for whoever runs the line. Init's namespace has no
     * `/storage/emulated`; the same file is at `/data/media/<user>` there.
     */
    private fun place(path: String, asRoot: Boolean): String {
        require(path.endsWith(".tar") && ".." !in path) { "\"$path\" isn’t a snapshot's path" }
        if (!asRoot) return path
        val (user, rest) =
            sharedStorage.find(path)?.destructured
                ?: throw IllegalArgumentException("\"$path\" isn’t on shared storage")
        return "/data/media/$user/$rest"
    }

    private fun requirePackage(packageName: String): String {
        require(AppActions.isPackageName(packageName)) { "\"$packageName\" isn’t a package name" }
        return shellQuote(packageName)
    }

    /** Only the app's own data folder may be emptied: never anywhere a typo could reach. */
    private fun requireDataDir(dataDir: String, packageName: String): String {
        val (_, owner) =
            dataFolder.find(dataDir)?.destructured
                ?: throw IllegalArgumentException("\"$dataDir\" isn’t an app's data folder")
        require(owner == packageName) { "\"$dataDir\" isn’t $packageName's data folder" }
        return dataDir
    }

    private const val FIRST_APP_UID = 10_000

    /** What a restore leaves alone: the lib link, and the cache folders installd made. */
    private const val KEPT = "! -name lib ! -name cache ! -name code_cache"
}
