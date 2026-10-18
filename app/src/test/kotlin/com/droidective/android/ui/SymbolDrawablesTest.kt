package com.droidective.android.ui

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.w3c.dom.Element

/**
 * Every imported symbol draws inside its own viewport. A symbol whose grid the importer guessed
 * wrong draws off the edge and shows as an empty space, which no other check notices.
 */
class SymbolDrawablesTest {
    private val android = "http://schemas.android.com/apk/res/android"
    private val firstMove = Regex("""^\s*[Mm]\s*(-?[0-9.]+)[\s,]*(-?[0-9.]+)""")

    @Test
    fun everySymbolStartsInsideItsViewport() {
        val files = File("src/main/res/drawable").listFiles { f -> f.name.startsWith("sym_") }
        assertTrue(
            !files.isNullOrEmpty(),
            "no symbol drawables found from ${File(".").absolutePath}",
        )
        val outside = files.filterNot { file ->
            val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            val vector = factory.newDocumentBuilder().parse(file).documentElement
            val width = vector.getAttributeNS(android, "viewportWidth").toDouble()
            val height = vector.getAttributeNS(android, "viewportHeight").toDouble()
            val group = vector.getElementsByTagName("group").item(0) as Element
            val dx = group.getAttributeNS(android, "translateX").toDoubleOrNull() ?: 0.0
            val dy = group.getAttributeNS(android, "translateY").toDoubleOrNull() ?: 0.0
            val path = group.getElementsByTagName("path").item(0) as Element
            val move =
                firstMove.find(path.getAttributeNS(android, "pathData")) ?: return@filterNot false
            val x = move.groupValues[1].toDouble() + dx
            val y = move.groupValues[2].toDouble() + dy
            x in 0.0..width && y in 0.0..height
        }
        assertEquals(emptyList(), outside.map { it.name }.sorted())
    }
}
