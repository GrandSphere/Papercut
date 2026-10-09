package com.grandsphere.papercut.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface BookmarkDao {
    @Query("SELECT * FROM bookmarks WHERE documentUri = :uri ORDER BY sortOrder ASC, id ASC")
    fun observeForDocument(uri: String): Flow<List<Bookmark>>

    @Query("SELECT * FROM bookmarks ORDER BY documentUri ASC, sortOrder ASC, id ASC")
    suspend fun getAll(): List<Bookmark>

    @Query("SELECT COALESCE(MAX(sortOrder), -1) FROM bookmarks WHERE documentUri = :uri")
    suspend fun maxSortOrder(uri: String): Int

    @Insert
    suspend fun insert(bookmark: Bookmark): Long

    @Update
    suspend fun update(bookmark: Bookmark)

    @Query("DELETE FROM bookmarks WHERE id IN (:ids)")
    suspend fun deleteIds(ids: List<Long>)

    @Query("DELETE FROM bookmarks")
    suspend fun deleteAll()
}
