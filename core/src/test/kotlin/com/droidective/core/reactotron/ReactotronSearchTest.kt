package com.droidective.core.reactotron

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReactotronSearchTest {
    // As reactotron-core-client 2.10.0 sends apiResponse: bodies as text, headers as objects.
    private val call =
        entry(
            0,
            """{"type":"api.response","payload":{"duration":184,""" +
                """"request":{"url":"https://api.example.com/v1/orders","method":"POST",""" +
                """"data":"{\"items\":[\"tea\"],\"coupon\":\"SPRING10\"}",""" +
                """"headers":{"Authorization":"Bearer abc123"}},""" +
                """"response":{"status":200,"headers":{"x-request-id":"r-42"},""" +
                """"body":"{\"id\":991,\"status\":\"placed\"}"}}}""",
        )

    @Test
    fun aSearchFindsWhatAResponseOrRequestCarried() {
        listOf(
                "placed" to "a word in the response body",
                "\"status\":\"placed\"" to "the body as it reads",
                "SPRING10" to "the request body",
                "r-42" to "a response header's value",
                "authorization" to "a request header's name, in any case",
                "PLACED" to "any case",
            )
            .forEach { (query, what) ->
                assertTrue(ReactotronLabels.matches(call, null, query), what)
            }
        assertFalse(ReactotronLabels.matches(call, null, "refunded"))
    }

    @Test
    fun aSearchFindsAnyPayloadsValuesAndNamesButNotItsNulls() {
        val action =
            entry(
                1,
                """{"type":"state.action.complete","payload":{"name":"cart/add",""" +
                    """"action":{"type":"cart/add","item":{"sku":"TEA-7","gift":null}}}}""",
            )
        assertTrue(ReactotronLabels.matches(action, null, "tea-7"), "a nested value")
        assertTrue(ReactotronLabels.matches(action, null, "sku"), "a nested name")
        assertFalse(ReactotronLabels.matches(action, null, "null"), "null isn't text")
    }

    @Test
    fun theKindStillNarrowsIt() {
        assertTrue(ReactotronLabels.matches(call, ReactotronKind.Network, "placed"))
        assertFalse(ReactotronLabels.matches(call, ReactotronKind.Logs, "placed"))
    }

    @Test
    fun aGrowingTimelineFindsWhatAFreshSearchWould() {
        val timeline = ReactotronTimeline(maxEntries = 3)
        val search = ReactotronSearch(null, "tea")
        fun fresh() = timeline.snapshot().filter { ReactotronLabels.matches(it, null, "tea") }
        listOf("green tea", "coffee", "tea time", "juice", "iced tea", "water").forEach { said ->
            timeline.add(0, 1, "ShopApp", log(said), 10)
            assertEquals(fresh(), search.filter(timeline.snapshot()), "after \"$said\"")
        }
        assertEquals(
            listOf("iced tea"),
            search.filter(timeline.snapshot()).map { ReactotronLabels.summary(it.event) },
        )
        timeline.clear()
        assertEquals(emptyList(), search.filter(timeline.snapshot()))
    }

    private fun entry(id: Long, json: String): ReactotronTimeline.Entry {
        val command = ReactotronCommand.parse(json)!!
        return ReactotronTimeline.Entry(
            id,
            0,
            1,
            "ShopApp",
            command,
            ReactotronEvent.of(command),
            0,
        )
    }

    private fun log(text: String) =
        ReactotronCommand.parse(
            """{"type":"log","payload":{"level":"debug","message":"$text"}}"""
        )!!
}
