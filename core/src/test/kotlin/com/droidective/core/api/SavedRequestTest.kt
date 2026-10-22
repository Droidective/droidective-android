package com.droidective.core.api

import com.droidective.core.net.proxy.RequestDraft
import kotlin.test.Test
import kotlin.test.assertEquals

class SavedRequestTest {
    private val menu =
        SavedRequest(
            "r1",
            "Menu",
            url = "https://{{host}}/stores/{{store}}/menu",
            headers = "Authorization: Bearer {{token}}",
        )

    @Test
    fun aSavedRequestIsSentWithItsVariablesFilledIn() {
        val values = mapOf("host" to "api.example.com", "store" to "7", "token" to "abc")
        assertEquals(
            RequestDraft(
                "GET",
                "https://api.example.com/stores/7/menu",
                "Authorization: Bearer abc",
                "",
            ),
            menu.draft(values),
        )
        assertEquals(emptyList(), menu.missing(values))
    }

    @Test
    fun whatItAsksForAndHasNoValueIsSaid() {
        assertEquals(listOf("store", "token"), menu.missing(mapOf("host" to "api.example.com")))
    }

    @Test
    fun aPastedCommandIsKeptAsItWasRead() {
        val draft = RequestDraft("POST", "https://a.example/x", "Accept: */*", "{}")
        assertEquals(
            SavedRequest("r2", "x", "POST", "https://a.example/x", "Accept: */*", "{}"),
            SavedRequest.of("r2", "x", draft),
        )
    }
}
