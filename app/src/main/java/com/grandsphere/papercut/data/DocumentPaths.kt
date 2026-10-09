package com.grandsphere.papercut.data

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import java.net.URLDecoder

object DocumentPaths {
    data class Label(val displayName: String, val displayPath: String)

    fun label(context: Context, uri: Uri): Label {
        val name = queryName(context, uri)
            ?: fileNameFromUri(uri)
            ?: "document.pdf"
        return Label(name, friendlyFolder(context, uri, name))
    }

    fun fromUriString(uriString: String, storedName: String? = null, storedPath: String? = null): Label {
        val uri = runCatching { Uri.parse(fullyDecode(uriString)) }.getOrNull()
        val name = storedName?.takeIf { !isUgly(it) && looksLikeFileName(it) }
            ?: uri?.let { fileNameFromUri(it) }
            ?: fileNameOf(fullyDecode(storedName ?: uriString))
            ?: "document.pdf"
        val path = storedPath?.takeIf { !needsRebuild(it, name) }?.let { folderOf(it, name) }
            ?: uri?.let { friendlyFromParsed(it, name) }
            ?: folderOf(fullyDecode(uriString), name)
        return Label(name, path)
    }

    fun isUgly(value: String): Boolean {
        val t = value.trim()
        if (t.isEmpty()) return true
        if (t.contains('%')) return true
        if (t.contains("://")) return true
        if (t.startsWith("content:", ignoreCase = true)) return true
        if (t.startsWith("file:", ignoreCase = true)) return true
        if (t.contains("com.android.providers")) return true
        if (t.contains("/storage/emulated/", ignoreCase = true)) return true
        if (t.startsWith("/storage/", ignoreCase = true)) return true
        return false
    }

    fun needsRebuild(path: String, name: String): Boolean {
        if (isUgly(path)) return true
        val folder = folderOf(path, name)
        if (folder.isEmpty()) return true
        if (folder.equals(name, ignoreCase = true)) return true
        return false
    }

    private fun queryName(context: Context, uri: Uri): String? {
        return runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
                }
        }.getOrNull()?.takeIf { it.isNotBlank() && !isUgly(it) }
    }

    private fun queryRelativePath(context: Context, uri: Uri): String? {
        return runCatching {
            context.contentResolver.query(uri, arrayOf("relative_path"), null, null, null)?.use { cursor ->
                val idx = cursor.getColumnIndex("relative_path")
                if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
            }
        }.getOrNull()?.trim()?.trim('/')?.takeIf { it.isNotBlank() }
    }

    private fun friendlyFolder(context: Context, uri: Uri, name: String): String {
        val relative = queryRelativePath(context, uri)
        if (!relative.isNullOrBlank()) {
            val folder = folderOf(relative, name)
            if (folder.isNotEmpty() && !needsRebuild(folder, name)) return folder
        }
        if (DocumentsContract.isDocumentUri(context, uri)) {
            val id = fullyDecode(runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull().orEmpty())
            val mapped = folderFromDocumentId(id, name, uri)
            if (mapped.isNotEmpty() && !needsRebuild(mapped, name)) return mapped
        }
        return friendlyFromParsed(uri, name)
    }

    private fun friendlyFromParsed(uri: Uri, name: String): String {
        if (uri.scheme == "file") {
            val folder = folderOf(uri.path.orEmpty(), name)
            if (folder.isNotEmpty() && !isUgly(folder)) return folder
        }
        val decoded = fullyDecode(uri.toString())
        val parsed = runCatching { Uri.parse(decoded) }.getOrDefault(uri)
        val id = fullyDecode(
            runCatching { DocumentsContract.getDocumentId(parsed) }.getOrNull()
                ?: parsed.lastPathSegment.orEmpty(),
        )
        val fromId = folderFromDocumentId(id, name, parsed)
        if (fromId.isNotEmpty() && !isUgly(fromId) && !fromId.equals(name, ignoreCase = true)) return fromId
        val fromPath = folderOf(fullyDecode(parsed.path.orEmpty()), name)
        if (fromPath.isNotEmpty() && !isUgly(fromPath) && !fromPath.equals(name, ignoreCase = true)) return fromPath
        return authorityFolder(parsed)
    }

    private fun folderFromDocumentId(id: String, name: String, uri: Uri): String {
        val raw = fullyDecode(id)
        val rest = if (raw.contains(':')) raw.substringAfter(':') else raw
        val cleaned = rest
            .replace('\\', '/')
            .removePrefix("raw:")
            .removePrefix("/")
        if (cleaned.startsWith("storage/") || cleaned.startsWith("/storage/")) {
            return folderOf(cleaned, name)
        }
        val mapped = folderOf(cleaned, name)
        if (mapped.isNotEmpty()) return mapped
        if (uri.authority?.contains("downloads", ignoreCase = true) == true) return "Downloads"
        return ""
    }

    private fun folderOf(raw: String, name: String): String {
        var path = fullyDecode(raw).replace('\\', '/')
        path = path.substringAfter("://")
        if (path.contains(':') && !path.startsWith("/")) {
            val after = path.substringAfter(':', path)
            if (after.isNotBlank()) path = after
        }
        path = stripStorageRoot(path).trim('/')
        path = path.replaceFirst(Regex("(?i)^Download/"), "Downloads/")
        path = path.replaceFirst(Regex("(?i)^Download$"), "Downloads")
        if (path.isBlank() || isUgly(path)) return ""
        val last = path.substringAfterLast('/')
        if (looksLikeFileName(name) && last.equals(name, ignoreCase = true)) {
            path = if (path.contains('/')) path.substringBeforeLast('/') else ""
        } else if (looksLikeFileName(last) && last.contains('.')) {
            path = if (path.contains('/')) path.substringBeforeLast('/') else ""
        }
        path = path.trim('/')
        if (path.isBlank() || path.equals(name, ignoreCase = true) || isUgly(path)) return ""
        return path
    }

    private fun stripStorageRoot(raw: String): String {
        var path = raw.trim('/')
        val markers = listOf(
            "storage/emulated/0/",
            "/storage/emulated/0/",
            "sdcard/",
            "/sdcard/",
        )
        for (marker in markers) {
            val idx = path.indexOf(marker.trimStart('/'), ignoreCase = true)
            if (idx >= 0) {
                return path.substring(idx + marker.trimStart('/').length)
            }
        }
        val sd = Regex("(?i)^/?storage/[0-9A-F]{4}-[0-9A-F]{4}/").find(path)
        if (sd != null) return path.substring(sd.range.last + 1)
        val sdMid = Regex("(?i)(?:^|/)storage/[0-9A-F]{4}-[0-9A-F]{4}/").find(path)
        if (sdMid != null) return path.substring(sdMid.range.last + 1)
        return path
    }

    private fun fileNameFromUri(uri: Uri): String? {
        val decodedSeg = fullyDecode(uri.lastPathSegment.orEmpty())
        val fromSeg = fileNameOf(decodedSeg)
        if (fromSeg != null) return fromSeg
        val fromPath = fileNameOf(fullyDecode(uri.path.orEmpty()))
        if (fromPath != null) return fromPath
        return fileNameOf(fullyDecode(uri.toString()))
    }

    private fun fileNameOf(value: String): String? {
        val cleaned = fullyDecode(value).replace('\\', '/').substringAfterLast('/').substringAfterLast(':')
        return cleaned.takeIf { looksLikeFileName(it) }
    }

    private fun looksLikeFileName(value: String): Boolean {
        val t = value.trim()
        return t.isNotEmpty() && !t.contains('/') && !isUgly(t)
    }

    private fun authorityFolder(uri: Uri): String = when {
        uri.authority?.contains("downloads", ignoreCase = true) == true -> "Downloads"
        uri.authority?.contains("externalstorage", ignoreCase = true) == true -> "Storage"
        uri.authority?.contains("media", ignoreCase = true) == true -> "Media"
        else -> "Storage"
    }

    private fun fullyDecode(text: String): String {
        var current = text
        repeat(4) {
            if (!current.contains('%')) return current
            val next = runCatching { URLDecoder.decode(current, Charsets.UTF_8.name()) }.getOrDefault(current)
            if (next == current) return current
            current = next
        }
        return current
    }
}
