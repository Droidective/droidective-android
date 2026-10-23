package com.droidective.core.reactotron

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject

class ReactotronSnapshotsTest {
    private val dir = createTempDirectory("snapshots").toFile()
    private val shelf = ReactotronSnapshots(dir)
    private val cart =
        Json.parseToJsonElement("""{"cart":{"items":["tea"]},"user":{"name":"Ada"}}""")

    @AfterTest fun cleanUp() = dir.deleteRecursively().let {}

    @Test
    fun aBackupAnswerIsAStateToKeepAndARestorePutsItBack() {
        // As reactotron-redux 3.2.1 sends it, and as it asks to be given one back.
        val answer =
            ReactotronCommand.parse(
                """{"type":"state.backup.response","payload":{"state":{"cart":{"items":["tea"]},""" +
                    """"user":{"name":"Ada"}}}}"""
            )!!
        assertEquals(cart, ReactotronCommands.backedUp(answer))
        assertEquals(
            Json.parseToJsonElement(
                """{"state":{"cart":{"items":["tea"]},"user":{"name":"Ada"}}}"""
            ),
            ReactotronCommands.restore(cart),
        )
        val values = ReactotronCommand("state.values.response", JsonObject(mapOf("value" to cart)))
        assertNull(ReactotronCommands.backedUp(values), "only a backup answer is a snapshot")
        val nothing =
            ReactotronCommand("state.backup.response", JsonObject(mapOf("state" to JsonNull)))
        assertNull(ReactotronCommands.backedUp(nothing), "an app with no store gives nothing")
        assertNull(ReactotronCommands.backedUp(ReactotronCommand("state.backup.response")))
    }

    @Test
    fun aKeptStateComesBackAsItWasNewestFirst() {
        shelf.keep("StreamLab", "Empty cart", JsonObject(emptyMap()), takenAt = 1, id = "a")
        val kept = shelf.keep("StreamLab", "Tea in the cart", cart, takenAt = 2, id = "b")
        assertEquals(listOf("b", "a"), kept.map { it.id })
        assertEquals(kept, ReactotronSnapshots(dir).all(), "it survives the process")
        assertEquals(cart, shelf.state("b"))
        assertEquals(cart.toString().length.toLong(), kept.first().bytes)
        assertEquals("StreamLab", kept.first().app)
    }

    @Test
    fun renamingAndDeletingChangeOnlyThatOne() {
        shelf.keep("StreamLab", "One", cart, takenAt = 1, id = "a")
        shelf.keep("StreamLab", "Two", cart, takenAt = 2, id = "b")
        assertEquals(listOf("Two", "Logged in"), shelf.rename("a", "Logged in").map { it.name })
        assertEquals(listOf("a"), shelf.delete("b").map { it.id })
        assertNull(shelf.state("b"))
        assertEquals(setOf("a.json", "index.json"), dir.list()!!.toSet())
    }

    @Test
    fun onlyASnapshotTheIndexNamesIsRead() {
        shelf.keep("StreamLab", "One", cart, takenAt = 1, id = "a")
        File(dir.parentFile, "outside.json").apply { writeText("{}") }.deleteOnExit()
        assertNull(shelf.state("../outside"))
        assertNull(shelf.state("missing"))
        File(dir, "a.json").writeText("{ not json")
        assertNull(shelf.state("a"), "a state that won't read is no state")
    }

    @Test
    fun theOldestGoPastTwentyAndTheirFilesWithThem() {
        repeat(ReactotronSnapshots.MAX_COUNT + 2) { n ->
            shelf.keep("StreamLab", "#$n", cart, takenAt = n.toLong(), id = "s$n")
        }
        val kept = shelf.all()
        assertEquals(ReactotronSnapshots.MAX_COUNT, kept.size)
        assertEquals("s21", kept.first().id)
        assertEquals("s2", kept.last().id)
        assertTrue(File(dir, "s2.json").exists())
        assertTrue(!File(dir, "s1.json").exists() && !File(dir, "s0.json").exists())
    }

    @Test
    fun theBytesBoundDropsTheOldestButNeverTheNewest() {
        fun sized(id: String, megabytes: Long) =
            ReactotronSnapshot(id, id, "StreamLab", takenAt = 0, bytes = megabytes * MEGABYTE)
        assertEquals(
            listOf("c", "b"),
            ReactotronSnapshots.bounded(listOf(sized("c", 12), sized("b", 12), sized("a", 12)))
                .map { it.id },
        )
        assertEquals(
            listOf("huge"),
            ReactotronSnapshots.bounded(listOf(sized("huge", 40), sized("small", 1))).map { it.id },
        )
        assertEquals(
            listOf("c", "b", "a"),
            ReactotronSnapshots.bounded(listOf(sized("c", 16), sized("b", 15), sized("a", 1))).map {
                it.id
            },
        )
    }

    @Test
    fun aStrayFileFromACrashIsSweptOnTheNextKeep() {
        File(dir.apply { mkdirs() }, "orphan.json").writeText("{}")
        shelf.keep("StreamLab", "One", cart, takenAt = 1, id = "a")
        assertEquals(setOf("a.json", "index.json"), dir.list()!!.toSet())
    }

    private companion object {
        const val MEGABYTE = 1024L * 1024
    }
}
