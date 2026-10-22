package com.droidective.android

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

class NoticeIdsTest {
    @Test
    fun everyNoticeHasAnIdOfItsOwn() {
        assertEquals(NoticeIds.all.size, NoticeIds.all.toSet().size)
    }

    @Test
    fun noNoticeIdIsMadeUpAnywhereElse() {
        // A number written into a file of its own is how two notices came to share one.
        val written = Regex("""const val \w*ID = \d""")
        val strays =
            File("src/main/kotlin")
                .walk()
                .filter { it.extension == "kt" && it.name != "NoticeIds.kt" }
                .flatMap { file ->
                    file.readLines().mapIndexedNotNull { index, line ->
                        "${file.name}:${index + 1}".takeIf { written.containsMatchIn(line) }
                    }
                }
                .toList()
        assertEquals(emptyList(), strays)
    }
}
