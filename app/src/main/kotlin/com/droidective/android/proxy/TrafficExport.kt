package com.droidective.android.proxy

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.droidective.android.BuildConfig
import com.droidective.core.net.proxy.Flow
import com.droidective.core.net.proxy.Har
import com.droidective.platform.SharedStorage
import java.io.File
import java.io.IOException
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Flows out of the phone as a HAR file: saved to Downloads, then handed to the share sheet. */
object TrafficExport {
    /** A file saved to Downloads, and the name it was saved under. */
    data class Saved(val uri: Uri, val name: String)

    /**
     * Writes [flows], oldest first, to Downloads.
     *
     * @return null when there is no shared storage or MediaStore refused the entry.
     * @throws IOException when writing or copying the file fails.
     */
    suspend fun save(context: Context, flows: List<Flow>): Saved? {
        val folder = SharedStorage.handover(context) ?: return null
        val name = "droidective-${STAMP.format(LocalDateTime.now())}.har"
        val file = File(folder, name)
        withContext(Dispatchers.IO) {
            file.bufferedWriter().use { Har.write(flows, BuildConfig.VERSION_NAME, it) }
        }
        // Stored as plain bytes: under a JSON type MediaStore would add ".json" to the name.
        val uri = SharedStorage.publish(context, file, STORED_AS, SharedStorage.Place.Downloads)
        return uri?.let { Saved(it, name) }
    }

    /** The share sheet for a saved file. */
    fun share(saved: Saved): Intent {
        val send =
            Intent(Intent.ACTION_SEND)
                .setType(SHARED_AS)
                .putExtra(Intent.EXTRA_STREAM, saved.uri)
                .putExtra(Intent.EXTRA_TITLE, saved.name)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        send.clipData = ClipData(saved.name, arrayOf(SHARED_AS), ClipData.Item(saved.uri))
        return Intent.createChooser(send, "Share the traffic")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    private val STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
    private const val STORED_AS = "application/octet-stream"
    private const val SHARED_AS = "application/json"
}
