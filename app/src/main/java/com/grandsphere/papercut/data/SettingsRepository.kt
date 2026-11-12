package com.grandsphere.papercut.data

import androidx.room.RoomDatabase
import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart

class SettingsRepository(
    private val db: RoomDatabase,
    private val dao: SettingsDao,
) {
    @Volatile
    var snapshot: ViewerSettings = ViewerSettings()
        private set

    suspend fun prime() {
        snapshot = get()
    }

    fun observe(): Flow<ViewerSettings> =
        dao.observe()
            .onStart { dao.insertIgnore(ViewerSettings()) }
            .map { it ?: ViewerSettings() }
            .onEach { snapshot = it }

    suspend fun get(): ViewerSettings = dao.get() ?: ViewerSettings().also { dao.insertIgnore(it) }

    suspend fun update(transform: (ViewerSettings) -> ViewerSettings) {
        snapshot = db.withTransaction {
            transform(get()).also { dao.upsert(it) }
        }
    }
}
