package com.grandsphere.papercut

import android.app.Application
import com.grandsphere.papercut.data.AppDatabase
import com.grandsphere.papercut.data.AppFiles
import com.grandsphere.papercut.data.BookmarkRepository
import com.grandsphere.papercut.data.FolioRepository
import com.grandsphere.papercut.data.FolioStore
import com.grandsphere.papercut.data.SettingsRepository
import com.grandsphere.papercut.ui.viewer.FolioActivity
import com.grandsphere.papercut.ui.viewer.ViewerActivity
import kotlinx.coroutines.runBlocking

class PapercutApp : Application() {
    val database by lazy { AppDatabase.create(this) }
    val settingsRepository by lazy { SettingsRepository(database.settingsDao()) }
    val bookmarkRepository by lazy {
        BookmarkRepository(database.documentDao(), database.bookmarkDao())
    }
    val folioRepository by lazy {
        FolioRepository(database.folioDao(), FolioStore(AppFiles.stored(this)), contentResolver)
    }

    private val liveViewers = ArrayList<ViewerActivity>()
    private val liveFolios = ArrayList<FolioActivity>()

    fun registerViewer(activity: ViewerActivity) {
        synchronized(liveViewers) {
            if (activity !in liveViewers) liveViewers.add(activity)
        }
    }

    fun unregisterViewer(activity: ViewerActivity) {
        synchronized(liveViewers) { liveViewers.remove(activity) }
    }

    fun viewers(): List<ViewerActivity> = synchronized(liveViewers) { liveViewers.toList() }

    fun registerFolio(activity: FolioActivity) {
        synchronized(liveFolios) {
            if (activity !in liveFolios) liveFolios.add(activity)
        }
    }

    fun unregisterFolio(activity: FolioActivity) {
        synchronized(liveFolios) { liveFolios.remove(activity) }
    }

    fun finishFolios() {
        val open = synchronized(liveFolios) { liveFolios.toList() }
        open.forEach { folio ->
            if (!folio.isFinishing) folio.finish()
        }
    }

    override fun onCreate() {
        super.onCreate()
        AppFiles.migrate(this)
        runBlocking { settingsRepository.prime() }
    }
}
