package com.grandsphere.papercut.data

import androidx.room.RoomDatabase
import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow

class BookmarkRepository(
    private val db: RoomDatabase,
    private val documents: DocumentDao,
    private val bookmarks: BookmarkDao,
) {
    fun observeDocuments(): Flow<List<PdfDocument>> = documents.observeAll()

    fun observeBookmarks(uri: String): Flow<List<Bookmark>> = bookmarks.observeForDocument(uri)

    suspend fun getDocument(uri: String): PdfDocument? = documents.get(uri)

    suspend fun findBookmarkByName(uri: String, name: String): Bookmark? = bookmarks.findByName(uri, name)

    suspend fun findByNameAndPath(name: String, path: String): PdfDocument? {
        if (name.isBlank()) return null
        return documents.getByLabel(name, path)
    }

    suspend fun ensureDocument(uri: String, displayName: String, displayPath: String): PdfDocument {
        val label = DocumentPaths.fromUriString(uri, displayName, displayPath)
        val existing = documents.get(uri)
        if (existing != null) {
            if (existing.displayName != label.displayName || existing.displayPath != label.displayPath) {
                val updated = existing.copy(displayName = label.displayName, displayPath = label.displayPath)
                documents.update(updated)
                return updated
            }
            return existing
        }
        val created = PdfDocument(uri = uri, displayName = label.displayName, displayPath = label.displayPath)
        documents.insertIgnore(created)
        return documents.get(uri) ?: created
    }

    suspend fun repairLabels(resolve: (String) -> DocumentPaths.Label) {
        documents.getAll().forEach { doc ->
            val resolved = resolve(doc.uri)
            val merged = DocumentPaths.fromUriString(
                doc.uri,
                if (!DocumentPaths.isUgly(doc.displayName)) doc.displayName else resolved.displayName,
                if (!DocumentPaths.needsRebuild(doc.displayPath, doc.displayName)) {
                    doc.displayPath
                } else {
                    resolved.displayPath
                },
            )
            if (merged.displayName != doc.displayName || merged.displayPath != doc.displayPath) {
                documents.update(doc.copy(displayName = merged.displayName, displayPath = merged.displayPath))
            }
        }
    }

    suspend fun saveLastPosition(uri: String, page: Int, scrollX: Float, scrollY: Float) {
        if (documents.updatePosition(uri, page, scrollX, scrollY) > 0) return
        val label = DocumentPaths.fromUriString(uri)
        documents.insertIgnore(
            PdfDocument(
                uri = uri,
                displayName = label.displayName,
                displayPath = label.displayPath,
                lastPageIndex = page,
                lastScrollXPdf = scrollX,
                lastScrollYPdf = scrollY,
            ),
        )
    }

    suspend fun addBookmark(
        uri: String,
        name: String,
        pageIndex: Int,
        scrollX: Float,
        scrollY: Float,
    ): Bookmark {
        val order = bookmarks.maxSortOrder(uri) + 1
        val row = Bookmark(
            documentUri = uri,
            name = name,
            pageIndex = pageIndex,
            scrollXPdf = scrollX,
            scrollYPdf = scrollY,
            sortOrder = order,
        )
        val id = bookmarks.insert(row)
        return row.copy(id = id)
    }

    suspend fun upsertNamed(
        uri: String,
        name: String,
        pageIndex: Int,
        scrollX: Float,
        scrollY: Float,
    ): Bookmark {
        val existing = bookmarks.findByName(uri, name)
        if (existing != null) {
            val next = existing.copy(
                pageIndex = pageIndex,
                scrollXPdf = scrollX,
                scrollYPdf = scrollY,
            )
            bookmarks.update(next)
            return next
        }
        val order = bookmarks.maxSortOrder(uri) + 1
        val row = Bookmark(
            documentUri = uri,
            name = name,
            pageIndex = pageIndex,
            scrollXPdf = scrollX,
            scrollYPdf = scrollY,
            sortOrder = order,
        )
        val id = bookmarks.insert(row)
        return row.copy(id = id)
    }

    suspend fun updateBookmark(bookmark: Bookmark) {
        val trimmed = bookmark.copy(name = bookmark.name.trim().ifBlank { "Quick Bookmark" })
        if (JumpBookmarks.isJumpName(trimmed.name) && trimmed.id != 0L) {
            bookmarks.deleteOthersWithName(trimmed.documentUri, trimmed.name, trimmed.id)
        }
        bookmarks.update(trimmed)
    }

    suspend fun deleteBookmarks(ids: List<Long>) {
        if (ids.isEmpty()) return
        bookmarks.deleteIds(ids)
    }

    suspend fun reorder(orderedIds: List<Long>) {
        db.withTransaction {
            orderedIds.forEachIndexed { index, id -> bookmarks.setSortOrder(id, index) }
        }
    }

    suspend fun deleteDocuments(uris: List<String>) {
        if (uris.isEmpty()) return
        documents.delete(uris)
    }

    suspend fun clearAllBookmarks() {
        bookmarks.deleteAll()
    }

    suspend fun snapshot(kind: BackupJson.Kind, settings: ViewerSettings): String {
        val docs = if (kind == BackupJson.Kind.Settings) emptyList() else documents.getAll()
        val marks = if (kind == BackupJson.Kind.Settings) emptyList() else bookmarks.getAll()
        return BackupJson.export(kind, settings, docs, marks)
    }

    suspend fun importParsed(parsed: BackupJson.Parsed) {
        db.withTransaction { importParsedLocked(parsed) }
    }

    private suspend fun importParsedLocked(parsed: BackupJson.Parsed) {
        parsed.documents?.forEach { incoming ->
            val existing = documents.get(incoming.uri)
            if (existing == null) {
                documents.upsert(incoming)
            } else {
                documents.update(
                    existing.copy(
                        displayName = incoming.displayName.ifBlank { existing.displayName },
                        displayPath = incoming.displayPath.ifBlank { existing.displayPath },
                        lastPageIndex = incoming.lastPageIndex,
                        lastScrollXPdf = incoming.lastScrollXPdf,
                        lastScrollYPdf = incoming.lastScrollYPdf,
                    ),
                )
            }
        }
        parsed.bookmarks?.forEach { incoming ->
            if (documents.get(incoming.documentUri) == null) {
                val label = DocumentPaths.fromUriString(incoming.documentUri)
                documents.upsert(
                    PdfDocument(
                        uri = incoming.documentUri,
                        displayName = label.displayName,
                        displayPath = label.displayPath,
                    ),
                )
            }
            val nextOrder = bookmarks.maxSortOrder(incoming.documentUri) + 1
            bookmarks.insert(incoming.copy(id = 0, sortOrder = nextOrder))
        }
    }
}
