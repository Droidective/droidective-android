package com.droidective.platform

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SandboxDatabaseTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var file: File

    @Before
    fun makeDatabase() {
        file = File(context.cacheDir, "copy.db").apply { delete() }
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            db.execSQL("CREATE TABLE \"odd \"\"name\"\"\" (id INTEGER, note TEXT, data BLOB)")
            db.execSQL("INSERT INTO \"odd \"\"name\"\"\" VALUES (1, 'first', x'00010203')")
            db.execSQL("INSERT INTO \"odd \"\"name\"\"\" VALUES (2, NULL, NULL)")
            db.execSQL("CREATE TABLE many (n INTEGER)")
            repeat(SandboxDatabase.ROW_LIMIT + 5) { db.execSQL("INSERT INTO many VALUES ($it)") }
            db.execSQL("CREATE VIEW evens AS SELECT n FROM many WHERE n % 2 = 0")
        }
    }

    @Test
    fun listsTablesAndViewsWithTheirRowCounts() = runTest {
        val tables = SandboxDatabase.tables(file)
        assertEquals(listOf("evens", "many", "odd \"name\""), tables.map { it.name })
        assertTrue(tables.first { it.name == "evens" }.isView)
        assertEquals(SandboxDatabase.ROW_LIMIT + 5L, tables.first { it.name == "many" }.rows)
    }

    @Test
    fun aTableWhoseNameNeedsQuotingStillReads() = runTest {
        val rows = SandboxDatabase.rows(file, "odd \"name\"")
        assertEquals(listOf("id", "note", "data"), rows.columns)
        assertEquals(listOf("1", "first", "‹4 bytes›"), rows.rows[0])
        assertEquals(listOf("2", null, null), rows.rows[1])
    }

    @Test
    fun stopsAtTheRowLimitAndSaysThereAreMore() = runTest {
        val rows = SandboxDatabase.rows(file, "many")
        assertEquals(SandboxDatabase.ROW_LIMIT, rows.rows.size)
        assertTrue(rows.more)
        val few = SandboxDatabase.query(file, "SELECT n FROM many WHERE n < 3")
        assertEquals(listOf(listOf("0"), listOf("1"), listOf("2")), few.rows)
        assertEquals(false, few.more)
    }

    @Test
    fun theCopyIsOpenedReadOnly() = runTest {
        assertFailsWith<SQLiteException> { SandboxDatabase.query(file, "DELETE FROM many") }
        assertEquals(
            SandboxDatabase.ROW_LIMIT + 5L,
            SandboxDatabase.tables(file).first { it.name == "many" }.rows,
        )
    }
}
