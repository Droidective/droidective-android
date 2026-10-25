package com.droidective.core.commands

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DeepLinkRepositoryTest {
    private val links =
        listOf(
            DeepLink("1", "Settings", "myapp://settings"),
            DeepLink("2", "checkout", "shop://cart/checkout"),
            DeepLink("3", "Onboarding", "myapp://onboarding"),
        )

    @Test
    fun anEmptyQueryListsEveryLinkByName() {
        assertEquals(
            listOf("checkout", "Onboarding", "Settings"),
            DeepLinkRepository.matching(links, "  ").map { it.label },
        )
    }

    @Test
    fun aQueryMatchesTheNameOrTheLinkInAnyCase() {
        assertEquals(
            listOf("Settings"),
            DeepLinkRepository.matching(links, "SETT").map { it.label },
        )
        assertEquals(
            listOf("Onboarding", "Settings"),
            DeepLinkRepository.matching(links, "myapp://").map { it.label },
        )
    }

    @Test
    fun aQueryThatMatchesNothingListsNothing() {
        assertEquals(emptyList(), DeepLinkRepository.matching(links, "nothing"))
    }

    @Test
    fun aSavedLinkIsReadBackAndReplacedByItsId() {
        val repository = DeepLinkRepository(Files.createTempDirectory("links").toFile())

        repository.save(DeepLink("a", "Home", "myapp://home"))
        repository.save(DeepLink("a", "Home page", "myapp://home"))

        assertEquals(listOf(DeepLink("a", "Home page", "myapp://home")), repository.all())
    }

    @Test
    fun somethingThatIsNotALinkIsRefused() {
        val repository = DeepLinkRepository(Files.createTempDirectory("links").toFile())

        assertFailsWith<IllegalArgumentException> {
            repository.save(DeepLink("a", "Home", "not a link"))
        }
    }
}
