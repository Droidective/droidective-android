package com.droidective.core.apps

import kotlin.test.Test
import kotlin.test.assertEquals

class ApkContentsTest {
    private val entries =
        listOf(
            record("AndroidManifest.xml", size = 28_984, packed = 5_542),
            record("classes.dex", size = 19_000_000, stored = true),
            record("classes2.dex", size = 35_232, stored = true),
            record("resources.arsc", size = 714_224, stored = true),
            record("res/drawable/a.xml", size = 900, packed = 400),
            record("res/drawable/b.png", size = 20_000, stored = true),
            record("res/layout/main.xml", size = 3_000, packed = 1_000),
            record("lib/arm64-v8a/libapp.so", size = 10_096, stored = true),
            record("lib/x86_64/libapp.so", size = 10_760, stored = true),
        )

    @Test
    fun theTopAddsUpEachFolderAndPutsTheBiggestFirst() {
        assertEquals(
            listOf(
                ApkNode("classes.dex", "classes.dex", false, 19_000_000, 19_000_000, 1, true),
                ApkNode("resources.arsc", "resources.arsc", false, 714_224, 714_224, 1, true),
                ApkNode("classes2.dex", "classes2.dex", false, 35_232, 35_232, 1, true),
                ApkNode("res", "res", true, 21_400, 23_900, 3, false),
                ApkNode("lib", "lib", true, 20_856, 20_856, 2, false),
                ApkNode(
                    "AndroidManifest.xml",
                    "AndroidManifest.xml",
                    false,
                    5_542,
                    28_984,
                    1,
                    false,
                ),
            ),
            ApkContents.list(entries, ""),
        )
    }

    @Test
    fun aFolderListsWhatIsDirectlyInIt() {
        assertEquals(
            listOf("drawable" to 20_400L, "layout" to 1_000L),
            ApkContents.list(entries, "res").map { it.name to it.packed },
        )
        assertEquals(
            listOf("res/drawable/b.png", "res/drawable/a.xml"),
            ApkContents.list(entries, "res/drawable").map { it.path },
        )
    }

    @Test
    fun aFolderIsntAPrefixOfAFileName() {
        // "res" holds res/…, never resources.arsc.
        assertEquals(3, ApkContents.list(entries, "res").sumOf { it.files })
        assertEquals(emptyList(), ApkContents.list(entries, "classes.dex"))
        assertEquals(emptyList(), ApkContents.list(entries, "nowhere"))
    }

    @Test
    fun equalSizesGoByName() {
        val same = listOf(record("b", 10, stored = true), record("a", 10, stored = true))
        assertEquals(listOf("a", "b"), ApkContents.list(same, "").map { it.name })
    }

    @Test
    fun sharesToATenthOfAPercent() {
        assertEquals("38.2%", ApkContents.share(382, 1000))
        assertEquals("100.0%", ApkContents.share(5, 5))
        assertEquals("0.1%", ApkContents.share(1, 1000))
        assertEquals("20.0%", ApkContents.share(1999, 10_000), "rounded, not cut")
        assertEquals("0.1%", ApkContents.share(1, 1999))
        assertEquals("under 0.1%", ApkContents.share(1, 2001))
        assertEquals("0%", ApkContents.share(0, 1000))
        assertEquals("0%", ApkContents.share(10, 0))
        assertEquals("33.3%", ApkContents.share(4_000_000_000L, 12_000_000_000L))
    }

    @Test
    fun crumbsLeadFromTheTop() {
        assertEquals(
            listOf(Crumb("base.apk", ""), Crumb("res", "res"), Crumb("drawable", "res/drawable")),
            ApkContents.crumbs("base.apk", "res/drawable"),
        )
        assertEquals(listOf(Crumb("base.apk", "")), ApkContents.crumbs("base.apk", ""))
    }

    private fun record(name: String, size: Long, packed: Long = size, stored: Boolean = false) =
        ZipRecord(name, size, packed, stored, headerOffset = 0)
}
