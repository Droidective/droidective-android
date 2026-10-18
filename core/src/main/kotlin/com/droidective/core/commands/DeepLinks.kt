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
