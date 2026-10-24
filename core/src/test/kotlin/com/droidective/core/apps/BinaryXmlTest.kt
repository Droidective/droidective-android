package com.droidective.core.apps

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.w3c.dom.Element

class BinaryXmlTest {
    private val fixtures = File(System.getProperty("fixtures.dir"), "apks")
    private val real = File(fixtures, "droidective-release-manifest.bin").readBytes()

    @Test
    fun decodesTheRealManifestBackToWhatWasCompiled() {
        val written = elements(File(fixtures, "droidective-release-manifest.xml").readText())
        val read = elements(BinaryXml.decode(real))
        assertEquals(written.map { it.first }, read.map { it.first })
        var references = 0
        written.zip(read).forEach { (w, r) ->
            assertEquals(emptySet(), w.second.keys - r.second.keys, w.first)
            assertEquals(emptySet(), r.second.keys - w.second.keys - ADDED_BY_AAPT2, w.first)
            w.second.forEach { (name, value) ->
                val got = r.second.getValue(name)
                if (value.startsWith("@") || value.startsWith("?")) {
                    // A reference keeps its id; only the device's resources can name it.
                    assertTrue(got.matches(Regex("""\${value[0]}0x[0-9a-f]{8}""")), "$name=$got")
                    references++
                } else {
                    assertEquals(value, got, "${w.first} $name")
                }
            }
        }
        assertEquals(76, references)
    }

    @Test
    fun aReferenceIsNamedWhenItsNameIsKnown() {
        val names =
            mapOf(0x7f0d0003 to "string/app_name", 0x01030055 to "android:style/Theme.NoDisplay")
        val decoded = BinaryXml.decode(real) { names[it] }
        assertTrue("android:label=\"@string/app_name\"" in decoded)
        assertTrue("android:theme=\"@android:style/Theme.NoDisplay\"" in decoded)
    }

    @Test
    fun laysOutTheManifestOneAttributeALine() {
        val decoded = BinaryXml.decode(real)
        assertEquals(
            """
            <?xml version="1.0" encoding="utf-8"?>
            <manifest
                xmlns:android="http://schemas.android.com/apk/res/android"
                android:versionCode="22"
                android:versionName="0.20.0"
                android:compileSdkVersion="37"
                android:compileSdkVersionCodename="17"
                package="com.droidective.android"
                platformBuildVersionCode="37"
                platformBuildVersionName="17">
                <uses-sdk
                    android:minSdkVersion="30"
                    android:targetSdkVersion="37" />
                <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
            """
                .trimIndent(),
            decoded.lines().take(14).joinToString("\n"),
        )
        assertTrue(decoded.endsWith("</manifest>"))
    }

    @Test
    fun everyKindOfValue() {
        val xml =
            Compiled(utf8 = true).run {
                val label = attrName("label", 0x01010001)
                val size = attrName("textSize", 0x01010095)
                namespace("android", ANDROID)
                start(
                    "root",
                    Attr(ANDROID, label, raw = "Tom & \"Jerry\" <3"),
                    Attr(ANDROID, size, type = 0x05, data = (16 shl 8) or 1),
                    Attr(null, string("weight"), type = 0x04, data = 1.5f.toRawBits()),
                    Attr(null, string("color"), type = 0x1c, data = 0x80ff0000.toInt()),
                    Attr(null, string("rgb"), type = 0x1d, data = 0xff00ff00.toInt()),
                    Attr(null, string("on"), type = 0x12, data = -1),
                    Attr(null, string("off"), type = 0x12, data = 0),
                    Attr(null, string("hex"), type = 0x11, data = 255),
                    Attr(null, string("negative"), type = 0x10, data = -3),
                    Attr(
                        null,
                        string("fraction"),
                        type = 0x06,
                        data = (1 shl 30) or (3 shl 4) or 1,
                    ),
                    Attr(null, string("ref"), type = 0x01, data = 0x7f010002),
                    Attr(null, string("none"), type = 0x01, data = 0),
                    Attr(null, string("empty"), type = 0x00, data = 1),
                    Attr(null, string("themed"), type = 0x02, data = 0x01010036),
                    Attr(null, string("odd"), type = 0x2a, data = 7),
                )
                start("child", Attr(null, string("long"), raw = "é".repeat(200)))
                end("child")
                start("text")
                cdata("  a < b  ")
                end("text")
                end("root")
                bytes()
            }
        val decoded = BinaryXml.decode(xml)
        listOf(
                """android:label="Tom &amp; &quot;Jerry&quot; &lt;3"""",
                """android:textSize="16dp"""",
                """weight="1.5"""",
                """color="#80ff0000"""",
                """rgb="#00ff00"""",
                """on="true"""",
                """off="false"""",
                """hex="0xff"""",
                """negative="-3"""",
                """fraction="50%p"""",
                """ref="@0x7f010002"""",
                """none="@null"""",
                """empty=""""",
                """themed="?0x01010036"""",
                """odd="(type 0x2a)0x00000007"""",
                """<child long="${"é".repeat(200)}" />""",
                """<text>a &lt; b</text>""",
            )
            .forEach { assertTrue(it in decoded, "$it in\n$decoded") }
    }

    @Test
    fun utf16StringsLongerThan32KChars() {
        val long = "x".repeat(40_000)
        val xml =
            Compiled(utf8 = false).run {
                start("root", Attr(null, string("a"), raw = long))
                end("root")
                bytes()
            }
        assertTrue("a=\"$long\"" in BinaryXml.decode(xml))
    }

    @Test
    fun aNamelessAttributeIsNamedByItsResourceId() {
        val xml =
            Compiled(utf8 = true).run {
                val nameless = attrName("", 0x01010003)
                val unknown = attrName("", 0x01019999)
                namespace("android", ANDROID)
                start("root", Attr(ANDROID, nameless, raw = "x"), Attr(ANDROID, unknown, raw = "y"))
                end("root")
                bytes()
            }
        val decoded = BinaryXml.decode(xml) { if (it == 0x01010003) "android:attr/name" else null }
        assertTrue("android:name=\"x\"" in decoded, decoded)
        assertTrue("android:0x01019999=\"y\"" in decoded, decoded)
    }

    @Test
    fun numbersAreNamedOnlyOnAndroidsOwnAttributes() {
        val xml =
            Compiled(utf8 = true).run {
                val mode = attrName("launchMode", 0x0101001d)
                namespace("android", ANDROID)
                start(
                    "activity",
                    Attr(ANDROID, mode, type = 0x10, data = 2),
                    Attr(null, string("launchMode"), type = 0x10, data = 2),
                )
                end("activity")
                bytes()
            }
        val decoded = BinaryXml.decode(xml)
        assertTrue("android:launchMode=\"singleTask\"" in decoded, decoded)
        assertTrue("\n    launchMode=\"2\"" in decoded, decoded)
    }

    @Test
    fun namesForEnumsAndFlags() {
        assertEquals("singleInstance", BinaryXml.manifestName("launchMode", 3))
        assertNull(BinaryXml.manifestName("launchMode", 42))
        assertEquals("signature|privileged", BinaryXml.manifestName("protectionLevel", 0x12))
        assertEquals("normal", BinaryXml.manifestName("protectionLevel", 0))
        assertEquals(
            "stateAlwaysVisible|adjustResize",
            BinaryXml.manifestName("windowSoftInputMode", 0x15),
        )
        assertEquals("adjustNothing", BinaryXml.manifestName("windowSoftInputMode", 0x30))
        assertEquals(
            "orientation|screenSize",
            BinaryXml.manifestName("configChanges", 0x80 or 0x400),
        )
        assertNull(BinaryXml.manifestName("configChanges", 0x80 or 0x10000), "an unnamed bit")
        assertNull(BinaryXml.manifestName("versionCode", 3))
    }

    @Test
    fun whatIsntCompiledXmlSaysSo() {
        fun fails(bytes: ByteArray, message: String) =
            assertEquals(message, assertFailsWith<IOException> { BinaryXml.decode(bytes) }.message)
        fails(ByteArray(0), "It isn't well-formed compiled XML.")
        fails("<manifest/>".toByteArray(), "It isn't compiled XML.")
        fails(real.copyOf(real.size / 2), "It isn't compiled XML.")
        val noElements =
            Compiled(utf8 = true).run {
                string("only")
                bytes()
            }
        fails(noElements, "It has no elements.")
        val unbalanced =
            Compiled(utf8 = true).run {
                end("root")
                bytes()
            }
        fails(unbalanced, "It isn't well-formed compiled XML.")
        val deep =
            Compiled(utf8 = true).run {
                repeat(300) { start("a") }
                bytes()
            }
        fails(deep, "It nests too deep to read.")
    }

    @Test
    fun aCorruptChunkIsAnErrorNotACrash() {
        val random = kotlin.random.Random(3)
        repeat(2_000) { round ->
            val bytes = real.copyOf()
            // Half the rounds hit the string pool's header and offsets, where sizes live.
            val end = if (round % 2 == 0) 1_000 else bytes.size
            repeat(4) { bytes[random.nextInt(8, end)] = random.nextInt().toByte() }
            try {
                BinaryXml.decode(bytes)
            } catch (_: IOException) {
                // Said so, which is the point.
            }
        }
    }

    /** Each element's name and its attributes, in document order. */
    private fun elements(xml: String): List<Pair<String, Map<String, String>>> {
        val factory = DocumentBuilderFactory.newInstance()
        val document = factory.newDocumentBuilder().parse(xml.byteInputStream())
        val all = document.getElementsByTagName("*")
        return (0 until all.length).map { index ->
            val element = all.item(index) as Element
            val attributes = element.attributes
            element.tagName to
                (0 until attributes.length).associate {
                    attributes.item(it).nodeName to attributes.item(it).nodeValue
                }
        }
    }

    private data class Attr(
        val namespace: String?,
        val name: Int,
        val raw: String? = null,
        val type: Int = 0x03,
        val data: Int = 0,
    )

    /** Compiled XML built by hand, laid out as aapt2 lays it out. */
    private class Compiled(private val utf8: Boolean) {
        private val strings = mutableListOf<String>()
        private val ids = mutableListOf<Int>()
        private val nodes = ByteArrayOutputStream()

        /** An attribute name with a resource id; these must come first, as the map lines up. */
        fun attrName(name: String, id: Int): Int {
            check(strings.size == ids.size)
            strings += name
            ids += id
            return strings.size - 1
        }

        fun string(value: String): Int {
            val at = strings.indexOf(value)
            if (at >= 0) return at
            strings += value
            return strings.size - 1
        }

        fun namespace(prefix: String, uri: String) = node(0x0100, ints(string(prefix), string(uri)))

        fun start(name: String, vararg attributes: Attr) {
            val ext = ByteBuffer.allocate(20 + 20 * attributes.size).order(ByteOrder.LITTLE_ENDIAN)
            ext.putInt(-1).putInt(string(name))
            ext.putShort(20).putShort(20).putShort(attributes.size.toShort())
            ext.putShort(0).putShort(0).putShort(0)
            attributes.forEach { a ->
                ext.putInt(a.namespace?.let(::string) ?: -1).putInt(a.name)
                ext.putInt(a.raw?.let(::string) ?: -1)
                ext.putShort(8).put(0).put(a.type.toByte())
                ext.putInt(if (a.type == 0x03 && a.raw != null) string(a.raw) else a.data)
            }
            node(0x0102, ext.array())
        }

        fun end(name: String) = node(0x0103, ints(-1, string(name)))

        fun cdata(text: String) {
            val index = string(text)
            val ext = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
            ext.putInt(index).putShort(8).put(0).put(0x03).putInt(index)
            node(0x0104, ext.array())
        }

        fun bytes(): ByteArray {
            val pool = pool()
            val map = chunk(0x0180, 8, ints(*ids.toIntArray()))
            val body = pool + map + nodes.toByteArray()
            return chunk(0x0003, 8, body)
        }

        private fun node(type: Int, ext: ByteArray) {
            nodes.write(chunk(type, 16, ints(1, -1) + ext))
        }

        private fun pool(): ByteArray {
            val data = ByteArrayOutputStream()
            val offsets = strings.map { value ->
                val at = data.size()
                data.write(if (utf8) utf8(value) else utf16(value))
                at
            }
            while (data.size() % 4 != 0) data.write(0)
            val header = ints(strings.size, 0, if (utf8) 0x100 else 0, 28 + 4 * strings.size, 0)
            return chunk(0x0001, 28, header + ints(*offsets.toIntArray()) + data.toByteArray())
        }

        private fun utf8(value: String): ByteArray {
            val bytes = value.toByteArray()
            return length8(value.length) + length8(bytes.size) + bytes + byteArrayOf(0)
        }

        private fun length8(n: Int): ByteArray =
            if (n < 0x80) byteArrayOf(n.toByte())
            else byteArrayOf((0x80 or (n shr 8)).toByte(), n.toByte())

        private fun utf16(value: String): ByteArray {
            val out = ByteBuffer.allocate(4 + value.length * 2 + 2).order(ByteOrder.LITTLE_ENDIAN)
            if (value.length < 0x8000) {
                out.putShort(value.length.toShort())
            } else {
                out.putShort((0x8000 or (value.length shr 16)).toShort())
                out.putShort(value.length.toShort())
            }
            value.forEach { out.putChar(it) }
            out.putShort(0)
            return out.array().copyOf(out.position())
        }

        /** A chunk: its type, header size and total size, then [rest]. */
        private fun chunk(type: Int, headerSize: Int, rest: ByteArray): ByteArray {
            val head = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
            head.putShort(type.toShort()).putShort(headerSize.toShort()).putInt(8 + rest.size)
            return head.array() + rest
        }

        private fun ints(vararg values: Int): ByteArray {
            val out = ByteBuffer.allocate(4 * values.size).order(ByteOrder.LITTLE_ENDIAN)
            values.forEach { out.putInt(it) }
            return out.array()
        }
    }

    private companion object {
        const val ANDROID = "http://schemas.android.com/apk/res/android"

        /** What aapt2 adds to <manifest> as it compiles it. */
        val ADDED_BY_AAPT2 =
            setOf(
                "android:compileSdkVersion",
                "android:compileSdkVersionCodename",
                "platformBuildVersionCode",
                "platformBuildVersionName",
            )
    }
}
