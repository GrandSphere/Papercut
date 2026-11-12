package com.grandsphere.papercut.pdf

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.system.Os
import android.system.OsConstants
import java.io.File

object DocumentMagic {
    const val PDF = "application/pdf"
    const val EPUB = "application/epub+zip"
    const val XPS = "application/vnd.ms-xpsdocument"
    const val OXPS = "application/oxps"
    const val CBZ = "application/vnd.comicbook+zip"
    const val CBZ_ALT = "application/x-cbz"
    const val JPEG = "image/jpeg"
    const val PNG = "image/png"
    const val ZIP = "application/zip"

    val OPEN_MIME_TYPES: Array<String> = arrayOf(
        PDF,
        EPUB,
        XPS,
        OXPS,
        CBZ,
        CBZ_ALT,
        ZIP,
        JPEG,
        PNG,
    )

    fun persistRead(context: Context, uri: Uri) {
        if (uri.scheme != "content") return
        try {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        } catch (_: SecurityException) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            } catch (_: SecurityException) {
            }
        }
    }

    fun openDescriptor(context: Context, uri: Uri): ParcelFileDescriptor {
        if (uri.scheme == "file") {
            val path = uri.path ?: error("Unable to open $uri")
            return ParcelFileDescriptor.open(File(path), ParcelFileDescriptor.MODE_READ_ONLY)
        }
        return context.contentResolver.openFileDescriptor(uri, "r")
            ?: error("Unable to open $uri")
    }

    fun isReadable(context: Context, uri: Uri): Boolean {
        return runCatching {
            openDescriptor(context, uri).use { resolve(context, uri, it) }
            true
        }.getOrDefault(false)
    }

    fun resolve(context: Context, uri: Uri, pfd: ParcelFileDescriptor): String {
        val mime = runCatching { context.contentResolver.getType(uri) }.getOrNull()
        val name = queryName(context, uri) ?: uri.lastPathSegment
        return pick(peek(pfd), mime, name)
    }

    fun resolve(file: File, pfd: ParcelFileDescriptor): String {
        return pick(peek(pfd), null, file.name)
    }

    fun shareType(magic: String): String = when (magic) {
        CBZ_ALT -> CBZ
        ZIP -> CBZ
        else -> magic.ifBlank { PDF }
    }

    private fun pick(head: ByteArray, mime: String?, name: String?): String {
        val fromHead = fromHeader(head)
        val fromName = fromName(name)
        val fromMime = fromMime(mime)
        if (fromHead != null && fromHead != ZIP) return fromHead
        if (fromHead == ZIP) {
            val hint = fromName ?: fromMime
            return if (hint != null && (hint == EPUB || hint == XPS || hint == OXPS || hint == CBZ)) hint else CBZ
        }
        return fromName ?: fromMime ?: fromHead ?: PDF
    }

    private fun ByteArray.startsWith(minSize: Int, vararg prefix: Int): Boolean {
        if (size < minSize) return false
        for (i in prefix.indices) if (this[i] != prefix[i].toByte()) return false
        return true
    }

    private fun fromHeader(head: ByteArray): String? {
        if (head.startsWith(5, 0x25, 0x50, 0x44, 0x46)) return PDF
        if (head.startsWith(3, 0xFF, 0xD8, 0xFF)) return JPEG
        if (head.startsWith(8, 0x89, 0x50, 0x4E, 0x47)) return PNG
        if (head.startsWith(4, 0x50, 0x4B, 0x03, 0x04)) {
            val text = head.toString(Charsets.ISO_8859_1)
            if (text.contains("application/epub+zip") || text.contains("mimetypeapplication/epub")) {
                return EPUB
            }
            if (text.contains("FixedDocument") || text.contains("vnd.ms-package.xps") ||
                text.contains("[Content_Types].xml")
            ) {
                return XPS
            }
            return ZIP
        }
        return null
    }

    private fun fromName(name: String?): String? {
        val ext = name?.substringAfterLast('.', "")?.lowercase()?.trim().orEmpty()
        return when (ext) {
            "pdf" -> PDF
            "epub" -> EPUB
            "xps" -> XPS
            "oxps" -> OXPS
            "cbz" -> CBZ
            "jpg", "jpeg" -> JPEG
            "png" -> PNG
            else -> null
        }
    }

    private fun fromMime(mime: String?): String? {
        val t = mime?.lowercase()?.trim().orEmpty()
        return when {
            t.isEmpty() || t == "application/octet-stream" -> null
            t == PDF || t.startsWith("application/pdf") -> PDF
            t == EPUB -> EPUB
            t == XPS || t == OXPS -> t
            t == CBZ || t == CBZ_ALT -> CBZ
            t == ZIP -> ZIP
            t == JPEG || t == "image/jpg" -> JPEG
            t == PNG -> PNG
            else -> null
        }
    }

    private fun queryName(context: Context, uri: Uri): String? {
        return runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
                }
        }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    private fun peek(pfd: ParcelFileDescriptor): ByteArray {
        val fd = pfd.fileDescriptor
        val start = Os.lseek(fd, 0, OsConstants.SEEK_CUR)
        Os.lseek(fd, 0, OsConstants.SEEK_SET)
        val buf = ByteArray(1024)
        var read = 0
        while (read < buf.size) {
            val n = Os.read(fd, buf, read, buf.size - read)
            if (n <= 0) break
            read += n
        }
        Os.lseek(fd, start, OsConstants.SEEK_SET)
        return if (read == buf.size) buf else buf.copyOf(read)
    }
}
