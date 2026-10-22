package com.droidective.android.api

import com.droidective.android.proxy.KeptList
import com.droidective.core.api.SavedRequest
import com.droidective.core.api.Variable

/** API Testing's saved requests and the values their `{{names}}` take, kept across runs. */
internal object ApiRequests {
    val requests = KeptList("api-requests.json", SavedRequest.serializer(), SavedRequest::id) {}

    val variables = KeptList("api-variables.json", Variable.serializer(), Variable::name) {}
}
