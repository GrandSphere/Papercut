package com.grandsphere.papercut.ui.settings

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.grandsphere.papercut.PapercutApp
import com.grandsphere.papercut.data.AppFiles
import com.grandsphere.papercut.data.BackupJson
import com.grandsphere.papercut.data.DocumentPaths
import com.grandsphere.papercut.data.PdfDocument
import com.grandsphere.papercut.data.StoredEntry
import com.grandsphere.papercut.data.StoredFiles
import com.grandsphere.papercut.data.StoredUse
import com.grandsphere.papercut.data.ViewerSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class SettingsViewModel(application: Application) : AndroidViewModel(application) {
    private val repo = (application as PapercutApp).settingsRepository
    private val bookmarks = (application as PapercutApp).bookmarkRepository
    private val folioRepo = (application as PapercutApp).folioRepository

    val settings: StateFlow<ViewerSettings> = repo.observe().stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        repo.snapshot
    )

    val documents: StateFlow<List<PdfDocument>> = bookmarks.observeDocuments().stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        emptyList(),
    )

    private val _stored = MutableStateFlow<List<StoredEntry>>(emptyList())
    val stored: StateFlow<List<StoredEntry>> = _stored.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    init {
        viewModelScope.launch {
            val s = repo.get()
            s.lastPdfUri?.let { key ->
                val parsed = Uri.parse(key)
                val label = runCatching {
                    DocumentPaths.label(getApplication(), parsed)
                }.getOrElse { DocumentPaths.fromUriString(key) }
                bookmarks.ensureDocument(key, label.displayName, label.displayPath)
            }
            bookmarks.repairLabels { uri ->
                runCatching {
                    DocumentPaths.label(getApplication(), Uri.parse(uri))
                }.getOrElse { DocumentPaths.fromUriString(uri) }
            }
        }
    }

    fun save(draft: ViewerSettings) {
        viewModelScope.launch {
            repo.update { current ->
                var next = draft.withExclusiveMenuTaps().withExclusiveStartup().copy(
                    id = current.id,
                    lastPdfUri = current.lastPdfUri,
                    lastFolioItemId = current.lastFolioItemId,
                    zoomMode = current.zoomMode,
                    customZoomPercent = current.customZoomPercent,
                    lockZoom = current.lockZoom,
                    lockSideScroll = current.lockSideScroll,
                    originalColors = current.originalColors,
                    lastPageIndex = current.lastPageIndex,
                    lastScrollXPdf = current.lastScrollXPdf,
                    lastScrollYPdf = current.lastScrollYPdf,
                )
                if (!next.restoreLastPdf) {
                    AppFiles.lastPdf(getApplication()).delete()
                    next = next.copy(lastPdfUri = null)
                }
                next
            }
        }
    }

    fun exportTo(uri: Uri, kind: BackupJson.Kind) {
        viewModelScope.launch {
            runCatching {
                val json = exportText(kind)
                withContext(Dispatchers.IO) {
                    getApplication<Application>().contentResolver.openOutputStream(uri)?.use { out ->
                        out.write(json.toByteArray(Charsets.UTF_8))
                    } ?: error("open")
                }
            }.onSuccess {
                _messages.emit("Exported")
            }.onFailure {
                _messages.emit("Export failed")
            }
        }
    }

    suspend fun exportText(kind: BackupJson.Kind): String {
        if (kind == BackupJson.Kind.Folio) {
            return BackupJson.exportFolio(folioRepo.getAll())
        }
        return bookmarks.snapshot(kind, repo.get())
    }

    fun refreshStored() {
        viewModelScope.launch {
            val app = getApplication<Application>()
            val settings = repo.get()
            val items = folioRepo.getAll()
            val title = settings.lastPdfUri?.let { key ->
                runCatching { DocumentPaths.label(app, Uri.parse(key)).displayName }
                    .getOrElse { DocumentPaths.fromUriString(key).displayName }
            }
            _stored.value = StoredFiles.list(app, items, title)
        }
    }

    fun deleteStored(entries: List<StoredEntry>) {
        viewModelScope.launch {
            val folioIds = entries.mapNotNull { entry ->
                if (entry.use == StoredUse.Folio) entry.folioId else null
            }
            if (folioIds.isNotEmpty()) folioRepo.delete(folioIds)
            var clearLast = false
            entries.forEach { entry ->
                if (entry.use == StoredUse.LastPdf) clearLast = true
                if (entry.use != StoredUse.Folio) File(entry.path).delete()
            }
            if (clearLast) {
                repo.update { it.copy(lastPdfUri = null) }
            }
            refreshStored()
        }
    }

    fun importFrom(uri: Uri) {
        viewModelScope.launch {
            runCatching {
                val text = withContext(Dispatchers.IO) {
                    getApplication<Application>().contentResolver.openInputStream(uri)
                        ?.bufferedReader()
                        ?.use { it.readText() }
                        ?: error("open")
                }
                val parsed = BackupJson.parse(text)
                parsed.settingsJson?.let { obj ->
                    repo.update { current -> BackupJson.mergeSettings(current, obj) }
                }
                bookmarks.importParsed(parsed)
            }.onSuccess {
                _messages.emit("Imported")
            }.onFailure {
                _messages.emit(if (it.message == "FORMAT") "Not a Papercut file" else "Import failed")
            }
        }
    }

    fun deleteDocuments(uris: List<String>) {
        viewModelScope.launch { bookmarks.deleteDocuments(uris) }
    }

    fun clearBookmarks() {
        viewModelScope.launch {
            bookmarks.clearAllBookmarks()
            _messages.emit("Bookmarks cleared")
        }
    }
}
