package com.droidective.core.api

import kotlin.test.Test
import kotlin.test.assertEquals

class VariablesTest {
    private val values = mapOf("host" to "staging.example.com", "token" to "abc")

    @Test
    fun namesAreFilledInWhereverTheyAre() {
        assertEquals(
            "https://staging.example.com/menu?k=abc",
            Variables.fill("https://{{host}}/menu?k={{ token }}", values),
        )
    }

    @Test
    fun aNameWithNoValueIsLeftAndSaid() {
        val text = "https://{{host}}/{{version}}/menu"
        assertEquals("https://staging.example.com/{{version}}/menu", Variables.fill(text, values))
        assertEquals(
            listOf("version"),
            Variables.missing(listOf(text, "Authorization: {{token}}"), values),
        )
    }

    @Test
    fun namesAreListedOnceInOrder() {
        assertEquals(listOf("b", "a"), Variables.names("{{b}} {{a}} {{b}} { {not} } {{}}"))
    }

    @Test
    fun aValueIsNeverItselfFilledIn() {
        assertEquals(
            "{{token}}",
            Variables.fill("{{loop}}", mapOf("loop" to "{{token}}", "token" to "x")),
        )
    }
}
