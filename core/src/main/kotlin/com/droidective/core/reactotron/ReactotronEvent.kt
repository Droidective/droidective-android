package com.droidective.core.reactotron

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * A command read for what it says: what a timeline shows. Built from a [ReactotronCommand] the way
 * Droidective for Mac builds its own, so the two read an app the same; a type neither knows is
 * [Unknown], kept rather than dropped.
 */
sealed interface ReactotronEvent {
    data class ClientIntro(
        val name: String,
        val platform: String?,
        val environment: String?,
        val version: String?,
    ) : ReactotronEvent

    data class Log(val level: Level, val message: String, val stack: List<Frame>) :
        ReactotronEvent {
        enum class Level {
            Debug,
            Warn,
            Error,
        }
    }

    data class Display(val name: String, val value: JsonElement?, val preview: String?) :
        ReactotronEvent

    data class Image(val uri: String, val caption: String?) : ReactotronEvent

    data class ApiResponse(
        val method: String,
        val url: String,
        /** 0 when the request never got an answer: how the client says a network error. */
        val status: Int,
        val durationMillis: Double,
        val request: JsonElement?,
        val response: JsonElement?,
    ) : ReactotronEvent

    data class Benchmark(val title: String, val steps: List<Step>) : ReactotronEvent {
        data class Step(val title: String, val time: Double, val delta: Double)
    }

    /** The app cleared its timeline: what came before it is gone from view. */
    data object Clear : ReactotronEvent

    data class AsyncStorage(val action: String, val data: JsonElement?) : ReactotronEvent

    /** A Redux action, or another state library's, as it completed. */
    data class StateAction(val name: String, val action: JsonElement?, val millis: Double?) :
        ReactotronEvent

    data class StateChange(val changes: List<Pair<String, JsonElement>>) : ReactotronEvent

    data class Unknown(val type: String, val payload: JsonElement?) : ReactotronEvent

    /** A frame in a stack trace, as the client sends it. */
    data class Frame(
        val fileName: String,
        val functionName: String,
        val line: Int?,
        val column: Int?,
    )

    companion object {
        fun of(command: ReactotronCommand): ReactotronEvent {
            val payload = command.payload
            return when (command.type) {
                "client.intro" -> intro(payload)
                "log" -> log(payload)
                "display" ->
                    Display(
                        payload["name"].text ?: "Display",
                        payload["value"],
                        payload["preview"].text,
                    )
                "image" -> Image(payload["uri"].text.orEmpty(), payload["caption"].text)
                "api.response" -> api(payload)
                "benchmark.report" -> benchmark(payload)
                "clear" -> Clear
                "asyncStorage.mutation" ->
                    AsyncStorage(payload["action"].text.orEmpty(), payload["data"])
                "state.action.complete" ->
                    StateAction(
                        payload["name"].text ?: "(action)",
                        payload["action"],
                        payload["ms"].number,
                    )
                "state.values.change" ->
                    StateChange(
                        payload["changes"].list.map {
                            it["path"].text.orEmpty() to (it["value"] ?: JsonNull)
                        }
                    )
                else -> Unknown(command.type, payload)
            }
        }

        private fun intro(payload: JsonElement?) =
            ClientIntro(
                name = payload["name"].text ?: "App",
                platform = payload["platform"].text,
                environment = payload["environment"].text,
                version =
                    payload["reactotronCoreClientVersion"].text
                        ?: payload["reactotronVersion"].text,
            )

        private fun log(payload: JsonElement?): Log {
            val level =
                when (payload["level"].text) {
                    "warn" -> Log.Level.Warn
                    "error" -> Log.Level.Error
                    else -> Log.Level.Debug
                }
            return Log(level, message(payload["message"]), stack(payload["stack"]))
        }

        private fun api(payload: JsonElement?): ApiResponse {
            val request = payload["request"]
            val response = payload["response"]
            return ApiResponse(
                method = (request["method"].text ?: "GET").uppercase(),
                url = request["url"].text.orEmpty(),
                status = response["status"].number?.toInt() ?: 0,
                durationMillis = payload["duration"].number ?: 0.0,
                request = request,
                response = response,
            )
        }

        private fun benchmark(payload: JsonElement?) =
            Benchmark(
                payload["title"].text ?: "Benchmark",
                payload["steps"].list.map {
                    Benchmark.Step(
                        it["title"].text.orEmpty(),
                        it["time"].number ?: 0.0,
                        it["delta"].number ?: 0.0,
                    )
                },
            )

        /**
         * A log's message as one line to show: text as it is, an object or array as compact JSON,
         * so `console.log(object)` reads as its content rather than as a count. Bounded either way.
         */
        private fun message(value: JsonElement?): String {
            val shown =
                when (value) {
                    null,
                    JsonNull -> "null"
                    is JsonPrimitive -> value.content
                    is JsonObject,
                    is JsonArray -> value.toString()
                }
            return shown.take(MAX_MESSAGE)
        }

        private fun stack(value: JsonElement?): List<Frame> =
            value.list.mapNotNull { frame ->
                when (frame) {
                    is JsonObject ->
                        Frame(
                            frame["fileName"].text.orEmpty(),
                            frame["functionName"].text.orEmpty(),
                            frame["lineNumber"].number?.toInt(),
                            frame["columnNumber"].number?.toInt(),
                        )
                    is JsonPrimitive ->
                        if (frame.isString) Frame(frame.content, "", null, null) else null
                    else -> null
                }
            }

        private const val MAX_MESSAGE = 500
    }
}
