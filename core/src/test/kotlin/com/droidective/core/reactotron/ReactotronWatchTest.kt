package com.droidective.core.reactotron

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

class ReactotronWatchTest {
    private val watch = ReactotronWatch()

    // As reactotron-redux 3.2.1 sends them: every watched value, on subscribing and on each change.
    private fun change(json: String) =
        ReactotronCommand.parse("""{"type":"state.values.change","payload":{"changes":$json}}""")!!

    @Test
    fun anAppsLatestChangeIsWhatItsWatchedPathsHold() {
        assertTrue(watch.heard(1, change("""[{"path":"cart.items","value":["tea"]}]""")))
        assertTrue(watch.heard(1, change("""[{"path":"cart.items","value":["tea","cake"]}]""")))
        assertTrue(watch.heard(2, change("""[{"path":"user.name","value":"Ada"}]""")))
        assertEquals(
            mapOf(
                1 to listOf("cart.items" to Json.parseToJsonElement("""["tea","cake"]""")),
                2 to listOf("user.name" to JsonPrimitive("Ada")),
            ),
            watch.all(),
        )
        watch.gone(1)
        assertEquals(setOf(2), watch.all().keys)
    }

    @Test
    fun aStarredPathComesBackAsAValueForEachKeyAndAMissingOneAsNothing() {
        watch.heard(
            1,
            change(
                """[{"path":"user.name","value":"Ada"},{"path":"user.plan","value":"pro"},""" +
                    """{"path":"basket","value":"~~~ undefined ~~~"}]"""
            ),
        )
        assertEquals(
            listOf(
                "user.name" to JsonPrimitive("Ada"),
                "user.plan" to JsonPrimitive("pro"),
                "basket" to JsonNull,
            ),
            watch.all().getValue(1),
        )
    }

    @Test
    fun onlyWhatAWatchedPathAnswersIsShown() {
        val watched = listOf("cart.items", "user.*")
        listOf("cart.items", "user.name", "user.plan", "user").forEach {
            assertTrue(ReactotronWatch.answers(it, watched), it)
        }
        listOf("cart", "cart.items.0", "username", "users.name", "user2.name").forEach {
            assertFalse(ReactotronWatch.answers(it, watched), it)
        }
        assertFalse(ReactotronWatch.answers("cart.items", emptyList()), "no longer watched")
    }

    @Test
    fun eachWatchedPathGetsWhatTheConnectedAppsSayItHolds() {
        val values =
            mapOf(
                1 to
                    listOf(
                        "cart.items" to JsonPrimitive("tea"),
                        "user.name" to JsonPrimitive("Ada"),
                        "user.plan" to JsonPrimitive("pro"),
                    ),
                2 to listOf("cart.items" to JsonPrimitive("cake")),
                3 to listOf("cart.items" to JsonPrimitive("gone")),
            )
        val readings =
            ReactotronWatch.readings(
                listOf("user.*", "cart.items", "user.name", "basket"),
                values,
                connected = setOf(1, 2),
            )
        assertEquals(
            listOf(
                "user.*" to
                    listOf(
                        ReactotronWatch.Reading(1, "user.name", JsonPrimitive("Ada")),
                        ReactotronWatch.Reading(1, "user.plan", JsonPrimitive("pro")),
                    ),
                "cart.items" to
                    listOf(
                        ReactotronWatch.Reading(1, "cart.items", JsonPrimitive("tea")),
                        ReactotronWatch.Reading(2, "cart.items", JsonPrimitive("cake")),
                    ),
                "user.name" to
                    listOf(ReactotronWatch.Reading(1, "user.name", JsonPrimitive("Ada"))),
                "basket" to emptyList(),
            ),
            readings,
        )
    }

    @Test
    fun noPathsOrNoAppsMeanNoReadings() {
        val values = mapOf(1 to listOf("cart" to JsonPrimitive(1)))
        assertEquals(emptyList(), ReactotronWatch.readings(emptyList(), values, setOf(1)))
        assertEquals(
            listOf("cart" to emptyList()),
            ReactotronWatch.readings(listOf("cart"), values, connected = emptySet()),
        )
    }

    @Test
    fun anythingElseIsNotAboutWatchedPaths() {
        val values =
            ReactotronCommand.parse(
                """{"type":"state.values.response","payload":{"path":"cart","value":1}}"""
            )!!
        assertFalse(watch.heard(1, values))
        assertEquals(emptyMap(), watch.all())
    }

    @Test
    fun aSubscriptionNamesEveryPathAndAnEmptyPathWatchesNothing() {
        assertEquals(
            """{"paths":["cart.items","user.*"]}""",
            ReactotronWatch.subscribe(listOf("cart.items", "user.*")).toString(),
        )
        assertEquals("""{"paths":[]}""", ReactotronWatch.subscribe(emptyList()).toString())
        assertEquals("cart.items", ReactotronWatch.path("  cart.items "))
        assertNull(ReactotronWatch.path("   "))
    }
}
