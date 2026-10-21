package com.droidective.core.commands

import com.droidective.core.store.JsonFileStore
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

/** A link into an app, such as `myapp://onboarding`, saved to open in one tap. */
@Serializable data class DeepLink(val id: String, val label: String, val uri: String)

/** Saved deep links, kept as one JSON list in `deep-links.json`. */
class DeepLinkRepository(root: File) {
    private val store =
        JsonFileStore(File(root, "deep-links.json"), ListSerializer(DeepLink.serializer())) {
            emptyList()
        }

    @Synchronized fun all(): List<DeepLink> = store.load()

    @Synchronized
    fun save(link: DeepLink) {
        require(isLink(link.uri)) { "\"${link.uri}\" isn’t a link" }
        store.save(all().filterNot { it.id == link.id } + link)
    }

    @Synchronized fun delete(id: String) = store.save(all().filterNot { it.id == id })

    companion object {
        private val scheme = Regex("""^[A-Za-z][A-Za-z0-9+.-]*://\S+$""")

        /** A scheme, "://", and something after it, with no spaces. */
        fun isLink(text: String): Boolean = scheme.matches(text.trim())
    }
}

/** The link in text someone shared from another app: a message with a URL in it, say. */
object SharedLinks {
    /**
     * The first link in [text], or null when there is none. Quotes, brackets and the end of a
     * sentence around it are not part of it, but a bracket the link itself opened is:
     * `wiki/HAR_(file_format)` keeps its last `)`.
     */
    fun first(text: String): String? =
        text.split(WHITESPACE).asSequence().map(::trimmed).firstOrNull(DeepLinkRepository::isLink)

    private fun trimmed(word: String): String {
        var link = word.trimStart(*OPENERS)
        while (link.isNotEmpty() && link.last() in CLOSERS) {
            val last = link.last()
            val opener = PAIRS[last]
            if (opener != null && link.count { it == opener } >= link.count { it == last }) break
            link = link.dropLast(1)
        }
        return link
    }

    private val WHITESPACE = Regex("""\s+""")
    private val OPENERS = charArrayOf('(', '[', '<', '"', '\'', '“', '‘')
    private val CLOSERS = setOf(')', ']', '>', '"', '\'', '”', '’', '.', ',', ';', ':', '!', '?')
    private val PAIRS = mapOf(')' to '(', ']' to '[', '>' to '<')
}
