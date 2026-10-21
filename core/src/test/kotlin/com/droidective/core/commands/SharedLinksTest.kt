package com.droidective.core.commands

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SharedLinksTest {
    @Test
    fun aLinkAloneIsTheLink() {
        assertEquals("myapp://checkout?id=42", SharedLinks.first("myapp://checkout?id=42"))
        assertEquals("https://example.com/a", SharedLinks.first("  https://example.com/a \n"))
    }

    @Test
    fun theLinkIsFoundInAMessage() {
        assertEquals(
            "https://shop.example.com/menu?store=7",
            SharedLinks.first("Try this: https://shop.example.com/menu?store=7 — fast now"),
        )
        // The first of several.
        assertEquals(
            "myapp://one",
            SharedLinks.first("myapp://one or myapp://two"),
        )
    }

    @Test
    fun theSentenceAroundItIsNotPartOfIt() {
        assertEquals("https://x.example/a", SharedLinks.first("See (https://x.example/a)."))
        assertEquals("https://x.example/a", SharedLinks.first("\"https://x.example/a\","))
        assertEquals("https://x.example/a", SharedLinks.first("“https://x.example/a”"))
        assertEquals("https://x.example/a", SharedLinks.first("<https://x.example/a>"))
    }

    @Test
    fun aBracketTheLinkOpenedStays() {
        assertEquals(
            "https://en.wikipedia.org/wiki/HAR_(file_format)",
            SharedLinks.first("https://en.wikipedia.org/wiki/HAR_(file_format)"),
        )
        assertEquals(
            "https://en.wikipedia.org/wiki/HAR_(file_format)",
            SharedLinks.first("(https://en.wikipedia.org/wiki/HAR_(file_format))."),
        )
    }

    @Test
    fun textWithNoLinkHasNone() {
        assertNull(SharedLinks.first(""))
        assertNull(SharedLinks.first("just some words, no link"))
        assertNull(SharedLinks.first("example.com without a scheme"))
        assertNull(SharedLinks.first("mailto:someone@example.com"))
    }
}
