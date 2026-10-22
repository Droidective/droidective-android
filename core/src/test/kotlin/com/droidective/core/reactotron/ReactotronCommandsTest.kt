package com.droidective.core.reactotron

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

class ReactotronCommandsTest {
    private val commands = ReactotronCommands()

    @Test
    fun aRegisteredCommandIsOffered() {
        assertTrue(
            commands.heard(
                1,
                command(
                    """{"type":"customCommand.register","payload":{"id":1,"command":"resetStore",
                       "title":"Reset store","description":"Back to a fresh install",
                       "args":[{"name":"user","type":"string"}]}}"""
                ),
            )
        )
        val offered = commands.all().single()
        assertEquals("resetStore", offered.name)
        assertEquals("Reset store", offered.label)
        assertEquals("Back to a fresh install", offered.description)
        assertEquals(listOf("user"), offered.args)
    }

    @Test
    fun aCommandWithNoTitleIsLabelledByItsName() {
        commands.heard(
            1,
            command("""{"type":"customCommand.register","payload":{"id":2,"command":"ping"}}"""),
        )
        assertEquals("ping", commands.all().single().label)
    }

    @Test
    fun anUnregisteredCommandAndAGoneConnectionTakeTheirsAway() {
        commands.heard(
            1,
            command("""{"type":"customCommand.register","payload":{"id":1,"command":"a"}}"""),
        )
        commands.heard(
            1,
            command("""{"type":"customCommand.register","payload":{"id":2,"command":"b"}}"""),
        )
        commands.heard(
            2,
            command("""{"type":"customCommand.register","payload":{"id":1,"command":"c"}}"""),
        )
        commands.heard(
            1,
            command("""{"type":"customCommand.unregister","payload":{"id":1,"command":"a"}}"""),
        )
        assertEquals(listOf("b", "c"), commands.all().map { it.name })
        commands.gone(1)
        assertEquals(listOf("c"), commands.all().map { it.name })
    }

    @Test
    fun anythingElseIsNotAboutCommands() {
        assertFalse(commands.heard(1, command("""{"type":"log","payload":{"message":"hi"}}""")))
        // A register with no command name registers nothing.
        assertFalse(
            commands.heard(1, command("""{"type":"customCommand.register","payload":{"id":3}}"""))
        )
        assertTrue(commands.all().isEmpty())
    }

    @Test
    fun runningOneSendsItsNameAndEveryArgument() {
        commands.heard(
            1,
            command(
                """{"type":"customCommand.register","payload":{"id":1,"command":"login",
                   "args":[{"name":"user"},{"name":"code"}]}}"""
            ),
        )
        val payload = ReactotronCommands.run(commands.all().single(), mapOf("user" to "ada"))
        // An argument left empty is sent empty, as the desktop does, not left out.
        assertEquals(
            Json.parseToJsonElement("""{"command":"login","args":{"user":"ada","code":""}}"""),
            payload,
        )
    }

    @Test
    fun askingForStateNamesThePathAndWhetherKeysOrTheValue() {
        val (type, payload) = ReactotronCommands.stateRequest(" user.profile ", keys = false)
        assertEquals("state.values.request", type)
        assertEquals("""{"path":"user.profile"}""", payload.toString())
        assertEquals("state.keys.request", ReactotronCommands.stateRequest("", keys = true).first)
    }

    @Test
    fun theAnswersReadAsStateAnswers() {
        val value =
            assertIs<ReactotronEvent.StateAnswer>(
                ReactotronEvent.of(
                    command(
                        """{"type":"state.values.response","payload":{"path":"user","value":{"name":"Ada"},"valid":true}}"""
                    )
                )
            )
        assertEquals("user", value.path)
        assertFalse(value.keys)
        assertIs<JsonObject>(value.value)
        assertEquals("VALUE", ReactotronLabels.tag(value))
        val keys =
            assertIs<ReactotronEvent.StateAnswer>(
                ReactotronEvent.of(
                    command(
                        """{"type":"state.keys.response","payload":{"path":"","keys":["user","cart"]}}"""
                    )
                )
            )
        assertTrue(keys.keys)
        assertEquals("KEYS", ReactotronLabels.tag(keys))
        assertEquals("the whole store", ReactotronLabels.summary(keys))
        assertEquals(ReactotronKind.State, ReactotronLabels.kind(keys))
    }

    private fun command(text: String) = ReactotronCommand.parse(text)!!
}
