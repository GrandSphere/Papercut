package com.grandsphere.papercut.data

import android.content.ContentResolver
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class FolioStore(private val filesDir: File) {
    fun dir(): File = File(filesDir, "folio").also { it.mkdirs() }

    fun fileFor(id: Long): File = File(dir(), id.toString())

    fun exists(id: Long): Boolean {
        val file = fileFor(id)
        return file.exists() && file.length() > 0L
    }

    fun delete(id: Long) {
        val file = fileFor(id)
        if (file.exists()) file.delete()
    }

    suspend fun copyFrom(resolver: ContentResolver, uri: Uri, id: Long): Boolean =
        withContext(Dispatchers.IO) {
            dir()
            val dest = fileFor(id)
            val tmp = File(dest.parentFile, "$id.tmp")
            val copied = runCatching {
                if (uri.scheme == "file") {
                    val path = uri.path ?: return@runCatching false
                    val src = File(path)
                    if (!src.exists() || src.length() <= 0L) return@runCatching false
                    if (src.absolutePath == dest.absolutePath) return@runCatching dest.exists()
                    src.inputStream().use { input ->
                        tmp.outputStream().use { output -> input.copyTo(output) }
                    }
                    true
                } else {
                    resolver.openInputStream(uri)?.use { input ->
                        tmp.outputStream().use { output -> input.copyTo(output) }
                    } != null
                }
            }.getOrDefault(false)
            if (copied && tmp.exists() && tmp.length() > 0L) {
                if (dest.exists()) dest.delete()
                if (tmp.renameTo(dest) && dest.exists() && dest.length() > 0L) {
                    true
                } else {
                    if (tmp.exists()) tmp.delete()
                    false
                }
            } else {
                if (tmp.exists()) tmp.delete()
                false
            }
        }
}
