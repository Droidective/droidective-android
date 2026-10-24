package com.droidective.core.apps

/** An activity, service, receiver or provider an app declares. */
data class ApkComponent(
    val kind: Kind,
    val name: String,
    val exported: Boolean,
    val permission: String?,
    val enabled: Boolean,
) {
    enum class Kind(val title: String) {
        Activity("Activities"),
        Service("Services"),
        Receiver("Receivers"),
        Provider("Providers"),
    }

    /** Exported with no permission: any app on the phone can start or bind it. */
    val open: Boolean
        get() = exported && permission == null
}

/**
 * A permission an app asks for. [protection] is Android's word for it (dangerous, normal,
 * signature…) or null when nothing on this phone defines it; [granted] is null for an APK file.
 */
data class ApkPermission(val name: String, val protection: String?, val granted: Boolean?)

/** One file of an installed app: the base APK or a split. */
data class ApkFile(val name: String, val path: String, val bytes: Long)

/** The manifest flags that change how an app runs, and that reviewers ask about. */
data class ApkFlags(
    val debuggable: Boolean,
    val testOnly: Boolean,
    val allowBackup: Boolean,
    val cleartextTraffic: Boolean,
    val largeHeap: Boolean,
    val extractNativeLibs: Boolean,
    val profileableByShell: Boolean,
)

/**
 * Everything the APK Inspector shows about one app or APK file, gathered from PackageManager and
 * the APK itself.
 */
data class ApkReport(
    val label: String,
    val packageName: String,
    val versionName: String?,
    val versionCode: Long,
    val minSdk: Int,
    val targetSdk: Int,
    val compileSdk: Int?,
    val installed: Boolean,
    val flags: ApkFlags,
    val certificates: List<SigningCertificate>,
    val rotatedKey: Boolean,
    val anatomy: ApkAnatomy?,
    val files: List<ApkFile>,
    val permissions: List<ApkPermission>,
    val definedPermissions: List<String>,
    val components: List<ApkComponent>,
    val features: List<String>,
    val metadata: List<Pair<String, String>>,
) {
    /** The report as text, for pasting into a ticket or a chat. */
    fun text(): String = buildString {
        appendLine("$label ($packageName)")
        appendLine("Version ${versionName ?: "—"} ($versionCode)")
        appendLine(
            "SDK: min $minSdk, target $targetSdk" + (compileSdk?.let { ", compiled $it" } ?: "")
        )
        val set =
            listOfNotNull(
                "debuggable".takeIf { flags.debuggable },
                "test only".takeIf { flags.testOnly },
                "backup allowed".takeIf { flags.allowBackup },
                "cleartext traffic".takeIf { flags.cleartextTraffic },
                "large heap".takeIf { flags.largeHeap },
                "profileable".takeIf { flags.profileableByShell },
            )
        appendLine("Flags: " + set.ifEmpty { listOf("none") }.joinToString(", "))
        anatomy?.let {
            appendLine("Signature schemes: " + it.schemes.joinToString(", ") { s -> scheme(s) })
            appendLine("Native code: " + it.abis.ifEmpty { listOf("none") }.joinToString(", "))
            appendLine(
                "Dex files: ${it.dex.size}, ${it.dex.sumOf { d -> d.classes }} classes, " +
                    "${it.dex.sumOf { d -> d.methods }} method references"
            )
        }
        certificates.forEach { cert ->
            appendLine("Certificate: ${cert.subject}" + if (cert.isDebug) " (debug key)" else "")
            appendLine("  SHA-256 ${cert.sha256}")
        }
        if (rotatedKey) appendLine("The signing key was rotated; the last certificate signs now.")
        appendLine("Permissions: ${permissions.size}")
        permissions.forEach {
            appendLine("  ${it.name}" + (it.protection?.let { p -> " ($p)" } ?: ""))
        }
        ApkComponent.Kind.entries.forEach { kind ->
            val ofKind = components.filter { it.kind == kind }
            if (ofKind.isEmpty()) return@forEach
            appendLine("${kind.title}: ${ofKind.size}, ${ofKind.count { it.exported }} exported")
            ofKind
                .filter { it.exported }
                .forEach { c ->
                    appendLine("  ${c.name}" + (c.permission?.let { " needs $it" } ?: " (open)"))
                }
        }
    }
        .trimEnd()

    companion object {
        /** "v2", "v3.1": a scheme as apksigner names it. */
        fun scheme(version: Int): String = if (version == 31) "v3.1" else "v$version"
    }
}
