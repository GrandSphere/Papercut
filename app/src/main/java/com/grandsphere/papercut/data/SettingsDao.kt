package com.grandsphere.papercut.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface SettingsDao {
    @Query("SELECT * FROM settings WHERE id = 1")
    fun observe(): Flow<ViewerSettings?>

    @Query("SELECT * FROM settings WHERE id = 1")
    suspend fun get(): ViewerSettings?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(settings: ViewerSettings)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(settings: ViewerSettings)
}
