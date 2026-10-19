package com.droidective.platform

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.os.LocaleList
import com.droidective.core.apps.bundles.AppPackageFormat
import com.droidective.core.apps.bundles.DeviceSpec
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * Installs an APK, or the chosen splits of a bundle, through Android's PackageInstaller, which asks
 * the person to confirm. Archives are read straight from the picked file in passes, never copied,
 * since a game's bundle can run to gigabytes.
 */
class AppInstaller(private val context: Context) {
    private val resolver = context.contentResolver

    /**
     * Whether Droidective may install apps: Settings ▸ Install unknown apps, which Android asks.
     */
    fun allowed(): Boolean = context.packageManager.canRequestPackageInstalls()

    /** Every file in the archive at [uri], in archive order. */
    fun entries(uri: Uri): List<String> =
        zip(uri) { input ->
            generateSequence { input.nextEntry }
                .filterNot { it.isDirectory }
                .map { it.name }
                .toList()
        }

    /**
     * One small file from the archive, such as a bundle's manifest or bundletool's `toc.pb`; null
     * when it's absent or larger than a megabyte.
     */
    fun readBytes(uri: Uri, name: String): ByteArray? =
        zip(uri) { input ->
            generateSequence { input.nextEntry }
                .firstOrNull { it.name == name }
                ?.let { input.readCapped(MAX_SMALL_FILE_BYTES) }
        }

    /**
     * Writes [entries] of the bundle at [uri] (or, for a plain APK, the file itself) into one
     * install session and commits it; Android reports to [result], filled in with the session id as
     * [EXTRA_SESSION]. [progress] hears how many files are written.
     *
     * @return the session id.
     * @throws IOException when the file can't be read or the session refuses a write.
     */
    fun install(
        uri: Uri,
        format: AppPackageFormat,
        entries: List<String>,
        result: Intent,
        progress: (written: Int, total: Int) -> Unit,
    ): Int {
        val installer = context.packageManager.packageInstaller
        val params =
            PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        val id = installer.createSession(params)
        try {
            installer.openSession(id).use { session ->
                if (format == AppPackageFormat.Apk) {
                    open(uri).use { session.write("base.apk", it) }
                    progress(1, 1)
                } else {
                    writeEntries(uri, entries, session, progress)
                }
                val pending =
                    PendingIntent.getBroadcast(
                        context,
                        id,
                        result.putExtra(EXTRA_SESSION, id),
                        PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                    )
                session.commit(pending.intentSender)
            }
        } catch (e: IOException) {
            installer.abandonSession(id)
            throw e
        } catch (e: SecurityException) {
            installer.abandonSession(id)
            throw IOException("Android refused the install: ${e.message}", e)
        }
        return id
    }

    private fun writeEntries(
        uri: Uri,
        entries: List<String>,
        session: PackageInstaller.Session,
        progress: (Int, Int) -> Unit,
    ) {
        val wanted = entries.toSet()
        var written = 0
        zip(uri) { input ->
            generateSequence { input.nextEntry }
                .filter { it.name in wanted }
                .forEach { entry ->
                    // Session file names must be unique; the archive path makes them so.
                    session.write(entry.name.replace('/', '_'), input, entry.size)
                    progress(++written, wanted.size)
                }
        }
        if (written != wanted.size) {
            throw IOException("The file changed while it was read: $written of ${wanted.size} APKs")
        }
    }

    private fun PackageInstaller.Session.write(name: String, input: InputStream, size: Long = -1) {
        openWrite(name, 0, size).use { out ->
            input.copyTo(out)
            fsync(out)
        }
    }

    private fun open(uri: Uri): InputStream =
        resolver.openInputStream(uri) ?: throw IOException("Couldn't open the file")

    private fun <T> zip(uri: Uri, read: (ZipInputStream) -> T): T =
        ZipInputStream(open(uri).buffered()).use(read)

    /** Up to [max] bytes, or null when there are more. A loop: `readNBytes` is API 33+. */
    private fun InputStream.readCapped(max: Int): ByteArray? {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(BUFFER_BYTES)
        while (true) {
            val count = read(buffer)
            if (count < 0) return out.toByteArray()
            out.write(buffer, 0, count)
            if (out.size() > max) return null
        }
    }

    companion object {
        const val EXTRA_SESSION = "com.droidective.android.extra.INSTALL_SESSION"
        private const val MAX_SMALL_FILE_BYTES = 1 shl 20
        private const val BUFFER_BYTES = 8 * 1024

        /** This phone's ABIs, screen density and languages, for picking a bundle's splits. */
        fun thisPhone(context: Context): DeviceSpec {
            val locales = LocaleList.getDefault()
            return DeviceSpec(
                abis = Build.SUPPORTED_ABIS.toList(),
                densityDpi = context.resources.displayMetrics.densityDpi,
                languages = (0 until locales.size()).map { locales[it].language }.distinct(),
            )
        }
    }
}
