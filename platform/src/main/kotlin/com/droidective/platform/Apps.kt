package com.droidective.platform

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import android.net.TrafficStats
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** One installed app, as the Apps list shows it. */
data class AppEntry(
    val packageName: String,
    val label: String,
    val system: Boolean,
    val enabled: Boolean,
)

/** A runtime permission an app asked for, and whether it holds it. */
data class AppPermission(val name: String, val granted: Boolean)

data class AppDetail(
    val entry: AppEntry,
    val facts: List<Fact>,
    val permissions: List<AppPermission>,
    val launchable: Boolean,
)

/** The installed apps. The app holds QUERY_ALL_PACKAGES; a Play build would list through Shell. */
object AppCatalog {
    suspend fun list(context: Context): List<AppEntry> =
        withContext(Dispatchers.IO) {
            val pm = context.packageManager
            pm.getInstalledApplications(PackageManager.MATCH_DISABLED_COMPONENTS)
                .map { it.toEntry(pm) }
                .sortedBy { it.label.lowercase() }
        }

    suspend fun detail(context: Context, packageName: String): AppDetail? =
        withContext(Dispatchers.IO) {
            val pm = context.packageManager
            val info =
                try {
                    pm.getPackageInfo(
                        packageName,
                        PackageManager.GET_PERMISSIONS or PackageManager.MATCH_DISABLED_COMPONENTS,
                    )
                } catch (_: PackageManager.NameNotFoundException) {
                    return@withContext null
                }
            val app = info.applicationInfo ?: return@withContext null
            AppDetail(
                entry = app.toEntry(pm),
                facts = facts(context, info, app),
                permissions = runtimePermissions(pm, info),
                launchable = pm.getLaunchIntentForPackage(packageName) != null,
            )
        }

    private fun facts(context: Context, info: PackageInfo, app: ApplicationInfo): List<Fact> {
        val date = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
        val installer =
            try {
                context.packageManager.getInstallSourceInfo(info.packageName).installingPackageName
            } catch (_: PackageManager.NameNotFoundException) {
                null
            }
        return listOf(
            Fact("Package", info.packageName),
            Fact("Version", "${info.versionName} (${info.longVersionCode})"),
            Fact("Target SDK", app.targetSdkVersion.toString()),
            Fact("Min SDK", app.minSdkVersion.toString()),
            Fact(
                "Installed by",
                installer
                    ?: if (app.flags and ApplicationInfo.FLAG_SYSTEM != 0) "Preinstalled"
                    else "Unknown, or adb",
            ),
            Fact("First installed", date.format(Date(info.firstInstallTime))),
            Fact("Updated", date.format(Date(info.lastUpdateTime))),
            Fact(
                "Debuggable",
                if (app.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) "Yes" else "No",
            ),
            Fact("UID", app.uid.toString()),
            Fact("APK", app.sourceDir),
            Fact("Data", app.dataDir),
        )
    }

    private fun runtimePermissions(pm: PackageManager, info: PackageInfo): List<AppPermission> {
        val names = info.requestedPermissions ?: return emptyList()
        val flags = info.requestedPermissionsFlags ?: IntArray(names.size)
        return names.indices
            .filter { index -> isRuntime(pm, names[index]) }
            .map { index ->
                AppPermission(
                    names[index],
                    flags[index] and PackageInfo.REQUESTED_PERMISSION_GRANTED != 0,
                )
            }
            .sortedBy { it.name }
    }

    private fun isRuntime(pm: PackageManager, name: String): Boolean =
        try {
            pm.getPermissionInfo(name, 0).protection == PermissionInfo.PROTECTION_DANGEROUS
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }

    private fun ApplicationInfo.toEntry(pm: PackageManager) =
        AppEntry(
            packageName = packageName,
            label = loadLabel(pm).toString(),
            system = flags and ApplicationInfo.FLAG_SYSTEM != 0,
            enabled = enabled,
        )
}

/** Total traffic counters since boot, for the Network Speed rate. */
object TrafficCounters {
    /** Bytes received and sent, or null where Android doesn't report them. */
    fun total(): Pair<Long, Long>? {
        val down = TrafficStats.getTotalRxBytes()
        val up = TrafficStats.getTotalTxBytes()
        return if (
            down == TrafficStats.UNSUPPORTED.toLong() || up == TrafficStats.UNSUPPORTED.toLong()
        )
            null
        else down to up
    }
}
