package com.droidective.core.api

import com.droidective.core.net.proxy.RequestDraft
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Base64

/**
 * A `curl` command read back into a request: what a browser's "Copy as cURL", a README or a
 * colleague hands over. It is read, never run, so a flag this doesn't know is skipped and said so,
 * not trusted.
 */
object CurlCommand {
    /** What [parse] makes of a command: a request to edit, or why there isn't one. */
    sealed interface Parsed {
        /** @property skipped the flags it left out, as they were written. */
        data class Ready(val draft: RequestDraft, val skipped: List<String>) : Parsed

        data class Invalid(val reason: String) : Parsed
    }

    fun parse(command: String): Parsed {
        val words =
            ShellWords.split(command) ?: return Parsed.Invalid("A quote in it is never closed.")
        if (words.firstOrNull() != "curl") return Parsed.Invalid("It doesn't start with curl.")
        val request = Request()
        var at = 1
        while (at < words.size) {
            val word = words[at++]
            // `-sS`, `-kL`: several flags that take nothing, written as one.
            if (isBundle(word)) {
                word.drop(1).forEach { request.plain("-$it") }
                continue
            }
            val (flag, attached) = flagOf(word)
            if (flag == null) {
                request.url = request.url ?: word
                continue
            }
            if (flag in REFUSED) return Parsed.Invalid(REFUSED.getValue(flag))
            if (flag in WITHOUT_VALUE) {
                request.plain(flag)
                continue
            }
            val value =
                attached ?: words.getOrNull(at++) ?: return Parsed.Invalid("$flag needs a value.")
            if (!request.with(flag, value)) request.skipped += word
        }
        return request.parsed()
    }

    private fun isBundle(word: String): Boolean =
        word.length > 2 &&
            word[0] == '-' &&
            word[1] != '-' &&
            word.drop(1).all { "-$it" in WITHOUT_VALUE }

    /** `--name=value`, `-Xvalue`, or a flag alone; a word that isn't one is the URL. */
    private fun flagOf(word: String): Pair<String?, String?> =
        when {
            word.startsWith("--") && '=' in word ->
                word.substringBefore('=') to word.substringAfter('=')
            word.startsWith("--") -> word to null
            word.startsWith("-") && word.length > 2 && word.substring(0, 2) in ATTACHABLE ->
                word.substring(0, 2) to word.substring(2)
            word.startsWith("-") && word.length > 1 -> word to null
            else -> null to null
        }

    /** A request as the flags build it up, before it is checked. */
    private class Request {
        var url: String? = null
        var method: String? = null
        val headers = mutableListOf<Pair<String, String>>()
        val data = mutableListOf<String>()
        var get = false
        var head = false
        val skipped = mutableListOf<String>()

        fun plain(flag: String) {
            when (flag) {
                "-G",
                "--get" -> get = true
                "-I",
                "--head" -> head = true
                else -> Unit
            }
        }

        /** Takes [flag] with [value], or says it was left out. */
        fun with(flag: String, value: String): Boolean {
            when (flag) {
                "-X",
                "--request" -> method = value.uppercase()
                "-H",
                "--header" -> header(value)
                "-d",
                "--data",
                "--data-raw",
                "--data-binary",
                "--data-ascii" -> data += value
                "--data-urlencode" -> data += urlencoded(value)
                "--json" -> json(value)
                "-u",
                "--user" -> basic(value)
                "-A",
                "--user-agent" -> headers += "User-Agent" to value
                "-e",
                "--referer" -> headers += "Referer" to value
                "-b",
                "--cookie" -> if ('=' in value) headers += "Cookie" to value else return false
                "--url" -> url = value
                in IGNORED_VALUES -> Unit
                else -> return false
            }
            return true
        }

        private fun header(line: String) {
            val name = line.substringBefore(':').trim()
            // `-H 'Name;'` is curl's way of sending an empty header; `-H 'Name:'` removes one.
            if (line.endsWith(';') && ':' !in line) headers += name.removeSuffix(";") to ""
            else if (line.substringAfter(':').isNotBlank()) {
                headers += name to line.substringAfter(':').trim()
            }
        }

        private fun json(value: String) {
            data += value
            if (headers.none { it.first.equals(CONTENT_TYPE, true) })
                headers += CONTENT_TYPE to JSON
            if (headers.none { it.first.equals("Accept", true) }) headers += "Accept" to JSON
        }

        private fun basic(user: String) {
            val token = Base64.getEncoder().encodeToString(user.toByteArray(StandardCharsets.UTF_8))
            headers += "Authorization" to "Basic $token"
        }

        fun parsed(): Parsed {
            val target = url ?: return Parsed.Invalid("There's no URL in it.")
            var address = if ("://" in target) target else "http://$target"
            val body = data.joinToString("&")
            val verb =
                method
                    ?: when {
                        head -> "HEAD"
                        data.isNotEmpty() && !get -> "POST"
                        else -> "GET"
                    }
            if (get && body.isNotEmpty()) {
                address += (if ('?' in address) "&" else "?") + body
            }
            val sent = if (get) "" else body
            // What curl itself adds to a body sent with -d, when nothing says otherwise.
            if (sent.isNotEmpty() && headers.none { it.first.equals(CONTENT_TYPE, true) }) {
                headers += CONTENT_TYPE to FORM
            }
            val lines = headers.joinToString("\n") { (name, value) -> "$name: $value" }
            return Parsed.Ready(RequestDraft(verb, address, lines, sent), skipped)
        }
    }

    /** `--data-urlencode`'s forms: `content`, `=content`, and `name=content`. */
    private fun urlencoded(value: String): String {
        val equals = value.indexOf('=')
        val encode = { text: String -> URLEncoder.encode(text, StandardCharsets.UTF_8) }
        return when {
            equals < 0 -> encode(value)
            equals == 0 -> encode(value.substring(1))
            else -> value.substring(0, equals) + "=" + encode(value.substring(equals + 1))
        }
    }

    private const val CONTENT_TYPE = "Content-Type"
    private const val JSON = "application/json"
    private const val FORM = "application/x-www-form-urlencoded"

    /** The short flags whose value can be written straight after them, as in `-XPOST`. */
    private val ATTACHABLE = setOf("-X", "-H", "-d", "-u", "-A", "-e", "-b", "-o", "-m", "-w")

    /** Flags that take no value, and change nothing about the request or are about the output. */
    private val WITHOUT_VALUE =
        setOf(
            "-G",
            "--get",
            "-I",
            "--head",
            "-k",
            "--insecure",
            "-L",
            "--location",
            "-s",
            "--silent",
            "-S",
            "--show-error",
            "-v",
            "--verbose",
            "-i",
            "--include",
            "--compressed",
            "-f",
            "--fail",
            "-g",
            "--globoff",
            "--http1.1",
            "--http2",
            "-N",
            "--no-buffer",
        )

    /** Flags that take a value that is about how curl runs, not what it asks. */
    private val IGNORED_VALUES =
        setOf(
            "-o",
            "--output",
            "-m",
            "--max-time",
            "--connect-timeout",
            "-w",
            "--write-out",
            "--retry",
        )

    /** What can't be made into a request here, and why. */
    private val REFUSED =
        mapOf(
            "-F" to "A form with files (-F) isn't something this sends.",
            "--form" to "A form with files (--form) isn't something this sends.",
            "-T" to "Uploading a file (-T) isn't something this sends.",
            "--upload-file" to "Uploading a file isn't something this sends.",
        )
}

/**
 * Splits a command line into words the way a POSIX shell does, without running anything: single
 * quotes are taken as written, double quotes keep `\"` `\\` `\$` and `` \` `` escapes, a backslash
 * before a newline joins the lines, and bash's `$'…'` — which Chrome's "Copy as cURL" uses for
 * anything with a quote or a newline in it — understands its escapes.
 */
object ShellWords {
    /** The words, or null when a quote is never closed. */
    fun split(line: String): List<String>? {
        val words = mutableListOf<String>()
        val word = StringBuilder()
        var started = false
        var at = 0
        while (at < line.length) {
            val c = line[at]
            when {
                c == '\\' && line.getOrNull(at + 1) == '\n' -> at += 1
                c == '\\' && at + 1 < line.length -> {
                    word.append(line[at + 1])
                    started = true
                    at += 1
                }
                c == '\'' -> {
                    val end = line.indexOf('\'', at + 1).takeIf { it >= 0 } ?: return null
                    word.append(line, at + 1, end)
                    started = true
                    at = end
                }
                c == '$' && line.getOrNull(at + 1) == '\'' -> {
                    at = ansi(line, at + 2, word) ?: return null
                    started = true
                }
                c == '"' -> {
                    at = doubled(line, at + 1, word) ?: return null
                    started = true
                }
                c.isWhitespace() -> {
                    if (started) words += word.toString()
                    word.clear()
                    started = false
                }
                else -> {
                    word.append(c)
                    started = true
                }
            }
            at++
        }
        if (started) words += word.toString()
        return words
    }

    /** A double-quoted part from [start]; returns where its closing quote is. */
    private fun doubled(line: String, start: Int, word: StringBuilder): Int? {
        var at = start
        while (at < line.length) {
            val c = line[at]
            if (c == '"') return at
            if (c == '\\' && line.getOrNull(at + 1) in DOUBLE_ESCAPES) {
                word.append(line[at + 1])
                at += 2
                continue
            }
            if (c == '\\' && line.getOrNull(at + 1) == '\n') {
                at += 2
                continue
            }
            word.append(c)
            at++
        }
        return null
    }

    /** A `$'…'` part from [start]; returns where its closing quote is. */
    private fun ansi(line: String, start: Int, word: StringBuilder): Int? {
        var at = start
        while (at < line.length) {
            val c = line[at]
            if (c == '\'') return at
            if (c != '\\' || at + 1 >= line.length) {
                word.append(c)
                at++
                continue
            }
            val next = line[at + 1]
            at += 2
            when (next) {
                'n' -> word.append('\n')
                't' -> word.append('\t')
                'r' -> word.append('\r')
                '\\',
                '\'',
                '"',
                '?' -> word.append(next)
                'x' -> {
                    val hex =
                        line.substring(at, minOf(at + 2, line.length)).takeWhile { it.isHexDigit() }
                    if (hex.isEmpty()) word.append("\\x") else word.append(hex.toInt(HEX).toChar())
                    at += hex.length
                }
                'u' -> {
                    val hex =
                        line.substring(at, minOf(at + 4, line.length)).takeWhile { it.isHexDigit() }
                    if (hex.isEmpty()) word.append("\\u") else word.append(hex.toInt(HEX).toChar())
                    at += hex.length
                }
                else -> word.append('\\').append(next)
            }
        }
        return null
    }

    private fun Char.isHexDigit() = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

    private val DOUBLE_ESCAPES = setOf('"', '\\', '$', '`')
    private const val HEX = 16
}
