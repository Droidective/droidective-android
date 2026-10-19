package com.droidective.platform

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Rows a query returned: its columns, up to the first [SandboxDatabase.ROW_LIMIT] rows. */
data class QueryResult(val columns: List<String>, val rows: List<List<String?>>, val more: Boolean)

/** A table or view in a database, with how many rows it holds. */
data class DatabaseTable(val name: String, val isView: Boolean, val rows: Long?)

/**
 * Reads a copy of another app's database, opened read-only: the Sandbox Browser copies the file
 * (and its write-ahead log) out first, so nothing here can touch the app's own data.
 */
object SandboxDatabase {
    const val ROW_LIMIT = 200

    /** The tables and views in [file], Android's own bookkeeping tables left out. */
    suspend fun tables(file: File): List<DatabaseTable> =
        withContext(Dispatchers.IO) {
            open(file).use { db ->
                db.rawQuery(
                        "SELECT name, type FROM sqlite_master WHERE type IN ('table', 'view') " +
                            "AND name NOT IN ('android_metadata', 'sqlite_sequence') " +
                            "ORDER BY name",
                        null,
                    )
                    .use { cursor ->
                        buildList {
                            while (cursor.moveToNext()) {
                                val name = cursor.getString(0)
                                val isView = cursor.getString(1) == "view"
                                add(DatabaseTable(name, isView, count(db, name)))
                            }
                        }
                    }
            }
        }

    /** The first rows of [table]. */
    suspend fun rows(file: File, table: String): QueryResult =
        query(file, "SELECT * FROM ${quoteIdentifier(table)}")

    /** Runs [sql] against the read-only copy. Writes fail: the file is opened read-only. */
    suspend fun query(file: File, sql: String): QueryResult =
        withContext(Dispatchers.IO) { open(file).use { db -> db.rawQuery(sql, null).use(::read) } }

    private fun open(file: File): SQLiteDatabase =
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY)

    private fun count(db: SQLiteDatabase, table: String): Long? =
        try {
            db.rawQuery("SELECT COUNT(*) FROM ${quoteIdentifier(table)}", null).use {
                if (it.moveToFirst()) it.getLong(0) else null
            }
        } catch (_: SQLiteException) {
            // A view over a table that's gone, or a virtual table without its module.
            null
        }

    private fun read(cursor: Cursor): QueryResult {
        val rows = mutableListOf<List<String?>>()
        while (rows.size < ROW_LIMIT && cursor.moveToNext()) {
            rows += (0 until cursor.columnCount).map { cell(cursor, it) }
        }
        return QueryResult(cursor.columnNames.toList(), rows, more = cursor.moveToNext())
    }

    private fun cell(cursor: Cursor, column: Int): String? =
        when (cursor.getType(column)) {
            Cursor.FIELD_TYPE_NULL -> null
            Cursor.FIELD_TYPE_BLOB -> "‹${cursor.getBlob(column).size} bytes›"
            else -> cursor.getString(column)
        }

    /** A name as SQLite reads an identifier, whatever it contains. */
    fun quoteIdentifier(name: String): String = "\"" + name.replace("\"", "\"\"") + "\""
}
