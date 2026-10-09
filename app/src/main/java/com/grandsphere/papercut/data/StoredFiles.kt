package com.grandsphere.papercut.data

import android.content.Context
import java.io.File

enum class StoredUse {
    LastPdf,
    Folio,
    ExportCache,
    Unused,
}

data class StoredEntry(
    val path: String,
    val title: String,
    val status: String,
    val use: StoredUse,
    val folioId: Long? = null,
) {
    val inDatabase: Boolean
        get() = use == StoredUse.LastPdf || use == StoredUse.Folio
}

object StoredFiles {
    private val ignoredNames = setOf("rlist", "profileinstalled")

    fun list(
        context: Context,
        items: List<FolioItem>,
        lastPdfTitle: String?,
    ): List<StoredEntry> {
        val stored = AppFiles.stored(context)
        val temp = AppFiles.temp(context)
        val byId = items.associateBy { it.id }
        val found = ArrayList<StoredEntry>()
        walk(stored) { file -> found += classify(file, stored, byId, lastPdfTitle) }
        walk(temp) { file -> found += classify(file, stored, byId, lastPdfTitle) }
        return found.sortedWith(compareBy({ it.status }, { it.title.lowercase() }))
    }

    private fun walk(root: File, onFile: (File) -> Unit) {
        if (!root.isDirectory) return
        root.listFiles()?.forEach { child ->
            if (child.isDirectory && child.name == "folio") {
                child.listFiles()?.forEach { file ->
                    if (file.isFile && !ignored(file)) onFile(file)
                }
            } else if (child.isFile && !ignored(child)) {
                onFile(child)
            }
        }
    }

    private fun ignored(file: File): Boolean {
        val name = file.name
        val lower = name.lowercase()
        if (lower in ignoredNames) return true
        if (lower.endsWith(".lck")) return true
        if (lower.startsWith("papercut.db")) return true
        return false
    }

    private fun classify(
        file: File,
        stored: File,
        byId: Map<Long, FolioItem>,
        lastPdfTitle: String?,
    ): StoredEntry {
        val inFolioDir = file.parentFile?.name == "folio" && file.parentFile?.parentFile == stored
        if (!inFolioDir && file.parentFile == stored && file.name == AppFiles.LAST_PDF) {
            return StoredEntry(
                path = file.absolutePath,
                title = lastPdfTitle?.takeIf { it.isNotBlank() } ?: file.name,
                status = "Last PDF",
                use = StoredUse.LastPdf,
            )
        }
        if (inFolioDir) {
            val id = file.name.toLongOrNull()
            val item = id?.let { byId[it] }
            if (item != null) {
                return StoredEntry(
                    path = file.absolutePath,
                    title = item.displayName.ifBlank { file.name },
                    status = "Folio item ${item.displayName}",
                    use = StoredUse.Folio,
                    folioId = item.id,
                )
            }
        }
        if (file.name == AppFiles.EXPORT_PDF) {
            return StoredEntry(
                path = file.absolutePath,
                title = file.name,
                status = "Export cache",
                use = StoredUse.ExportCache,
            )
        }
        return StoredEntry(
            path = file.absolutePath,
            title = file.name,
            status = "Unused",
            use = StoredUse.Unused,
        )
    }
}
