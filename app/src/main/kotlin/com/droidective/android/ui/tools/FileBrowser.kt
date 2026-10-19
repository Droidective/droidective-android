package com.droidective.android.ui.tools

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.droidective.android.droidective
import com.droidective.android.ui.Symbols
import com.droidective.core.apps.Crumb
import com.droidective.core.apps.FileSource
import com.droidective.core.apps.Preference
import com.droidective.core.apps.PreferencesFile
import com.droidective.core.apps.Sandbox
import com.droidective.core.apps.SandboxEntry
import com.droidective.core.apps.SandboxListing
import com.droidective.core.shell.Parse
import com.droidective.core.shell.ShellCommand
import com.droidective.core.shell.ShellResult
import com.droidective.core.shell.ShellUnavailableException
import com.droidective.platform.DatabaseTable
import com.droidective.platform.QueryResult
import com.droidective.platform.SandboxDatabase
import java.io.File
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What an open file turned out to be, and what to show for it. */
private sealed interface Opened {
    val path: String

    data class Preferences(override val path: String, val values: List<Preference>) : Opened

    data class Database(
        override val path: String,
        val copy: File,
        val tables: List<DatabaseTable>,
    ) : Opened

    data class Text(override val path: String, val text: String, val cut: Boolean) : Opened

    data class Binary(override val path: String) : Opened

    data class Failed(override val path: String, val message: String) : Opened
}

/** A [FileSource], where its copies go, and how its errors read. */
class Files(
    val source: FileSource,
    /** This browser's own folder in this app's external files, for copies. */
    val copies: String,
    /** Turns what the shell said into a sentence, such as run-as refusing a release build. */
    val explain: (String) -> String = { it },
)

/**
 * A file browser over [files]: a breadcrumb trail, the folder at [path], and whatever file is open,
 * as preferences, a database, text or a note that it isn't text. Copies of what it opens are
 * deleted when it leaves the screen, since they can be another app's private data.
 */
@Composable
fun FileBrowser(files: Files, path: String, onPath: (String) -> Unit, tool: ToolContext) {
    val context = LocalContext.current
    var listing by remember { mutableStateOf<Result<List<SandboxEntry>>?>(null) }
    var opened by remember { mutableStateOf<Opened?>(null) }
    val scope = rememberCoroutineScope()

    DisposableEffect(files.copies) {
        onDispose { copiesFolder(context, files).listFiles()?.forEach { it.delete() } }
    }
    LaunchedEffect(files.source, path) {
        opened = null
        listing = null
        listing = runCatching { list(context, files, path) }
    }

    Breadcrumbs(files.source.crumbs(path)) {
        opened = null
        onPath(it)
    }
    when (val file = opened) {
        null ->
            Listing(listing) { entry ->
                val child = files.source.child(path, entry.name)
                if (entry.isDirectory) onPath(child)
                else
                    scope.launch {
                        val siblings = listing?.getOrNull().orEmpty().map { it.name }
                        opened = openOrSayWhy(context, files, child, siblings)
                    }
            }
        else -> OpenedFile(context, files, file, tool) { opened = null }
    }
}

/** Each step of the path, a tap away. */
@Composable
private fun Breadcrumbs(crumbs: List<Crumb>, onGo: (String) -> Unit) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        crumbs.forEach { crumb ->
            AssistChip(onClick = { onGo(crumb.path) }, label = { Text(crumb.label) })
        }
    }
}

@Composable
private fun Listing(listing: Result<List<SandboxEntry>>?, onOpen: (SandboxEntry) -> Unit) {
    var filter by rememberSaveable(listing) { mutableStateOf("") }
    when {
        listing == null -> ToolNote("Reading…")
        listing.isFailure -> ToolNote(listing.exceptionOrNull()?.message ?: "It couldn't be read.")
        listing.getOrThrow().isEmpty() -> ToolNote("This folder is empty.")
        else -> {
            val all = listing.getOrThrow()
            // /proc holds a folder per process: a long folder gets a filter, and draws a page.
            if (all.size > FILTER_FROM) {
                OutlinedTextField(
                    filter,
                    { filter = it },
                    placeholder = { Text("Filter ${all.size} names") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            val shown =
                all.filter { it.name.contains(filter.trim(), ignoreCase = true) }
                    .sortedWith(compareBy({ !it.isDirectory }, { it.name }))
            ToolSection("Files") {
                shown.take(PAGE).forEach { EntryRow(it) { onOpen(it) } }
                if (shown.size > PAGE) ToolNote("${shown.size - PAGE} more: filter to find them.")
                if (shown.isEmpty()) ToolNote("Nothing here is called that.")
            }
        }
    }
}

@Composable
private fun EntryRow(entry: SandboxEntry, onClick: () -> Unit) {
    val icon =
        when {
            entry.isDirectory -> "folder"
            entry.isLink -> "link"
            else -> "receipt_long"
        }
    Row(
        Modifier.fillMaxWidth().clickable(enabled = !entry.isLink, onClick = onClick).padding(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painterResource(Symbols.byName.getValue(icon).outlined), null, Modifier.size(20.dp))
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(entry.name, fontFamily = FontFamily.Monospace)
            Text(
                listOfNotNull(
                        entry.size?.takeIf { !entry.isDirectory }?.let(::bytes),
                        entry.modified,
                    )
                    .joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun OpenedFile(
    context: Context,
    files: Files,
    file: Opened,
    tool: ToolContext,
    onClose: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val save =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
            uri ?: return@rememberLauncherForActivityResult
            scope.launch { tool.say(saveCopy(context, files, file.path, uri)) }
        }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            file.path.substringAfterLast('/'),
            style = MaterialTheme.typography.titleMedium,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = { save.launch(file.path.substringAfterLast('/')) }) {
            Text("Save a copy")
        }
        TextButton(onClick = onClose) { Text("Close") }
    }
    when (file) {
        is Opened.Preferences -> PreferencesTable(file.values)
        is Opened.Database -> DatabaseView(file)
        is Opened.Text -> {
            if (file.cut) ToolNote("The first ${bytes(Sandbox.TEXT_LIMIT_BYTES.toLong())} of it.")
            ToolSection("Contents") {
                Text(
                    file.text,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                )
            }
        }
        is Opened.Binary -> ToolNote("This file isn't text. Save a copy to open it in another app.")
        is Opened.Failed -> ToolNote(file.message)
    }
}

@Composable
private fun PreferencesTable(values: List<Preference>) {
    if (values.isEmpty()) {
        ToolNote("No values saved in this file.")
        return
    }
    ToolSection("Values") {
        values.forEach { pref ->
            Column(Modifier.padding(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        pref.name,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        pref.type,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Text(
                    pref.value.ifEmpty { "(empty)" },
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun DatabaseView(file: Opened.Database) {
    var table by remember(file) { mutableStateOf<String?>(null) }
    var sql by rememberSaveable(file.path) { mutableStateOf("") }
    var result by remember(file) { mutableStateOf<Result<QueryResult>?>(null) }
    val scope = rememberCoroutineScope()
    fun run(block: suspend () -> QueryResult) {
        scope.launch { result = runCatching { block() } }
    }
    ToolSection("Tables") {
        if (file.tables.isEmpty()) ToolNote("No tables.")
        file.tables.forEach { t ->
            Row(
                Modifier.fillMaxWidth()
                    .clickable {
                        table = t.name
                        sql = "SELECT * FROM ${SandboxDatabase.quoteIdentifier(t.name)}"
                        run { SandboxDatabase.rows(file.copy, t.name) }
                    }
                    .padding(4.dp)
            ) {
                Text(t.name, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
                Text(
                    listOfNotNull(if (t.isView) "view" else null, t.rows?.let { "$it rows" })
                        .joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    OutlinedTextField(
        sql,
        { sql = it },
        label = { Text("Query (read-only)") },
        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedButton(
        enabled = sql.isNotBlank(),
        onClick = { run { SandboxDatabase.query(file.copy, sql) } },
    ) {
        Text("Run")
    }
    when (val now = result) {
        null -> if (table == null) ToolNote("Pick a table, or write a query.")
        else ->
            now.fold(
                onSuccess = { ResultGrid(it) },
                onFailure = { ToolNote(it.message ?: "The query failed.") },
            )
    }
}

/** Rows as a grid that scrolls sideways, every column as wide as its widest cell (up to a cap). */
@Composable
private fun ResultGrid(result: QueryResult) {
    if (result.rows.isEmpty()) {
        ToolNote("No rows.")
        return
    }
    val widths =
        result.columns.indices.map { column ->
            val longest =
                (listOf(result.columns[column]) + result.rows.map { it[column] ?: "NULL" }).maxOf {
                    it.length
                }
            (longest.coerceAtMost(MAX_CELL_CHARS) * CHAR_DP + CELL_PAD_DP).dp
        }
    ToolSection("${result.rows.size}${if (result.more) "+" else ""} rows") {
        Column(Modifier.horizontalScroll(rememberScrollState())) {
            GridRow(result.columns, widths, header = true)
            result.rows.forEach { row -> GridRow(row.map { it ?: "NULL" }, widths, header = false) }
        }
        if (result.more)
            ToolNote("The first ${SandboxDatabase.ROW_LIMIT}; narrow the query for the rest.")
    }
}

@Composable
private fun GridRow(
    cells: List<String>,
    widths: List<androidx.compose.ui.unit.Dp>,
    header: Boolean,
) {
    Row(Modifier.padding(vertical = 2.dp)) {
        cells.forEachIndexed { index, cell ->
            Text(
                cell.take(MAX_CELL_CHARS * 2),
                maxLines = 2,
                fontFamily = FontFamily.Monospace,
                style =
                    if (header) MaterialTheme.typography.labelMedium
                    else MaterialTheme.typography.bodySmall,
                color =
                    if (header) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.width(widths[index]).padding(end = 8.dp),
            )
        }
    }
}

private suspend fun list(context: Context, files: Files, path: String): List<SandboxEntry> {
    val result = runShell(context, files.source.list(path))
    if (!result.succeeded) error(why(result, files))
    return SandboxListing.parse(result.stdout)
}

/** [open], with anything that goes wrong said on screen rather than ending the app. */
private suspend fun openOrSayWhy(
    context: Context,
    files: Files,
    path: String,
    siblings: List<String>,
): Opened =
    try {
        open(context, files, path, siblings)
    } catch (e: CancellationException) {
        throw e
    } catch (
        @Suppress("TooGenericExceptionCaught") // A file someone picked: any failure is shown.
        e: Exception) {
        Opened.Failed(path, e.message ?: "It couldn't be opened.")
    }

private suspend fun open(
    context: Context,
    files: Files,
    path: String,
    siblings: List<String>,
): Opened {
    val header = runShell(context, files.source.header(path))
    if (Sandbox.isDatabaseHeader(header.stdout)) {
        val copy = copyOut(context, files, path, siblings)
        // A copy that silently wrote nothing would open as an empty database, which it isn't.
        if (withContext(Dispatchers.IO) { copy.length() } == 0L) error("The copy came out empty.")
        return Opened.Database(path, copy, SandboxDatabase.tables(copy))
    }
    val read = runShell(context, files.source.read(path))
    if (!read.succeeded) return Opened.Failed(path, why(read, files))
    val text = read.stdout
    if (Sandbox.isPreferences(path) || "/shared_prefs/" in path) {
        PreferencesFile.parse(text)?.let {
            return Opened.Preferences(path, it)
        }
    }
    if (text.any { it == '\u0000' || it == '�' }) return Opened.Binary(path)
    return Opened.Text(path, text, cut = text.length >= Sandbox.TEXT_LIMIT_BYTES)
}

/**
 * Copies a database and its write-ahead log to this app's external files, the one place both the
 * shell user and this app can reach, and returns the copy of the database itself.
 */
private suspend fun copyOut(
    context: Context,
    files: Files,
    path: String,
    siblings: List<String>,
): File {
    val folder = copiesFolder(context, files).apply { mkdirs() }
    withContext(Dispatchers.IO) { folder.listFiles()?.forEach { it.delete() } }
    val parts = Sandbox.databaseParts(path, siblings)
    for (part in parts) {
        val target = File(folder, part.substringAfterLast('/'))
        val result = runShell(context, files.source.copy(part, target.path))
        if (!result.succeeded) error(why(result, files))
    }
    return File(folder, path.substringAfterLast('/'))
}

/** Where copies go: this app's external files, which both the shell user and this app can reach. */
private fun copiesFolder(context: Context, files: Files): File =
    File(context.getExternalFilesDir(null), files.copies)

private suspend fun saveCopy(context: Context, files: Files, path: String, uri: Uri): String =
    try {
        val copy = copyOut(context, files, path, siblings = emptyList())
        withContext(Dispatchers.IO) {
            context.contentResolver.openOutputStream(uri)?.use { out ->
                copy.inputStream().use { it.copyTo(out) }
            } ?: error("The place you picked can't be written to.")
        }
        "Saved ${path.substringAfterLast('/')}"
    } catch (e: IllegalStateException) {
        e.message ?: "It couldn't be saved."
    }

/** The first thing the command said, through the source's own wording. */
private fun why(result: ShellResult, files: Files): String {
    val said =
        Parse.lines(result.stderr.ifBlank { result.stdout }).firstOrNull { it.isNotBlank() }?.trim()
    return files.explain(said ?: "It couldn't be read.")
}

private suspend fun runShell(context: Context, command: ShellCommand): ShellResult {
    val shell = context.droidective.access.shell() ?: error("Shell isn't running.")
    return try {
        shell.run(command)
    } catch (e: ShellUnavailableException) {
        error(e.message ?: "The shell stopped responding.")
    }
}

private fun bytes(size: Long): String =
    when {
        size < KB -> "$size B"
        size < KB * KB -> "%.1f KB".format(size / KB.toDouble())
        else -> "%.1f MB".format(size / (KB * KB).toDouble())
    }

private const val KB = 1024L
private const val FILTER_FROM = 30
private const val PAGE = 300
private const val MAX_CELL_CHARS = 32
private const val CHAR_DP = 7
private const val CELL_PAD_DP = 12
