package com.droidective.android.proxy

import android.content.Context
import com.droidective.core.net.proxy.Mock
import com.droidective.core.net.proxy.MockFiles
import java.io.File
import java.io.IOException

/**
 * The folder mocks answer from, in the app's own space on shared storage: Droidective reads it
 * without any permission, and `adb push` can put a fixture there.
 */
class MockFolder(private val context: Context) : MockFiles {
    /** The folder, or null when there is no shared storage to have one on. */
    fun folder(): File? = context.getExternalFilesDir(NAME)

    /** The file [name] names, when it is one that can be read: there, inside, small enough. */
    fun find(name: String): File? {
        val folder = folder()?.canonicalFile ?: return null
        if (!Mock.isFileName(name)) return null
        val file = File(folder, name).canonicalFile
        // A link inside the folder could still point anywhere the app can read.
        if (!file.path.startsWith(folder.path + File.separator) || !file.isFile) return null
        return file
    }

    override fun read(name: String): ByteArray {
        val folder =
            folder() ?: throw IOException("there is no shared storage for the mocks folder")
        if (!Mock.isFileName(name)) throw IOException("\"$name\" isn't a file in the mocks folder")
        val file = find(name) ?: throw IOException("there is no $name in ${folder.path}")
        if (file.length() > MAX_BYTES) throw IOException("$name is over ${MAX_BYTES / MEGABYTE} MB")
        return file.readBytes()
    }

    companion object {
        const val NAME = "mocks"

        /** As much as one mocked answer is: it is read whole, every time it answers. */
        const val MAX_BYTES = 8L * 1024 * 1024
        private const val MEGABYTE = 1024 * 1024
    }
}
