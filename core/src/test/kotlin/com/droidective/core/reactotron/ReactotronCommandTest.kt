package com.droidective.core.reactotron

import com.droidective.core.reactotron.ReactotronEvent.Log.Level
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

class ReactotronCommandTest {
    @Test
    fun theEnvelopeIsReadAsItCame() {
        val command =
            parse(
                """{"type":"log","payload":{"message":"hi"},"important":true,
                   "date":"2026-10-22T10:00:00.000Z","deltaTime":12}"""
            )
        assertEquals("log", command.type)
        assertTrue(command.important)
        assertEquals("2026-10-22T10:00:00.000Z", command.date)
        assertEquals(12.0, command.deltaTime)
    }

    @Test
    fun theClientsMarkersAreUndone() {
        val command =
            parse(
                """{"type":"display","payload":{"a":"~~~ null ~~~","b":"~~~ undefined ~~~",
                   "c":"~~~ false ~~~","d":"~~~ zero ~~~","e":"~~~ empty string ~~~",
                   "f":"~~~ onPress() ~~~","g":["~~~ False ~~~"],"h":"~~~ not a marker"},
                   "important":"~~~ false ~~~"}"""
            )
        val payload = command.payload
        assertEquals(JsonNull, payload["a"])
        assertEquals(JsonNull, payload["b"])
        assertEquals(JsonPrimitive(false), payload["c"])
        assertEquals(JsonPrimitive(0), payload["d"])
        assertEquals(JsonPrimitive(""), payload["e"])
        assertEquals(JsonPrimitive("onPress()"), payload["f"])
        assertEquals(JsonPrimitive(false), payload["g"].list.single())
        assertEquals(JsonPrimitive("~~~ not a marker"), payload["h"])
        assertFalse(command.important)
    }

    @Test
    fun whatIsNotACommandIsNothing() {
        assertNull(ReactotronCommand.parse("not json"))
        assertNull(ReactotronCommand.parse("[1,2,3]"))
        assertEquals("", parse("{}").type)
    }

    @Test
    fun anIntroNamesTheApp() {
        val intro =
            event(
                """{"type":"client.intro","payload":{"name":"StreamLab","platform":"android",
                   "environment":"development","reactotronCoreClientVersion":"2.9.4"}}"""
            )
        assertEquals(
            ReactotronEvent.ClientIntro("StreamLab", "android", "development", "2.9.4"),
            intro,
        )
    }

    @Test
    fun aLogsObjectMessageReadsAsItsContent() {
        val log =
            assertIs<ReactotronEvent.Log>(
                event(
                    """{"type":"log","payload":{"level":"error","message":{"user":7},
                       "stack":[{"fileName":"App.tsx","functionName":"load","lineNumber":12},
                       "at somewhere"]}}"""
                )
            )
        assertEquals(Level.Error, log.level)
        assertEquals("""{"user":7}""", log.message)
        assertEquals(ReactotronEvent.Frame("App.tsx", "load", 12, null), log.stack[0])
        assertEquals("at somewhere", log.stack[1].fileName)
    }

    @Test
    fun aRequestThatNeverGotAnAnswerHasStatusZero() {
        val api =
            assertIs<ReactotronEvent.ApiResponse>(
                event(
                    """{"type":"api.response","payload":{"duration":42.5,
                       "request":{"method":"post","url":"https://api.example.com/orders"},
                       "response":{}}}"""
                )
            )
        assertEquals("POST", api.method)
        assertEquals("https://api.example.com/orders", api.url)
        assertEquals(0, api.status)
        assertEquals(42.5, api.durationMillis)
    }

    @Test
    fun theRestReadAsTheirKinds() {
        val bench =
            assertIs<ReactotronEvent.Benchmark>(
                event(
                    """{"type":"benchmark.report","payload":{"title":"boot",
                       "steps":[{"title":"start","time":0,"delta":0},{"title":"ready","time":40,"delta":40}]}}"""
                )
            )
        assertEquals(listOf("start", "ready"), bench.steps.map { it.title })
        val action =
            assertIs<ReactotronEvent.StateAction>(
                event("""{"type":"state.action.complete","payload":{"name":"ADD","ms":3}}""")
            )
        assertEquals("ADD", action.name)
        assertEquals(ReactotronEvent.Clear, event("""{"type":"clear"}"""))
        val change =
            assertIs<ReactotronEvent.StateChange>(
                event(
                    """{"type":"state.values.change","payload":{"changes":[{"path":"user.name","value":"Ada"}]}}"""
                )
            )
        assertEquals("user.name" to JsonPrimitive("Ada"), change.changes.single())
        val unknown =
            assertIs<ReactotronEvent.Unknown>(event("""{"type":"storybook","payload":true}"""))
        assertEquals("storybook", unknown.type)
    }

    private fun parse(text: String) = assertNotNull(ReactotronCommand.parse(text))

    private fun event(text: String) = ReactotronEvent.of(parse(text))
}
