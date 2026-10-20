package com.droidective.platform

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Saves files the shell made to shared storage as files this app owns. The shell can write
 * Downloads itself, but a file it writes there belongs to no app, and Android refuses to let this
 * app hand it to the share sheet: the share crashed with "does not have permission". So the shell
 * leaves the file in [handover], and [publish] copies it in through MediaStore.
 */
object SharedStorage {
    enum class Place(val collection: Uri, val folder: String) {
        Downloads(MediaStore.Downloads.EXTERNAL_CONTENT_URI, Environment.DIRECTORY_DOWNLOADS),
        Movies(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            "${Environment.DIRECTORY_MOVIES}/Droidective",
        ),
    }

    /** This app's own folder on shared storage, which the shell can write and the app read. */
    fun handover(context: Context): File? =
        context.getExternalFilesDir("handover")?.also { it.mkdirs() }

    /**
     * Copies [file] into [place] under its own name, deletes [file], and returns the new entry.
     * Null when MediaStore refused the entry.
     *
     * @throws IOException when the copy fails; the half-written entry is deleted first.
     */
    suspend fun publish(context: Context, file: File, mime: String, place: Place): Uri? =
        withContext(Dispatchers.IO) {
            val resolver = context.contentResolver
            val values =
                ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
                    put(MediaStore.MediaColumns.MIME_TYPE, mime)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, place.folder)
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
            val uri = resolver.insert(place.collection, values) ?: return@withContext null
            try {
                resolver.openOutputStream(uri)?.use { out ->
                    file.inputStream().use { it.copyTo(out) }
                } ?: throw IOException("MediaStore gave no stream for $uri")
                resolver.update(
                    uri,
                    ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                    null,
                    null,
                )
                file.delete()
                uri
            } catch (e: IOException) {
                resolver.delete(uri, null, null)
                throw e
            }
        }
}
