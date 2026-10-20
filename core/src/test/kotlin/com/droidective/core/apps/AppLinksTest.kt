package com.droidective.core.apps

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Against `pm get-app-links --user 0` from an API 36 emulator (fixtures/applinks/). */
class AppLinksTest {
    @Test
    fun readsEachDomainAndTheUsersChoice() {
        val status =
            AppLinks.parse(
                File(System.getProperty("fixtures.dir"), "applinks/youtube-api36.txt").readText()
            )
        assertEquals(
            listOf("youtu.be", "m.youtube.com", "youtube.com", "www.youtube.com"),
            status.domains.map { it.domain },
        )
        assertTrue(status.domains.all { it.state == "system_configured" && it.opensTheApp })
        assertEquals(true, status.handlingAllowed)
        assertEquals(emptyList(), status.enabled)
        assertEquals(4, status.disabled.size)
    }

    @Test
    fun saysWhatAStateMeansForALink() {
        assertFalse(LinkDomain("a.com", "none").opensTheApp)
        assertEquals(
            "Not verified: links open a chooser or the browser",
            LinkDomain("a.com", "none").meaning,
        )
        assertTrue(
            LinkDomain("a.com", "1026").meaning.startsWith("Failed: the verifier's error 1026")
        )
        assertFalse(LinkDomain("a.com", "1026").opensTheApp)
        assertEquals("odd_new_state", LinkDomain("a.com", "odd_new_state").meaning)
    }

    @Test
    fun readsAnAppWithEnabledSelections() {
        val status =
            AppLinks.parse(
                """
                com.example.app:
                  ID: 00000000-0000-0000-0000-000000000000
                  Signatures: [AA:BB]
                  Domain verification state:
                    example.com: verified
                    shop.example.com: 1024
                  User 0:
                    Verification link handling allowed: false
                    Selection state:
                      Enabled:
                        example.com
                      Disabled:
                        shop.example.com
                """
                    .trimIndent()
            )
        assertEquals(listOf("verified", "1024"), status.domains.map { it.state })
        assertEquals(false, status.handlingAllowed)
        assertEquals(listOf("example.com"), status.enabled)
        assertEquals(listOf("shop.example.com"), status.disabled)
    }

    @Test
    fun commandsQuoteThePackage() {
        assertEquals(
            "pm get-app-links --user 0 'com.example.app'",
            AppLinks.status("com.example.app", 0).line,
        )
        assertEquals(
            "pm verify-app-links --re-verify 'com.example.app'",
            AppLinks.reverify("com.example.app").line,
        )
        assertEquals(
            "pm set-app-links --package 'com.example.app' 2 all",
            AppLinks.approve("com.example.app").line,
        )
        assertEquals(
            "pm set-app-links-user-selection --user 10 --package 'com.example.app' true all",
            AppLinks.allowForUser("com.example.app", 10).line,
        )
        assertEquals(
            listOf(
                "pm reset-app-links 'com.example.app'",
                "pm verify-app-links --re-verify 'com.example.app'",
            ),
            AppLinks.reset("com.example.app").map { it.line },
        )
        assertFailsWith<IllegalArgumentException> { AppLinks.approve("x; reboot") }
    }
}
