package com.droidective.android.proxy

import android.content.Context
import com.droidective.core.net.grpc.ProtoSchema
import com.droidective.core.net.proxy.ServerSchema
import java.io.File

/**
 * The gRPC schemas Network Traffic reads calls with: descriptor sets (`protoc --include_imports
 * --descriptor_set_out=app.protoset …`) in a folder on shared storage, which `adb push` reaches
 * with no permission asked, as the mocks folder does. Every file there is read, and together they
 * are one schema; a file that isn't a descriptor set is passed over.
 */
class ProtoFolder(private val context: Context) {
    /** The folder, or null when there is no shared storage to have one on. */
    fun folder(): File? = context.getExternalFilesDir(NAME)

    /**
     * Every schema in the folder together, or null when it holds none. Read again only when it
     * changes.
     */
    fun schema(): ProtoSchema? {
        val folder = folder()?.canonicalFile ?: return null
        // A link inside the folder could still point anywhere the app can read.
        val files =
            folder
                .listFiles()
                .orEmpty()
                .filter {
                    it.isFile && it.canonicalFile.parentFile == folder && it.length() <= MAX_BYTES
                }
                .sortedBy { it.name }
        val key = files.map { Triple(it.name, it.length(), it.lastModified()) }
        read?.let { (readFrom, schema) -> if (readFrom == key) return schema }
        val schema =
            files.mapNotNull { ProtoSchema.parse(it.readBytes()) }.reduceOrNull(ProtoSchema::plus)
        read = key to schema
        return schema
    }

    /**
     * Keeps [set], the descriptor set the server at [host] gave by reflection, beside the ones
     * pushed, in place of any it gave before; false when there is no shared storage to keep it on.
     */
    fun keep(host: String, set: ByteArray): Boolean {
        val folder = folder() ?: return false
        if (!folder.isDirectory && !folder.mkdirs()) return false
        val file = File(folder, ServerSchema.fileName(host))
        val temp = File(folder, ".${file.name}.part")
        temp.writeBytes(set)
        return temp.renameTo(file)
    }

    companion object {
        const val NAME = "protos"

        /**
         * A descriptor set for a large API is a few hundred kilobytes; this is past any of them.
         */
        const val MAX_BYTES = 16L * 1024 * 1024

        /** What was read last, and from which files: one read for every detail screen opened. */
        @Volatile private var read: Pair<List<Triple<String, Long, Long>>, ProtoSchema?>? = null
    }
}
