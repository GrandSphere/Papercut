package com.grandsphere.papercut.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface DocumentDao {
    @Query("SELECT * FROM documents ORDER BY displayName COLLATE NOCASE")
    fun observeAll(): Flow<List<PdfDocument>>

    @Query("SELECT * FROM documents")
    suspend fun getAll(): List<PdfDocument>

    @Query("SELECT * FROM documents WHERE uri = :uri")
    suspend fun get(uri: String): PdfDocument?

    @Query("SELECT * FROM documents WHERE displayName = :name AND displayPath = :path LIMIT 1")
    suspend fun getByLabel(name: String, path: String): PdfDocument?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(doc: PdfDocument): Long

    @Update
    suspend fun update(doc: PdfDocument)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(doc: PdfDocument)

    @Query("DELETE FROM documents WHERE uri IN (:uris)")
    suspend fun delete(uris: List<String>)
}
