package com.droidective.core.api

import com.droidective.core.net.proxy.RequestDraft
import kotlinx.serialization.Serializable

/** A request kept to send again, by name: the API Testing tool's list. */
@Serializable
data class SavedRequest(
    val id: String,
    val name: String,
    val method: String = "GET",
    val url: String = "https://",
    val headers: String = "",
    val body: String = "",
) {
    /** As a draft to send, each `{{name}}` filled from [values]. */
    fun draft(values: Map<String, String>): RequestDraft =
        RequestDraft(
            Variables.fill(method, values),
            Variables.fill(url, values),
            Variables.fill(headers, values),
            Variables.fill(body, values),
        )

    /** The names it asks for that [values] doesn't have. */
    fun missing(values: Map<String, String>): List<String> =
        Variables.missing(listOf(method, url, headers, body), values)

    companion object {
        /** A draft — a pasted cURL command, say — kept under [name]. */
        fun of(id: String, name: String, draft: RequestDraft) =
            SavedRequest(id, name, draft.method, draft.url, draft.headers, draft.body)
    }
}

/** One `{{name}}`'s value. */
@Serializable data class Variable(val name: String, val value: String)
