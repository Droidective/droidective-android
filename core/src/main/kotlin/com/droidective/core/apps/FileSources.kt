package com.droidective.core.apps

import com.droidective.core.shell.ShellCommand
import com.droidective.core.shell.shellQuote

/** One step of the path shown above a listing: what it says, and where it goes. */
data class Crumb(val label: String, val path: String)

/**
 * Where a file browser reads from: the commands it runs for a path inside it. The Sandbox Browser
 * reads one app's data folder; the File Explorer, the whole phone.
 */
interface FileSource {
    /** The path the browser opens at. */
    val start: String

    fun list(path: String): ShellCommand

    /** The first bytes of a file, enough to tell a database by its header. */
    fun header(path: String): ShellCommand

    /** The start of a file, up to [Sandbox.TEXT_LIMIT_BYTES]. */
    fun read(path: String): ShellCommand

    /** Copies a file to [destination], a path this app can read. */
    fun copy(path: String, destination: String): ShellCommand

    fun child(path: String, name: String): String

    fun crumbs(path: String): List<Crumb>
}

/** One app's data folder, through `run-as` or root ([Sandbox]). Paths are relative to it. */
class SandboxSource(private val packageName: String, private val access: SandboxAccess) :
    FileSource {
    override val start = ""

    override fun list(path: String) = Sandbox.list(packageName, path, access)

    override fun header(path: String) = Sandbox.header(packageName, path, access)

    override fun read(path: String) = Sandbox.read(packageName, path, access)

    override fun copy(path: String, destination: String) =
        Sandbox.copy(packageName, path, access, destination)

    override fun child(path: String, name: String) = if (path.isEmpty()) name else "$path/$name"

    override fun crumbs(path: String): List<Crumb> {
        val parts = path.split('/').filter { it.isNotEmpty() }
        return listOf(Crumb("data", "")) +
            parts.indices.map { Crumb(parts[it], parts.take(it + 1).joinToString("/")) }
    }
}

/**
 * The whole phone, as the shell user or root sees it, at absolute paths. A path is built only from
 * names a listing gave, each checked by [isName], and always quoted.
 */
class PhoneFiles(override val start: String) : FileSource {
    init {
        require(isPath(start)) { "\"$start\" isn’t an absolute path" }
    }

    override fun list(path: String) = ShellCommand.of("ls", "-la", quoted(path))

    override fun header(path: String) = ShellCommand.of("head", "-c", "16", quoted(path))

    override fun read(path: String) =
        ShellCommand.of("head", "-c", Sandbox.TEXT_LIMIT_BYTES.toString(), quoted(path))

    override fun copy(path: String, destination: String) =
        ShellCommand("cat ${quoted(path)} > ${shellQuote(destination)}")

    override fun child(path: String, name: String): String {
        require(isName(name)) { "\"$name\" isn’t a file name" }
        return if (path == "/") "/$name" else "$path/$name"
    }

    override fun crumbs(path: String): List<Crumb> {
        val parts = path.split('/').filter { it.isNotEmpty() }
        return listOf(Crumb("/", "/")) +
            parts.indices.map { Crumb(parts[it], "/" + parts.take(it + 1).joinToString("/")) }
    }

    private fun quoted(path: String): String {
        require(isPath(path)) { "\"$path\" isn’t an absolute path" }
        return shellQuote(path)
    }

    companion object {
        /** An absolute path with nothing a shell or `ls` would split on, and no climbing back. */
        fun isPath(path: String): Boolean =
            path.startsWith("/") &&
                path.split('/').none { it == ".." } &&
                path.none { it == '\u0000' || it == '\n' || it == '\r' }

        /** One name inside a folder: no slash, not "." or "..". */
        fun isName(name: String): Boolean =
            name.isNotEmpty() && '/' !in name && name != "." && name != ".." && isPath("/$name")
    }
}
