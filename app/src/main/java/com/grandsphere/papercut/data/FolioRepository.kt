package com.grandsphere.papercut.data

import android.content.ContentResolver
import android.net.Uri
import kotlinx.coroutines.flow.Flow

class FolioRepository(
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
        )
        val id = dao.insert(created)
        val row = dao.getById(id) ?: created.copy(id = id)
        val copied = store.copyFrom(resolver, source, row.id)
        if (!copied) {
            dao.deleteIds(listOf(row.id))
            store.delete(row.id)
            reindex()
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
        val current = dao.getById(id) ?: return
        dao.update(
            current.copy(
                zoomMode = zoomMode,
                customZoomPercent = customZoomPercent,
                lockZoom = lockZoom,
                lockSideScroll = lockSideScroll,
                originalColors = originalColors,
                lastPageIndex = pageIndex,
                lastScrollXPdf = scrollX,
                lastScrollYPdf = scrollY,
            ),
        )
    }

    suspend fun setHidden(ids: List<Long>, hidden: Boolean) {
        ids.forEach { id ->
            val row = dao.getById(id) ?: return@forEach
            if (row.hidden != hidden) dao.update(row.copy(hidden = hidden))
        }
    }

    suspend fun setStartItem(id: Long) {
        dao.clearStartItems()
        val row = dao.getById(id) ?: return
        dao.update(row.copy(isStartItem = true))
    }

    suspend fun delete(ids: List<Long>) {
        if (ids.isEmpty()) return
        ids.forEach { store.delete(it) }
        dao.deleteIds(ids)
        reindex()
    }

    suspend fun reorder(orderedIds: List<Long>) {
        val all = dao.getAll().associateBy { it.id }
        orderedIds.forEachIndexed { index, id ->
            val row = all[id] ?: return@forEachIndexed
            if (row.sortOrder != index) dao.update(row.copy(sortOrder = index))
        }
    }

    private suspend fun reindex() {
        dao.getAll().forEachIndexed { index, item ->
            if (item.sortOrder != index) dao.update(item.copy(sortOrder = index))
        }
    }
}
