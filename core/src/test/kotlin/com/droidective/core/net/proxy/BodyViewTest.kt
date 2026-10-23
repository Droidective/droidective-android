package com.droidective.core.net.proxy

import com.droidective.core.net.http.CapturedBody
import com.droidective.core.net.http.Header
import com.droidective.core.net.http.Headers
import java.io.ByteArrayOutputStream
import java.util.HexFormat
import java.util.zip.GZIPOutputStream
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BodyViewTest {
    private fun headers(vararg pairs: Pair<String, String>) =
        Headers(pairs.map { Header(it.first, it.second) })

    private fun body(bytes: ByteArray, truncated: Boolean = false) =
        CapturedBody(bytes, bytes.size.toLong(), truncated)

    private fun body(text: String, truncated: Boolean = false) = body(text.toByteArray(), truncated)

    private fun gzipped(text: String): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(text.toByteArray()) }
        return out.toByteArray()
    }

    @Test
    fun nothingKeptIsNothingToShow() {
        assertEquals(BodyShape.Empty, BodyView.of(Headers(), null))
        assertEquals(BodyShape.Empty, BodyView.of(Headers(), CapturedBody.NONE))
    }

    @Test
    fun jsonIsLaidOutWithTheServersOwnKeyOrderKept() {
        val shape =
            BodyView.of(
                headers("Content-Type" to "application/json"),
                body("""{"zebra":1,"apple":{"deep":[1,2]}}"""),
            )
        val json = assertIs<BodyShape.Json>(shape)
        // Zebra first, because that is how it arrived: reordering hides what changed between
        // two answers, which is the thing people are most often comparing.
        assertTrue(json.pretty.indexOf("zebra") < json.pretty.indexOf("apple"))
        assertContains(json.pretty, "\n")
    }

    @Test
    fun jsonIsRecognisedWithoutBeingAnnounced() {
        // Plenty of servers send JSON as text/plain, or with no type at all.
        assertIs<BodyShape.Json>(BodyView.of(Headers(), body("""[1, 2, 3]""")))
        assertIs<BodyShape.Json>(
            BodyView.of(headers("Content-Type" to "text/plain"), body("""  {"a":1}"""))
        )
    }

    @Test
    fun whatIsNotJsonStaysText() {
        assertEquals(BodyShape.Text("hello"), BodyView.of(Headers(), body("hello")))
        // Starts like JSON and is not: shown as what it is rather than refused.
        assertEquals(BodyShape.Text("{not json"), BodyView.of(Headers(), body("{not json")))
    }

    @Test
    fun aCompressedBodyIsReadBeforeItIsJudged() {
        val shape =
            BodyView.of(
                headers("Content-Type" to "application/json", "Content-Encoding" to "gzip"),
                body(gzipped("""{"a":1}""")),
            )
        assertContains(assertIs<BodyShape.Json>(shape).pretty, "\"a\"")
    }

    @Test
    fun aCompressionThisCannotReadSaysSoRatherThanShowingRubbish() {
        val shape =
            BodyView.of(
                headers("Content-Type" to "application/json", "Content-Encoding" to "dcz"),
                body("not readable without its dictionary"),
            )
        assertContains(assertIs<BodyShape.Binary>(shape).reason, "dcz")
    }

    @Test
    fun zstdIsUndoneAsGzipIs() {
        // What the reference zstd made of "hello, hello, hello!".
        val zstd = assertNotNull(javaClass.getResource("/zstd/short.zst")).readBytes()
        val shape =
            BodyView.of(
                headers("Content-Type" to "text/plain", "Content-Encoding" to "zstd"),
                CapturedBody(zstd, zstd.size.toLong(), truncated = false),
            )
        assertEquals("hello, hello, hello!", assertIs<BodyShape.Text>(shape).text)
    }

    @Test
    fun zstdThatIsNotZstdSaysItCantBeRead() {
        val shape =
            BodyView.of(
                headers("Content-Type" to "text/plain", "Content-Encoding" to "zstd"),
                body("not really zstd"),
            )
        assertContains(assertIs<BodyShape.Binary>(shape).reason, "zstd")
    }

    @Test
    fun brotliIsUndoneAsGzipIs() {
        val shape =
            BodyView.of(
                headers("Content-Type" to "application/json", "Content-Encoding" to "br"),
                CapturedBody(BROTLI, BROTLI.size.toLong(), truncated = false),
            )
        val pretty = assertIs<BodyShape.Json>(shape).pretty
        assertEquals(40, Regex("\"fish\"").findAll(pretty).count())
        assertEquals(1_121, BodyView.decode("br", BROTLI)?.size)
    }

    @Test
    fun brotliThatIsntIsNotPretendedTo() {
        assertNull(BodyView.decode("br", "not really brotli".toByteArray()))
        // Cut short, it gives back what it could, if anything, and never throws.
        val cut = BodyView.decode("br", BROTLI.copyOf(20))
        assertTrue(cut == null || cut.size < 1_121)
    }

    @Test
    fun aBodyThatUnpacksToGigabytesStopsAtTheBound() {
        val zeros = ByteArrayOutputStream()
        GZIPOutputStream(zeros).use { out ->
            val chunk = ByteArray(1024 * 1024)
            repeat(64) { out.write(chunk) }
        }
        assertEquals(BodyView.MAX_DECODED, BodyView.decode("gzip", zeros.toByteArray())?.size)
    }

    @Test
    fun aHalfCapturedCompressedBodyGivesBackAsMuchAsItCan() {
        val items = (1..200).joinToString(",") { "\"item-$it\"" }
        val original = """{"items":[$items]}"""
        val whole = gzipped(original)
        val most = body(whole.copyOfRange(0, whole.size * 3 / 4), truncated = true)
        val shape =
            BodyView.of(
                headers("Content-Type" to "application/json", "Content-Encoding" to "gzip"),
                most,
            )
        // Not JSON any more, because it is not whole - but what did arrive is readable, and is
        // the start of what was sent rather than something invented to fill the gap.
        val text = assertIs<BodyShape.Text>(shape).text
        assertTrue(text.isNotEmpty())
        assertTrue(original.startsWith(text), "what came back should be the start of what was sent")
        assertContains(text, "item-1")
    }

    @Test
    fun aTruncatedBodyIsNeverPassedOffAsWholeJson() {
        val shape =
            BodyView.of(
                headers("Content-Type" to "application/json"),
                body("""{"a":1}""", truncated = true),
            )
        assertIs<BodyShape.Text>(shape)
    }

    @Test
    fun anImageIsHandedOverAsBytesToDraw() {
        val bytes = byteArrayOf(-119, 80, 78, 71)
        val shape = BodyView.of(headers("Content-Type" to "image/png"), body(bytes))
        assertEquals(BodyShape.Image("image/png", bytes), shape)
    }

    @Test
    fun aFormIsPulledApartIntoItsFields() {
        val shape =
            BodyView.of(
                headers("Content-Type" to "application/x-www-form-urlencoded"),
                body("name=Ada+Lovelace&note=100%25+sure&empty="),
            )
        assertEquals(
            listOf("name" to "Ada Lovelace", "note" to "100% sure", "empty" to ""),
            assertIs<BodyShape.Form>(shape).fields,
        )
    }

    @Test
    fun bytesThatAreNotTextSaySoInsteadOfPrintingReplacements() {
        val bytes = byteArrayOf(0, -1, -2, -3, 99)
        val shape = BodyView.of(headers("Content-Type" to "application/octet-stream"), body(bytes))
        assertEquals("not text", assertIs<BodyShape.Binary>(shape).reason)
    }

    @Test
    fun aStatedCharsetIsUsedAndNonsenseFallsBackToUtf8() {
        val latin = "café".toByteArray(Charsets.ISO_8859_1)
        val shape =
            BodyView.of(headers("Content-Type" to "text/plain; charset=ISO-8859-1"), body(latin))
        assertEquals(BodyShape.Text("café"), shape)
        // A charset nobody has heard of is not a reason to show nothing.
        assertIs<BodyShape.Text>(
            BodyView.of(headers("Content-Type" to "text/plain; charset=wat"), body("plain"))
        )
    }

    @Test
    fun prettyJsonRefusesWhatIsNotJson() {
        assertNull(BodyView.prettyJson("{"))
        assertNull(BodyView.prettyJson(""))
    }

    private companion object {
        /** `brotli -q 11` of forty copies of one JSON item: 46 bytes for 1,121. */
        val BROTLI: ByteArray =
            HexFormat.of()
                .parseHex(
                    "b10023c0ef387063adf908058dd049284568b69245a7ab5d6af6fab4229a6c529b5e018a3274e1519942efaf7030"
                )
    }
}
