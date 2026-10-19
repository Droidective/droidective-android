package com.droidective.core

/**
 * How long before [now] the moment [at] was, worded for the middle of a sentence: "just now", "5
 * minutes ago", "an hour ago", "3 days ago". A moment after [now], from a clock that moved back,
 * reads as just now.
 */
fun ago(at: Long, now: Long): String {
    val minutes = (now - at) / MINUTE_MILLIS
    val hours = minutes / 60
    val days = hours / 24
    return when {
        minutes < 1 -> "just now"
        minutes < 60 -> count(minutes, "a minute", "minutes")
        hours < 24 -> count(hours, "an hour", "hours")
        else -> count(days, "a day", "days")
    }
}

private fun count(n: Long, one: String, many: String) = if (n == 1L) "$one ago" else "$n $many ago"

private const val MINUTE_MILLIS = 60_000L
