package com.droidective.core.apps

import com.droidective.core.shell.Parse
import com.droidective.core.shell.ShellCommand
import com.droidective.core.shell.shellQuote
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.xml.sax.InputSource
import org.xml.sax.SAXException

/**
 * How the Sandbox Browser reaches an app's private files: `run-as` for a debuggable app, which the
 * shell user can do, or root for any app.
 */
sealed interface SandboxAccess {
    /** Inside the app's data folder through `run-as`, which starts there. */
    data object RunAs : SandboxAccess

    /**
     * As root, at the app's [dataDir] as Android reports it (`/data/user/0/…` for the first user),
     * never a path written here, which would be wrong for another user.
     */
    data class Root(val dataDir: String) : SandboxAccess {
        init {
            require(dataDir.startsWith("/data/") && Sandbox.isSafePath(dataDir.removePrefix("/"))) {
                "\"$dataDir\" isn’t an app's data folder"
            }
        }
    }
}

/** One entry of a folder listing. [size] is null for a device node, which has none. */
data class SandboxEntry(
    val name: String,
    val isDirectory: Boolean,
    val isLink: Boolean,
    val size: Long?,
    val modified: String,
)

/**
 * The commands behind the Sandbox Browser. A path is always relative to the app's data folder and
 * checked by [isSafePath] first, so a name can't climb out of it; every package and path is quoted.
 */
object Sandbox {
    /** The most of a file the text viewer reads. */
    const val TEXT_LIMIT_BYTES = 256 * 1024

    /** The first bytes of every SQLite database file. */
    const val SQLITE_HEADER = "SQLite format 3\u0000"

    /** A path below the data folder: no leading slash, no "..", nothing a shell would split on. */
    fun isSafePath(path: String): Boolean =
        !path.startsWith("/") &&
            path.split('/').none { it == ".." } &&
            path.none { it == '\u0000' || it == '\n' || it == '\r' }

    fun list(packageName: String, path: String, access: SandboxAccess): ShellCommand =
        command(packageName, access, "ls", "-la", path.ifEmpty { "." })

    /** The start of a file, up to [TEXT_LIMIT_BYTES]. */
    fun read(packageName: String, path: String, access: SandboxAccess): ShellCommand =
        command(packageName, access, "head", "-c", TEXT_LIMIT_BYTES.toString(), path)

    /**
     * Copies a file to [destination], a path the app can read, for what text can't carry: a
     * database opened with SQLite, or a file saved or shared. `run-as` runs as the app being
     * browsed, so the redirect is the shell user's, outside it.
     */
    fun copy(
        packageName: String,
        path: String,
        access: SandboxAccess,
        destination: String,
    ): ShellCommand {
        val read = command(packageName, access, "cat", path)
        return ShellCommand("${read.line} > ${shellQuote(destination)}")
    }

    /**
     * Runs [verb] on [path] in the app's data folder: inside it through `run-as`, which starts in
     * that folder, or with its full path as root.
     */
    private fun command(
        packageName: String,
        access: SandboxAccess,
        vararg verb: String,
    ): ShellCommand {
        require(AppActions.isPackageName(packageName)) { "\"$packageName\" isn’t a package name" }
        val path = verb.last()
        require(isSafePath(path)) { "\"$path\" isn’t a path inside the app" }
        val words = verb.dropLast(1)
        return when (access) {
            SandboxAccess.RunAs ->
                ShellCommand.of(
                    "run-as",
                    shellQuote(packageName),
                    *words.toTypedArray(),
                    shellQuote(path),
                )
            is SandboxAccess.Root ->
                ShellCommand.of(*words.toTypedArray(), shellQuote("${access.dataDir}/$path"))
        }
    }

    /** The files that make up the database at [path], for a copy that includes recent writes. */
    fun databaseParts(path: String, siblings: List<String>): List<String> {
        val name = path.substringAfterLast('/')
        val folder = path.substringBeforeLast('/', missingDelimiterValue = "")
        val prefix = if (folder.isEmpty()) "" else "$folder/"
        return listOf(name, "$name-wal", "$name-shm", "$name-journal")
            .filter { it == name || it in siblings }
            .map { prefix + it }
    }

    /** The first bytes of a file, enough to tell a database by its header. */
    fun header(packageName: String, path: String, access: SandboxAccess): ShellCommand =
        command(packageName, access, "head", "-c", "16", path)

    /**
     * Whether a file's first bytes are SQLite's: names don't say, since WorkManager's database is
     * `androidx.work.workdb` and Room's often have no extension at all.
     */
    fun isDatabaseHeader(start: String): Boolean = start.startsWith(SQLITE_HEADER.trimEnd('\u0000'))

    /** Whether [path] is a SharedPreferences file. */
    fun isPreferences(path: String): Boolean =
        path.startsWith("shared_prefs/") && path.endsWith(".xml")
}

/**
 * Parses toybox's `ls -la`: `drwxrwx--x 2 u0_a217 u0_a217 4096 2026-10-19 12:27 name`. The name is
 * everything after the time, so spaces in it survive; a link's target is dropped.
 */
object SandboxListing {
    private val line =
        Regex(
            """^([dlcbps-])[rwxsStT-]{9}\S*\s+\d+\s+\S+\s+\S+\s+(\d+|\d+,\s*\d+)\s+""" +
                """(\d{4}-\d{2}-\d{2} \d{2}:\d{2})\s(.+)$"""
        )

    fun parse(output: String): List<SandboxEntry> =
        Parse.lines(output).mapNotNull { raw ->
            val (type, size, modified, rest) =
                line.find(raw.trimEnd())?.destructured ?: return@mapNotNull null
            val name = if (type == "l") rest.substringBefore(" -> ") else rest
            if (name == "." || name == "..") return@mapNotNull null
            SandboxEntry(
                name = name,
                isDirectory = type == "d",
                isLink = type == "l",
                size = size.toLongOrNull(),
                modified = modified,
            )
        }
}

/** One SharedPreferences value, with the type Android wrote it as. */
data class Preference(val name: String, val type: String, val value: String)

/** Reads a SharedPreferences XML file, as Android's `XmlUtils` writes it. */
object PreferencesFile {
    /**
     * The values in [xml], in file order; null when it isn't a preferences file at all.
     *
     * Android writes no DOCTYPE, so a file with one is refused before parsing: that's what keeps a
     * declared entity from being fetched or expanded. A parser feature can't do it on both sides,
     * since Android's parser throws on the one the JVM's uses.
     */
    fun parse(xml: String): List<Preference>? {
        if (xml.contains("<!DOCTYPE", ignoreCase = true)) return null
        val document =
            try {
                DocumentBuilderFactory.newInstance()
                    .newDocumentBuilder()
                    .parse(InputSource(StringReader(xml)))
            } catch (_: SAXException) {
                return null
            } catch (_: java.io.IOException) {
                return null
            }
        val map = document.documentElement?.takeIf { it.tagName == "map" } ?: return null
        val children = map.childNodes
        return (0 until children.length).mapNotNull { children.item(it) as? Element }.map(::entry)
    }

    private fun entry(element: Element): Preference {
        val name = element.getAttribute("name")
        val value =
            when (element.tagName) {
                "string" -> element.textContent
                "set" -> {
                    val items = element.getElementsByTagName("string")
                    (0 until items.length).joinToString(", ") { items.item(it).textContent }
                }
                else -> element.getAttribute("value")
            }
        return Preference(name, element.tagName, value)
    }
}
