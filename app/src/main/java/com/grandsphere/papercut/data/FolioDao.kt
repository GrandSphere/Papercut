package com.grandsphere.papercut.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface FolioDao {
    @Query("SELECT * FROM folio_items ORDER BY sortOrder ASC, id ASC")
    fun observeAll(): Flow<List<FolioItem>>

    @Query("SELECT * FROM folio_items ORDER BY sortOrder ASC, id ASC")
    suspend fun getAll(): List<FolioItem>

    @Query("SELECT * FROM folio_items WHERE uri = :uri LIMIT 1")
    suspend fun getByUri(uri: String): FolioItem?

    @Query("SELECT * FROM folio_items WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): FolioItem?

    @Query("SELECT COALESCE(MAX(sortOrder), -1) FROM folio_items")
    suspend fun maxSortOrder(): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(item: FolioItem): Long

    @Update
    suspend fun update(item: FolioItem)

    @Query("DELETE FROM folio_items WHERE id IN (:ids)")
    suspend fun deleteIds(ids: List<Long>)

    @Query("UPDATE folio_items SET isStartItem = 0")
    suspend fun clearStartItems()

    @Query("UPDATE folio_items SET isStartItem = 1 WHERE id = :id")
    suspend fun markStartItem(id: Long)

    @Query("UPDATE folio_items SET hidden = :hidden WHERE id IN (:ids)")
    suspend fun setHidden(ids: List<Long>, hidden: Boolean)

    @Query("UPDATE folio_items SET sortOrder = :order WHERE id = :id")
    suspend fun setSortOrder(id: Long, order: Int)

    @Query(
        "UPDATE folio_items SET zoomMode = :zoomMode, customZoomPercent = :customZoomPercent, " +
            "lockZoom = :lockZoom, lockSideScroll = :lockSideScroll, originalColors = :originalColors, " +
            "lastPageIndex = :pageIndex, lastScrollXPdf = :scrollX, lastScrollYPdf = :scrollY WHERE id = :id",
    )
    suspend fun updateSnapshot(
        id: Long,
        zoomMode: String,
        customZoomPercent: Float,
        lockZoom: Boolean,
        lockSideScroll: Boolean,
        originalColors: Boolean,
        pageIndex: Int,
        scrollX: Float,
        scrollY: Float,
    )
}
