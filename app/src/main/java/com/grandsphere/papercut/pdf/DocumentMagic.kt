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

    fun isReadable(context: Context, uri: Uri): Boolean {
        return runCatching {
            val pfd = if (uri.scheme == "file") {
                val path = uri.path ?: return false
                ParcelFileDescriptor.open(File(path), ParcelFileDescriptor.MODE_READ_ONLY)
            } else {
                context.contentResolver.openFileDescriptor(uri, "r")
            } ?: return false
            pfd.use { resolve(context, uri, it) }
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
        if (fromHead == PDF || fromHead == JPEG || fromHead == PNG) return fromHead
        if (fromHead == EPUB) return EPUB
        if (fromHead == XPS) return XPS
        if (fromHead == ZIP || fromHead == CBZ) {
            val zipHint = fromName ?: fromMime
            if (zipHint == EPUB || zipHint == XPS || zipHint == OXPS || zipHint == CBZ) return zipHint
            if (zipHint == CBZ_ALT) return CBZ
            return CBZ
        }
        return fromName ?: fromMime ?: fromHead ?: PDF
    }

    private fun fromHeader(head: ByteArray): String? {
        if (head.size >= 5 && head[0] == '%'.code.toByte() &&
            head[1] == 'P'.code.toByte() &&
            head[2] == 'D'.code.toByte() &&
            head[3] == 'F'.code.toByte()
        ) {
            return PDF
        }
        if (head.size >= 3 &&
            head[0] == 0xFF.toByte() &&
            head[1] == 0xD8.toByte() &&
            head[2] == 0xFF.toByte()
        ) {
            return JPEG
        }
        if (head.size >= 8 &&
            head[0] == 0x89.toByte() &&
            head[1] == 'P'.code.toByte() &&
            head[2] == 'N'.code.toByte() &&
            head[3] == 'G'.code.toByte()
        ) {
            return PNG
        }
        if (head.size >= 4 &&
            head[0] == 'P'.code.toByte() &&
            head[1] == 'K'.code.toByte() &&
            head[2] == 0x03.toByte() &&
            head[3] == 0x04.toByte()
        ) {
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
