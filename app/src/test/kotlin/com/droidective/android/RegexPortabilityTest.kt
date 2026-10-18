package com.droidective.android

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Android compiles regular expressions with ICU, which rejects a bare `}` or a `{` that doesn't
 * start a quantifier; the JVM running these tests accepts both. A bare `}` in the placeholder
 * pattern once crashed every settings toggle on a phone while every test passed. This holds each
 * regex literal in the app's sources to the stricter rule.
 */
class RegexPortabilityTest {
    private val sources = listOf("../core/src/main", "../platform/src/main", "src/main")
    private val rawLiteral = Regex("Regex\\(\\s*\"\"\"(.*?)\"\"\"", RegexOption.DOT_MATCHES_ALL)
    private val plainLiteral = Regex("Regex\\(\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
    private val quantifier = Regex("""\{\d+(,\d*)?\}""")

    @Test
    fun everyRegexLiteralCompilesUnderIcuRules() {
        val patterns =
            sources
                .flatMap { root -> File(root).walk().filter { it.extension == "kt" }.toList() }
                .flatMap { file ->
                    val text = file.readText()
                    rawLiteral.findAll(text).map { it.groupValues[1] } +
                        plainLiteral.findAll(text).map { unescape(it.groupValues[1]) }
                }
        assertTrue(patterns.size >= 8, "found ${patterns.size} patterns; the scan needs updating")
        assertEquals(emptyList(), patterns.filterNot(::icuSafe))
    }

    @Test
    fun theCheckRejectsWhatIcuRejects() {
        assertTrue(icuSafe("""\{([a-z_]+)\}"""))
        assertTrue(icuSafe("""[a-z]{0,63}[}{\]]"""))
        assertTrue(!icuSafe("""\{([a-z_]+)}"""))
        assertTrue(!icuSafe("""a{b"""))
        assertTrue(!icuSafe("""^\[(.*)]$"""))
    }

    private fun unescape(kotlin: String) =
        kotlin.replace("\\\\", "\u0000").replace("\\\"", "\"").replace("\u0000", "\\")

    /** Outside a [...] set, braces are quantifiers or escaped, and `]` is always escaped. */
    private fun icuSafe(pattern: String): Boolean {
        var i = 0
        var inSet = false
        while (i < pattern.length) {
            val c = pattern[i]
            when {
                c == '\\' -> i++
                inSet -> if (c == ']') inSet = false
                c == '[' -> inSet = true
                c == '{' -> {
                    val match = quantifier.matchAt(pattern, i) ?: return false
                    i = match.range.last
                }
                c == '}' || c == ']' -> return false
            }
            i++
        }
        return true
    }
}
