package com.droidective.core.apps

import com.droidective.core.shell.Parse
import com.droidective.core.shell.ShellCommand

/** One notification an app has showing, as `dumpsys notification` records it. */
data class PostedNotification(
    val id: Int,
    val tag: String?,
    val channel: String,
    val importance: Int,
    val flags: List<String>,
    val title: String?,
    val text: String?,
    val actions: List<String>,
    val postedAt: Long?,
) {
    /** Can't be swiped away: ongoing, or a foreground service's. */
    val ongoing: Boolean
        get() = "ONGOING_EVENT" in flags || "FOREGROUND_SERVICE" in flags
}

/** One notification channel an app has made, with what the person set it to. */
data class ChannelSettings(
    val id: String,
    val name: String,
    val importance: Int,
    val bypassesDnd: Boolean,
)

/**
 * What decides whether an app's notifications show: whether the app's are turned off as a whole,
 * each channel's importance, and Do Not Disturb.
 */
data class NotificationSettings(
    val blocked: Boolean,
    val channels: List<ChannelSettings>,
    val doNotDisturb: String?,
)

/**
 * The notifications an app has showing and the settings behind them, read as the shell user from
 * `dumpsys notification --noredact`. The dump holds every app's notifications with their text, so
 * only the app asked about is ever read out of it.
 */
object NotificationInspector {
    val dump: ShellCommand = ShellCommand.of("dumpsys", "notification", "--noredact")

    /** A record's first line. A tag or channel can hold spaces, so each runs to the next field. */
    private val record =
        Regex(
            """^\s+NotificationRecord\(\S+ pkg=(\S+) user=\S+ id=(-?\d+) tag=(.*?) """ +
                """importance=(-?\d+) key=.*?: Notification\(channel=(.*?) shortcut=.*? flags=(\S+) """
        )
    private val extra = Regex("""^\s+(android\.\w+)=String \((.*)\)$""")
    private val action = Regex("""^\s+\[\d+\] "(.*)" ->""")
    private val posted = Regex("""^\s+when=(\d+)/""")

    /** [packageName]'s notifications, in the order the dump lists them. */
    fun posted(dump: String, packageName: String): List<PostedNotification> {
        val lines = Parse.lines(dump)
        return lines.indices.mapNotNull { index ->
            val (pkg, id, tag, importance, channel, flags) =
                record.find(lines[index])?.destructured ?: return@mapNotNull null
            if (pkg != packageName) return@mapNotNull null
            val block =
                lines.drop(index + 1).takeWhile { record.find(it) == null && it.isNotBlank() }
            val extras =
                block.mapNotNull { extra.find(it)?.destructured }.associate { (k, v) -> k to v }
            PostedNotification(
                id = id.toInt(),
                tag = tag.takeUnless { it == "null" },
                channel = channel,
                importance = importance.toInt(),
                flags = flags.split('|').filter { it.isNotBlank() && it != "0x0" },
                title = extras["android.title"],
                text = extras["android.bigText"] ?: extras["android.text"],
                actions = block.mapNotNull { action.find(it)?.groupValues?.get(1) },
                postedAt =
                    block.firstNotNullOfOrNull {
                        posted.find(it)?.groupValues?.get(1)?.toLongOrNull()
                    },
            )
        }
    }

    private val appSettings = Regex("""^\s+AppSettings: (\S+) \(\d+\)(.*)$""")
    private val channelLine =
        Regex(
            """^\s+NotificationChannel\{mId='([^']*)', mName=(.*?), mDescription=.*?mImportance=(-?\d+), mBypassDnd=(\w+)"""
        )
    private val zen = Regex("""^\s+mZenMode=(\S+)""")

    fun settings(dump: String, packageName: String): NotificationSettings {
        val lines = Parse.lines(dump)
        val start = lines.indexOfFirst { appSettings.find(it)?.groupValues?.get(1) == packageName }
        val header =
            if (start >= 0) appSettings.find(lines[start])?.groupValues?.get(2).orEmpty() else ""
        val channels =
            if (start < 0) emptyList()
            else
                lines
                    .drop(start + 1)
                    .takeWhile {
                        appSettings.find(it) == null && !it.trimStart().startsWith("AppSettings")
                    }
                    .mapNotNull { channelLine.find(it)?.destructured }
                    .map { (id, name, importance, bypass) ->
                        ChannelSettings(id, name, importance.toInt(), bypass == "true")
                    }
        return NotificationSettings(
            blocked = "importance=NONE" in header,
            channels = channels,
            doNotDisturb = lines.firstNotNullOfOrNull { zen.find(it)?.groupValues?.get(1) },
        )
    }

    /** An importance as Settings names it; 0 is a channel turned off. */
    fun importance(level: Int): String =
        when (level) {
            0 -> "Off"
            1 -> "Min: no sound, collapsed"
            2 -> "Low: no sound"
            3 -> "Default: makes a sound"
            4 -> "High: sound and a heads-up"
            5 -> "Urgent"
            else -> "Unspecified"
        }

    /** Do Not Disturb's mode as people say it, or null when it's off. */
    fun doNotDisturb(mode: String?): String? =
        when (mode) {
            null,
            "ZEN_MODE_OFF" -> null
            "ZEN_MODE_IMPORTANT_INTERRUPTIONS" -> "Do Not Disturb is on: priority only"
            "ZEN_MODE_ALARMS" -> "Do Not Disturb is on: alarms only"
            "ZEN_MODE_NO_INTERRUPTIONS" -> "Do Not Disturb is on: total silence"
            else -> "Do Not Disturb: $mode"
        }
}
