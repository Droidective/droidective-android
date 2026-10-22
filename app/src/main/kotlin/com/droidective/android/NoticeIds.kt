package com.droidective.android

/**
 * Every untagged notice's id, in one place. Two notices with one id replace each other whatever
 * their channels, which is how starting a UI capture took the debugging proxy's notice away, and
 * how an update notice and a monkey run's took turns. A notice posted with a tag is told apart by
 * the tag as well, so it keeps its own.
 */
object NoticeIds {
    const val AUTOMATION_WORKING = 7_301
    const val AUTOMATION = 7_302
    const val PERFORMANCE = 7_310
    const val MONKEY = 7_401
    const val BUG_REPORT = 7_402
    const val UPDATE = 7_403
    const val SCREEN_RECORD = 7_420
    const val PROXY = 7_430
    const val PROXY_HELD = 7_431
    const val UI_CAPTURE = 7_432
    const val SCREENSHOT = 7_433
    const val FLOATING = 7_440

    val all =
        listOf(
            AUTOMATION_WORKING,
            AUTOMATION,
            PERFORMANCE,
            MONKEY,
            BUG_REPORT,
            UPDATE,
            SCREEN_RECORD,
            PROXY,
            PROXY_HELD,
            UI_CAPTURE,
            SCREENSHOT,
            FLOATING,
        )
}
