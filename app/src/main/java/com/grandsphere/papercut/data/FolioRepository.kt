package com.grandsphere.papercut.data

import android.content.ContentResolver
import android.net.Uri
import androidx.room.RoomDatabase
import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow

class FolioRepository(
    private val db: RoomDatabase,
    private val dao: FolioDao,
    private val store: FolioStore,
    private val resolver: ContentResolver,
) {
    fun observeAll(): Flow<List<FolioItem>> = dao.observeAll()

    suspend fun getAll(): List<FolioItem> = dao.getAll()

    suspend fun getByUri(uri: String): FolioItem? = dao.getByUri(uri)

    suspend fun contains(uri: String): Boolean = dao.getByUri(uri) != null

    fun fileFor(item: FolioItem): java.io.File = store.fileFor(item.id)

    suspend fun add(
        uri: String,
        displayName: String,
        displayPath: String,
        source: Uri,
        snapshot: ViewerSettings? = null,
        pageIndex: Int = 0,
        scrollX: Float = 0f,
        scrollY: Float = 0f,
    ): FolioItem? {
        val existing = dao.getByUri(uri)
        if (existing != null) {
            ensureLocal(existing, source)
            return dao.getById(existing.id) ?: existing
        }
        val created = FolioItem(
            uri = uri,
            displayName = displayName,
            displayPath = displayPath,
            sortOrder = dao.maxSortOrder() + 1,
            zoomMode = snapshot?.zoomMode ?: ZoomMode.FIT_WIDTH.name,
            customZoomPercent = snapshot?.customZoomPercent ?: 100f,
            lockZoom = snapshot?.lockZoom ?: false,
            lockSideScroll = snapshot?.lockSideScroll ?: true,
            originalColors = snapshot?.originalColors ?: false,
            lastPageIndex = pageIndex,
            lastScrollXPdf = scrollX,
            lastScrollYPdf = scrollY,
            pageRotations = "[]",
        )
        val id = dao.insert(created)
        val row = dao.getById(id) ?: created.copy(id = id)
        val copied = store.copyFrom(resolver, source, row.id)
        if (!copied) {
            db.withTransaction {
                dao.deleteIds(listOf(row.id))
                reindex()
            }
            store.delete(row.id)
            return null
        }
        val stored = row.copy(localName = row.id.toString())
        dao.update(stored)
        return stored
    }

    suspend fun ensureLocal(item: FolioItem, fallback: Uri? = null): Boolean {
        if (store.exists(item.id)) {
            if (item.localName.isBlank()) {
                dao.update(item.copy(localName = item.id.toString()))
            }
            return true
        }
        val sources = listOfNotNull(
            fallback,
            runCatching { Uri.parse(item.uri) }.getOrNull(),
        ).distinct()
        for (uri in sources) {
            if (store.copyFrom(resolver, uri, item.id)) {
                dao.update(item.copy(localName = item.id.toString()))
                return true
            }
        }
        return false
    }

    suspend fun update(item: FolioItem) {
        dao.update(item)
    }

    suspend fun saveSnapshot(
        id: Long,
        zoomMode: String,
        customZoomPercent: Float,
        lockZoom: Boolean,
        lockSideScroll: Boolean,
        originalColors: Boolean,
        pageIndex: Int,
        scrollX: Float,
        scrollY: Float,
    ) {
        dao.updateSnapshot(
            id, zoomMode, customZoomPercent, lockZoom, lockSideScroll, originalColors,
            pageIndex, scrollX, scrollY,
        )
    }

    suspend fun saveRotations(id: Long, pageRotations: String) {
        val current = dao.getById(id) ?: return
        dao.update(current.copy(pageRotations = pageRotations))
    }

    suspend fun setHidden(ids: List<Long>, hidden: Boolean) {
        if (ids.isEmpty()) return
        dao.setHidden(ids, hidden)
    }

    suspend fun setStartItem(id: Long) {
        db.withTransaction {
            dao.clearStartItems()
            dao.markStartItem(id)
        }
    }

    suspend fun delete(ids: List<Long>) {
        if (ids.isEmpty()) return
        ids.forEach { store.delete(it) }
        db.withTransaction {
            dao.deleteIds(ids)
            reindex()
        }
    }

    suspend fun reorder(orderedIds: List<Long>) {
        db.withTransaction {
            orderedIds.forEachIndexed { index, id -> dao.setSortOrder(id, index) }
        }
    }

    private suspend fun reindex() {
        dao.getAll().forEachIndexed { index, item ->
            if (item.sortOrder != index) dao.setSortOrder(item.id, index)
        }
    }
}
