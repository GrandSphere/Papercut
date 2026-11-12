package com.grandsphere.papercut.data

import android.content.ContentResolver
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream

/**
 * Copies a stream to [dest] through [tmp] so a partial copy never replaces a good file.
 * Returns true only when a non-empty [dest] exists afterwards.
 */
internal suspend fun copyAtomically(dest: File, tmp: File, open: () -> InputStream?): Boolean =
    withContext(Dispatchers.IO) {
        val copied = runCatching {
            open()?.use { input -> tmp.outputStream().use { input.copyTo(it) } } != null
        }.getOrDefault(false)
        if (copied && tmp.length() > 0L) {
            dest.delete()
            if (tmp.renameTo(dest) && dest.length() > 0L) return@withContext true
        }
        tmp.delete()
        false
    }

class FolioStore(private val filesDir: File) {

    fun dir(): File = File(filesDir, "folio").also { it.mkdirs() }

    fun fileFor(id: Long): File = File(dir(), id.toString())

    fun exists(id: Long): Boolean {
        val file = fileFor(id)
        return file.exists() && file.length() > 0L
    }

    fun delete(id: Long) {
        fileFor(id).delete()
    }

    suspend fun copyFrom(resolver: ContentResolver, uri: Uri, id: Long): Boolean {
        val dest = fileFor(id)
        val tmp = File(dest.parentFile, "$id.tmp")
        if (uri.scheme == "file") {
            val src = uri.path?.let { File(it) } ?: return false
            if (!src.exists() || src.length() <= 0L) return false
            if (src.absolutePath == dest.absolutePath) return false
            return copyAtomically(dest, tmp) { src.inputStream() }
        }
        return copyAtomically(dest, tmp) { resolver.openInputStream(uri) }
    }
}
