package com.droidective.platform

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.ComponentInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import android.content.res.Resources
import android.os.Build
import com.droidective.core.apps.ApkAnatomies
import com.droidective.core.apps.ApkAnatomy
import com.droidective.core.apps.ApkComponent
import com.droidective.core.apps.ApkFile
import com.droidective.core.apps.ApkFlags
import com.droidective.core.apps.ApkPermission
import com.droidective.core.apps.ApkReport
import com.droidective.core.apps.BinaryXml
import com.droidective.core.apps.SigningCertificates
import com.droidective.core.apps.ZipDirectory
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.security.cert.CertificateException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The APK Inspector's reports, from PackageManager and the APK files themselves. An installed app's
 * APKs are readable by any app, so neither needs a shell.
 */
object ApkReports {
    private const val FLAGS =
        PackageManager.GET_ACTIVITIES or
            PackageManager.GET_SERVICES or
            PackageManager.GET_RECEIVERS or
            PackageManager.GET_PROVIDERS or
            PackageManager.GET_PERMISSIONS or
            PackageManager.GET_SIGNING_CERTIFICATES or
            PackageManager.GET_META_DATA or
            PackageManager.GET_CONFIGURATIONS or
            PackageManager.MATCH_DISABLED_COMPONENTS

    suspend fun installed(context: Context, packageName: String): ApkReport? =
        withContext(Dispatchers.IO) {
            val pm = context.packageManager
            val info =
                try {
                    pm.getPackageInfo(packageName, FLAGS)
                } catch (_: PackageManager.NameNotFoundException) {
                    return@withContext null
                }
            val app = info.applicationInfo ?: return@withContext null
            val names = listOf("base") + info.splitNames.orEmpty()
            val paths = listOf(app.sourceDir) + app.splitSourceDirs.orEmpty()
            val files = names.zip(paths) { name, path -> ApkFile(name, path, File(path).length()) }
            report(pm, info, app, files, installed = true)
        }

    /** A picked APK file. Null when Android can't parse it, such as a split on its own. */
    suspend fun archive(context: Context, file: File): ApkReport? =
        withContext(Dispatchers.IO) {
            val pm = context.packageManager
            val info = pm.getPackageArchiveInfo(file.path, FLAGS) ?: return@withContext null
            val app = info.applicationInfo ?: return@withContext null
            // Without these, the label and icon resolve against nothing.
            app.sourceDir = file.path
            app.publicSourceDir = file.path
            report(pm, info, app, listOf(ApkFile(file.name, file.path, file.length())), false)
        }

    /**
     * The base APK's AndroidManifest.xml as XML, its references named from the app's resources and
     * Android's.
     *
     * @throws IOException when it can't be read or isn't compiled XML.
     */
    suspend fun manifest(context: Context, report: ApkReport): String =
        withContext(Dispatchers.IO) {
            val base = File(report.files.first().path)
            val compiled =
                RandomAccessFile(base, "r").use { apk ->
                    val record =
                        ZipDirectory.entries(apk).firstOrNull { it.name == MANIFEST }
                            ?: throw IOException("It has no $MANIFEST.")
                    if (record.size > MAX_MANIFEST_BYTES) {
                        throw IOException("Its manifest is ${record.size} bytes, too big to show.")
                    }
                    ZipDirectory.head(apk, record, record.size.toInt())
                }
            val resources = resources(context.packageManager, report, base)
            BinaryXml.decode(compiled) { id -> resourceName(resources, report.packageName, id) }
        }

    private fun resources(pm: PackageManager, report: ApkReport, base: File): Resources? =
        try {
            if (report.installed) {
                pm.getResourcesForApplication(report.packageName)
            } else {
                pm.getPackageArchiveInfo(base.path, 0)?.applicationInfo?.let { app ->
                    app.sourceDir = base.path
                    app.publicSourceDir = base.path
                    pm.getResourcesForApplication(app)
                }
            }
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }

    /** `string/app_name` for the app's own, `android:style/Theme` for another package's. */
    private fun resourceName(resources: Resources?, packageName: String, id: Int): String? =
        try {
            val from = resources ?: Resources.getSystem()
            val name = "${from.getResourceTypeName(id)}/${from.getResourceEntryName(id)}"
            val owner = from.getResourcePackageName(id)
            if (owner == packageName) name else "$owner:$name"
        } catch (_: Resources.NotFoundException) {
            null
        }

    private fun report(
        pm: PackageManager,
        info: PackageInfo,
        app: ApplicationInfo,
        files: List<ApkFile>,
        installed: Boolean,
    ): ApkReport {
        val (certificates, rotated) = certificates(info)
        return ApkReport(
            label = app.loadLabel(pm).toString(),
            packageName = info.packageName,
            versionName = info.versionName,
            versionCode = info.longVersionCode,
            minSdk = app.minSdkVersion,
            targetSdk = app.targetSdkVersion,
            compileSdk =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    app.compileSdkVersion.takeIf { it > 0 }
                } else {
                    null
                },
            installed = installed,
            flags = flags(app),
            certificates = certificates,
            rotatedKey = rotated,
            anatomy = anatomy(files),
            files = files,
            permissions = permissions(pm, info, installed),
            definedPermissions = info.permissions.orEmpty().map { it.name }.sorted(),
            components = components(info),
            features =
                info.reqFeatures.orEmpty().mapNotNull { feature ->
                    feature.name ?: feature.glEsVersion?.let { "OpenGL ES $it" }
                },
            metadata = metadata(app),
        )
    }

    private fun flags(app: ApplicationInfo): ApkFlags {
        fun has(flag: Int) = app.flags and flag != 0
        return ApkFlags(
            debuggable = has(ApplicationInfo.FLAG_DEBUGGABLE),
            testOnly = has(ApplicationInfo.FLAG_TEST_ONLY),
            allowBackup = has(ApplicationInfo.FLAG_ALLOW_BACKUP),
            cleartextTraffic = has(ApplicationInfo.FLAG_USES_CLEARTEXT_TRAFFIC),
            largeHeap = has(ApplicationInfo.FLAG_LARGE_HEAP),
            extractNativeLibs = has(ApplicationInfo.FLAG_EXTRACT_NATIVE_LIBS),
            profileableByShell = app.isProfileableByShell,
        )
    }

    /** The certificates that sign it now, and whether its key was rotated to them. */
    private fun certificates(info: PackageInfo) =
        info.signingInfo?.let { signing ->
            val signers =
                if (signing.hasMultipleSigners()) signing.apkContentsSigners
                else signing.signingCertificateHistory?.takeLast(1)?.toTypedArray()
            val described =
                signers.orEmpty().mapNotNull { signature ->
                    try {
                        SigningCertificates.describe(signature.toByteArray())
                    } catch (_: CertificateException) {
                        null
                    }
                }
            val history = signing.signingCertificateHistory?.size ?: 0
            described to (!signing.hasMultipleSigners() && history > 1)
        } ?: (emptyList<com.droidective.core.apps.SigningCertificate>() to false)

    /**
     * Every file's native code and dex, with the base APK's signature schemes. A split's dex files
     * are named with the split, since each split's first is classes.dex too.
     */
    private fun anatomy(files: List<ApkFile>): ApkAnatomy? {
        val read = files.mapNotNull { file ->
            try {
                file to ApkAnatomies.read(File(file.path))
            } catch (_: IOException) {
                null
            }
        }
        val base = read.firstOrNull()?.second ?: return null
        return base.copy(
            bytes = read.sumOf { it.second.bytes },
            entries = read.sumOf { it.second.entries },
            dex =
                read.flatMap { (file, anatomy) ->
                    if (file == files.first()) anatomy.dex
                    else anatomy.dex.map { it.copy(name = "${file.name}/${it.name}") }
                },
            abis = read.flatMap { it.second.abis }.distinct().sorted(),
        )
    }

    private fun permissions(
        pm: PackageManager,
        info: PackageInfo,
        installed: Boolean,
    ): List<ApkPermission> {
        val names = info.requestedPermissions.orEmpty()
        val flags = info.requestedPermissionsFlags ?: IntArray(names.size)
        return names.indices
            .map { index ->
                ApkPermission(
                    names[index],
                    protection(pm, names[index]),
                    if (installed) flags[index] and PackageInfo.REQUESTED_PERMISSION_GRANTED != 0
                    else null,
                )
            }
            .sortedBy { it.name }
    }

    private fun protection(pm: PackageManager, name: String): String? =
        try {
            when (pm.getPermissionInfo(name, 0).protection) {
                PermissionInfo.PROTECTION_DANGEROUS -> "dangerous"
                PermissionInfo.PROTECTION_NORMAL -> "normal"
                PermissionInfo.PROTECTION_SIGNATURE -> "signature"
                PermissionInfo.PROTECTION_INTERNAL -> "internal"
                else -> "other"
            }
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }

    private fun components(info: PackageInfo): List<ApkComponent> {
        fun ComponentInfo.component(kind: ApkComponent.Kind, permission: String?) =
            ApkComponent(kind, name, exported, permission, isEnabled)
        return info.activities.orEmpty().map {
            it.component(ApkComponent.Kind.Activity, it.permission)
        } +
            info.services.orEmpty().map { it.component(ApkComponent.Kind.Service, it.permission) } +
            info.receivers.orEmpty().map {
                it.component(ApkComponent.Kind.Receiver, it.permission)
            } +
            info.providers.orEmpty().map {
                it.component(ApkComponent.Kind.Provider, it.readPermission ?: it.writePermission)
            }
    }

    private fun metadata(app: ApplicationInfo): List<Pair<String, String>> {
        val bundle = app.metaData ?: return emptyList()
        return bundle.keySet().sorted().map { key ->
            @Suppress("DEPRECATION") // Values are of every type; get() is the way to read them all.
            key to bundle.get(key).toString().take(METADATA_CHARS)
        }
    }

    private const val METADATA_CHARS = 300
    private const val MANIFEST = "AndroidManifest.xml"
    private const val MAX_MANIFEST_BYTES = 8L * 1024 * 1024
}
