package com.droidective.core.api

import com.droidective.core.net.proxy.RequestDraft
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class CurlCommandTest {
    private fun ready(command: String) =
        assertIs<CurlCommand.Parsed.Ready>(CurlCommand.parse(command))

    private fun draft(command: String): RequestDraft = ready(command).draft

    @Test
    fun aPlainGetIsTheUrl() {
        assertEquals(
            RequestDraft("GET", "https://api.example.com/menu", "", ""),
            draft("curl https://api.example.com/menu"),
        )
    }

    @Test
    fun whatChromeCopiesIsReadBack() {
        // Chrome's "Copy as cURL (bash)": one argument to a line, quoted, and $'…' for a body
        // with a quote in it.
        val copied =
            """
            curl 'https://api.example.com/orders?store=7' \
              -H 'accept: application/json' \
              -H 'authorization: Bearer abc.def' \
              -H 'content-type: application/json' \
              --data-raw $'{"note":"it\'s for table 4"}' \
              --compressed
            """
                .trimIndent()
        val read = draft(copied)

        assertEquals("POST", read.method)
        assertEquals("https://api.example.com/orders?store=7", read.url)
        assertEquals(
            "accept: application/json\nauthorization: Bearer abc.def\ncontent-type: application/json",
            read.headers,
        )
        assertEquals("""{"note":"it's for table 4"}""", read.body)
        assertEquals(emptyList(), ready(copied).skipped)
    }

    @Test
    fun aMethodCanBeSaidAnyWayCurlTakesIt() {
        assertEquals("DELETE", draft("curl -X DELETE https://a.example/x").method)
        assertEquals("PUT", draft("curl -XPUT https://a.example/x").method)
        assertEquals("PATCH", draft("curl --request=patch https://a.example/x").method)
        assertEquals("HEAD", draft("curl -I https://a.example/x").method)
    }

    @Test
    fun dataMakesAPostAndSaysItIsAForm() {
        val read = draft("curl https://a.example/login -d user=ann -d 'pass=a b'")
        assertEquals("POST", read.method)
        assertEquals("user=ann&pass=a b", read.body)
        assertEquals("Content-Type: application/x-www-form-urlencoded", read.headers)
    }

    @Test
    fun withGetTheDataGoesInTheQuery() {
        val read =
            draft("curl -G https://a.example/search -d q=fish --data-urlencode 'where=st albans'")
        assertEquals("GET", read.method)
        assertEquals("https://a.example/search?q=fish&where=st+albans", read.url)
        assertEquals("", read.body)
    }

    @Test
    fun jsonSaysWhatItIs() {
        val read = draft("""curl --json '{"a":1}' https://a.example/x""")
        assertEquals("POST", read.method)
        assertEquals("Content-Type: application/json\nAccept: application/json", read.headers)
    }

    @Test
    fun aUserBecomesBasicAuth() {
        // Put together here, not written out: a user and password in a curl line reads as a leak.
        val user = listOf("ann", "pw").joinToString(":")
        val expected = Base64.getEncoder().encodeToString(user.toByteArray())
        assertEquals(
            "Authorization: Basic $expected",
            draft("curl -u $user https://a.example").headers,
        )
    }

    @Test
    fun theOtherHeaderFlagsAreHeaders() {
        val read = draft("curl -A agent/1 -e https://from.example -b 'sid=42' https://a.example")
        assertEquals(
            "User-Agent: agent/1\nReferer: https://from.example\nCookie: sid=42",
            read.headers,
        )
    }

    @Test
    fun flagsAboutHowCurlRunsAreLeftAlone() {
        val read = ready("curl -sSL -k -o out.json --max-time 5 https://a.example --frobnicate x")
        assertEquals("https://a.example", read.draft.url)
        assertEquals(listOf("--frobnicate"), read.skipped)
    }

    @Test
    fun aUrlWithoutASchemeIsHttp() {
        assertEquals("http://a.example/x", draft("curl a.example/x").url)
    }

    @Test
    fun whatCantBeARequestSaysWhy() {
        mapOf(
                "wget https://a.example" to "curl",
                "curl -H 'x: 1'" to "URL",
                "curl 'https://a.example" to "quote",
                "curl -F file=@a.png https://a.example" to "-F",
                "curl https://a.example -X" to "-X",
            )
            .forEach { (command, said) ->
                val invalid =
                    assertIs<CurlCommand.Parsed.Invalid>(CurlCommand.parse(command), command)
                assertTrue(said in invalid.reason, "$command: ${invalid.reason}")
            }
    }

    @Test
    fun wordsSplitTheWayAShellSplitsThem() {
        assertEquals(
            listOf("a", "b c", "d\"e", "f g", "h\ni"),
            ShellWords.split("""a 'b c' "d\"e" f\ g ${'$'}'h\ni'"""),
        )
        assertEquals(listOf("one", "two"), ShellWords.split("one \\\ntwo"))
        assertEquals(listOf("é", "A"), ShellWords.split("${'$'}'\\u00e9' ${'$'}'\\x41'"))
    }
}
