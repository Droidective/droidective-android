package com.droidective.core.store

import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * One value kept as a JSON file, the Kotlin counterpart of the Mac's JSONStore. Writes are atomic
 * (a temp file renamed over the old one), and a file that won't parse is set aside as `.corrupt`
 * instead of being overwritten, so a bad write never costs someone their layouts.
 */
class JsonFileStore<T>(
    private val file: File,
    private val serializer: KSerializer<T>,
    private val default: () -> T,
) {
    @Synchronized
    fun load(): T {
        if (!file.exists()) return default()
        return try {
            json.decodeFromString(serializer, file.readText())
        } catch (e: SerializationException) {
            setAside(e)
        } catch (e: IllegalArgumentException) {
            setAside(e)
        }
    }

    @Synchronized
    fun save(value: T) {
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, ".${file.name}.tmp")
        temp.writeText(json.encodeToString(serializer, value))
        try {
            Files.move(
                temp.toPath(),
                file.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    @Synchronized
    fun delete() {
        if (file.exists() && !file.delete()) throw IOException("Couldn't delete ${file.path}")
    }

    private fun setAside(cause: Exception): T {
        val corrupt = File(file.parentFile, "${file.name}.corrupt")
        if (!file.renameTo(corrupt)) {
            throw IOException("${file.path} is unreadable and couldn't be set aside", cause)
        }
        return default()
    }

    companion object {
        val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            prettyPrint = true
        }
    }
}
