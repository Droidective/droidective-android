package com.droidective.core.apps

/** The Android version runtime permissions began with. */
private const val MARSHMALLOW = 23

/**
 * A kind of runtime permission, the way Settings' privacy pages group them, with the permissions
 * that make it up on this version of Android.
 */
data class PermissionGroup(val id: String, val title: String, val members: List<Member>) {
    /**
     * One permission in a group, what to call it on screen, and the Android versions it is a
     * runtime permission on: older ones never ask, newer ones replaced it.
     */
    data class Member(
        val name: String,
        val short: String,
        val since: Int = MARSHMALLOW,
        val until: Int = Int.MAX_VALUE,
    ) {
        fun on(sdk: Int): Boolean = sdk in since..until
    }
}

/**
 * An installed app and the runtime permissions it asks for, each held or not.
 *
 * @property system a part of Android rather than an app anyone opens: preinstalled, with nothing to
 *   launch. A preinstalled app people use, like a browser, isn't one.
 */
data class AppGrants(
    val packageName: String,
    val label: String,
    val system: Boolean,
    val granted: Map<String, Boolean>,
)

/** One app's place in a group: the group's permissions it asks for, each held or not. */
data class GroupHolder(
    val app: AppGrants,
    val permissions: List<Pair<PermissionGroup.Member, Boolean>>,
) {
    /** Whether it holds any of them: what Settings means by an app being allowed. */
    val allowed: Boolean
        get() = permissions.any { it.second }
}

/** How many apps ask for a group, and how many of those hold it. */
data class GroupCount(val group: PermissionGroup, val asking: Int, val allowed: Int)

object PermissionGroups {
    /** The groups on Android [sdk], each with the members it has there; any left empty go. */
    fun on(sdk: Int): List<PermissionGroup> = all.mapNotNull { group ->
        group.copy(members = group.members.filter { it.on(sdk) }).takeIf { it.members.isNotEmpty() }
    }

    /** The apps among [apps] that ask for any of [group]'s permissions, by name. */
    fun holders(group: PermissionGroup, apps: List<AppGrants>): List<GroupHolder> =
        apps
            .mapNotNull { app ->
                val asked =
                    group.members.mapNotNull { member ->
                        app.granted[member.name]?.let { member to it }
                    }
                if (asked.isEmpty()) null else GroupHolder(app, asked)
            }
            .sortedBy { it.app.label.lowercase() }

    /** For each group in [groups], how many of [apps] ask for it and how many are allowed it. */
    fun counts(groups: List<PermissionGroup>, apps: List<AppGrants>): List<GroupCount> =
        groups.map { group ->
            val holders = holders(group, apps)
            GroupCount(group, holders.size, holders.count { it.allowed })
        }

    private fun member(
        name: String,
        short: String,
        since: Int = MARSHMALLOW,
        until: Int = Int.MAX_VALUE,
    ) = PermissionGroup.Member("android.permission.$name", short, since, until)

    /** Every runtime permission an app can hold, in Settings' order of who people ask about. */
    val all: List<PermissionGroup> =
        listOf(
            PermissionGroup("camera", "Camera", listOf(member("CAMERA", "Camera"))),
            PermissionGroup(
                "microphone",
                "Microphone",
                listOf(member("RECORD_AUDIO", "Microphone")),
            ),
            PermissionGroup(
                "location",
                "Location",
                listOf(
                    member("ACCESS_FINE_LOCATION", "Precise"),
                    member("ACCESS_COARSE_LOCATION", "Approximate"),
                    member("ACCESS_BACKGROUND_LOCATION", "All the time", since = 29),
                ),
            ),
            PermissionGroup(
                "notifications",
                "Notifications",
                listOf(member("POST_NOTIFICATIONS", "Notifications", since = 33)),
            ),
            PermissionGroup(
                "media",
                "Photos, videos and music",
                listOf(
                    member("READ_MEDIA_IMAGES", "Photos", since = 33),
                    member("READ_MEDIA_VIDEO", "Videos", since = 33),
                    member("READ_MEDIA_AUDIO", "Music and audio", since = 33),
                    member(
                        "READ_MEDIA_VISUAL_USER_SELECTED",
                        "Chosen photos and videos",
                        since = 34,
                    ),
                    // Still how an app built for Android 12 or older reaches them on a newer one.
                    member("READ_EXTERNAL_STORAGE", "Storage"),
                    member("WRITE_EXTERNAL_STORAGE", "Write storage", until = 29),
                ),
            ),
            PermissionGroup(
                "contacts",
                "Contacts",
                listOf(
                    member("READ_CONTACTS", "Read"),
                    member("WRITE_CONTACTS", "Change"),
                    member("GET_ACCOUNTS", "Accounts"),
                ),
            ),
            PermissionGroup(
                "phone",
                "Phone",
                listOf(
                    member("READ_PHONE_STATE", "Phone state"),
                    member("READ_PHONE_NUMBERS", "Phone numbers", since = 26),
                    member("CALL_PHONE", "Make calls"),
                    member("ANSWER_PHONE_CALLS", "Answer calls", since = 26),
                    member("READ_CALL_LOG", "Read call log"),
                    member("WRITE_CALL_LOG", "Change call log"),
                    member("ADD_VOICEMAIL", "Voicemail"),
                    member("USE_SIP", "SIP calls"),
                ),
            ),
            PermissionGroup(
                "sms",
                "SMS",
                listOf(
                    member("SEND_SMS", "Send"),
                    member("RECEIVE_SMS", "Receive"),
                    member("READ_SMS", "Read"),
                    member("RECEIVE_MMS", "MMS"),
                    member("RECEIVE_WAP_PUSH", "WAP push"),
                ),
            ),
            PermissionGroup(
                "calendar",
                "Calendar",
                listOf(member("READ_CALENDAR", "Read"), member("WRITE_CALENDAR", "Change")),
            ),
            PermissionGroup(
                "nearby",
                "Nearby devices",
                listOf(
                    member("BLUETOOTH_SCAN", "Find", since = 31),
                    member("BLUETOOTH_CONNECT", "Connect", since = 31),
                    member("BLUETOOTH_ADVERTISE", "Be found", since = 31),
                    member("NEARBY_WIFI_DEVICES", "Wi-Fi devices", since = 33),
                    member("UWB_RANGING", "Ultra-wideband", since = 31),
                ),
            ),
            PermissionGroup(
                "activity",
                "Physical activity",
                listOf(member("ACTIVITY_RECOGNITION", "Activity", since = 29)),
            ),
            PermissionGroup(
                "sensors",
                "Body sensors",
                listOf(
                    member("BODY_SENSORS", "Body sensors"),
                    member("BODY_SENSORS_BACKGROUND", "All the time", since = 33),
                ),
            ),
        )
}
