package com.droidective.core.reactotron

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

class ReactotronTimelineTest {
    @Test
    fun theOldestGoWhenThereAreTooMany() {
        val timeline = ReactotronTimeline(maxEntries = 3)
        repeat(5) { timeline.add(it.toLong(), 1, "A", log("line $it"), 10) }
        assertEquals(listOf("line 2", "line 3", "line 4"), timeline.snapshot().map(::message))
    }

    @Test
    fun theOldestGoWhenTheyAreTooLarge() {
        val timeline = ReactotronTimeline(maxBytes = 100)
        timeline.add(0, 1, "A", log("small"), 30)
        timeline.add(1, 1, "A", log("small too"), 30)
        timeline.add(2, 1, "A", log("large"), 60)
        assertEquals(listOf("small too", "large"), timeline.snapshot().map(::message))
        assertEquals(90, timeline.bytes())
        // One alone past the budget is still kept: the newest thing said is never dropped.
        timeline.add(3, 1, "A", log("huge"), 500)
        assertEquals(listOf("huge"), timeline.snapshot().map(::message))
    }

    @Test
    fun anAppClearingItsTimelineClearsThisOne() {
        val timeline = ReactotronTimeline()
        timeline.add(0, 1, "A", log("before"), 10)
        timeline.add(1, 1, "A", ReactotronCommand("clear"), 0)
        timeline.add(2, 1, "A", log("after"), 10)
        assertEquals(listOf("after"), timeline.snapshot().map(::message))
        assertEquals(10, timeline.bytes())
    }

    @Test
    fun aConnectionGoingIsALineOfItsOwn() {
        val timeline = ReactotronTimeline()
        timeline.gone(5, 2, "StreamLab", "the app went away: reloaded, or quit")
        val entry = timeline.snapshot().single()
        val event = assertIs<ReactotronEvent.Unknown>(entry.event)
        assertEquals(ReactotronTimeline.GONE, event.type)
        assertEquals("GONE", ReactotronLabels.tag(event))
        assertEquals("the app went away: reloaded, or quit", ReactotronLabels.summary(event))
    }

    @Test
    fun everyChangeMovesTheRevision() {
        val timeline = ReactotronTimeline()
        val before = timeline.revision
        timeline.add(0, 1, null, log("x"), 1)
        timeline.clear()
        assertEquals(before + 2, timeline.revision)
        assertTrue(timeline.snapshot().isEmpty())
    }

    @Test
    fun entriesAreFoundByKindAndByWhatTheySay() {
        val timeline = ReactotronTimeline()
        timeline.add(0, 1, "StreamLab", log("payment failed"), 10)
        timeline.add(1, 1, "StreamLab", api("GET", "https://api.example.com/menu", 503), 10)
        timeline.add(
            2,
            1,
            "StreamLab",
            ReactotronCommand(
                "state.action.complete",
                JsonObject(mapOf("name" to JsonPrimitive("ADD_ITEM"))),
            ),
            10,
        )
        val (log, call, action) = timeline.snapshot()
        assertEquals(ReactotronKind.Logs, ReactotronLabels.kind(log.event))
        assertEquals(ReactotronKind.Network, ReactotronLabels.kind(call.event))
        assertEquals(ReactotronKind.State, ReactotronLabels.kind(action.event))
        assertTrue(ReactotronLabels.matches(call, ReactotronKind.Network, "menu"))
        assertFalse(ReactotronLabels.matches(call, ReactotronKind.Logs, "menu"))
        assertTrue(ReactotronLabels.matches(log, null, "PAYMENT"))
        assertTrue(ReactotronLabels.matches(action, null, "add_item"))
        assertTrue(ReactotronLabels.matches(call, null, "503"), "by its tag, the status")
        assertTrue(ReactotronLabels.matches(log, null, "streamlab"), "by its app")
        assertEquals("503", ReactotronLabels.tag(call.event))
        assertEquals(
            "GET https://api.example.com/menu · 12 ms",
            ReactotronLabels.summary(call.event),
        )
    }

    @Test
    fun aRequestWithNoAnswerSaysItFailed() {
        val event = ReactotronEvent.of(api("POST", "https://api.example.com/pay", 0))
        assertEquals("FAILED", ReactotronLabels.tag(event))
        assertEquals("1.5 s", ReactotronLabels.millis(1_500.0))
    }

    private fun log(text: String) =
        ReactotronCommand("log", JsonObject(mapOf("message" to JsonPrimitive(text))))

    private fun api(method: String, url: String, status: Int) =
        ReactotronCommand(
            "api.response",
            JsonObject(
                mapOf(
                    "duration" to JsonPrimitive(12),
                    "request" to
                        JsonObject(
                            mapOf("method" to JsonPrimitive(method), "url" to JsonPrimitive(url))
                        ),
                    "response" to JsonObject(mapOf("status" to JsonPrimitive(status))),
                )
            ),
        )

    private fun message(entry: ReactotronTimeline.Entry) =
        (entry.event as ReactotronEvent.Log).message
}
