package com.grandsphere.papercut.ui.viewer

import android.app.Application
import android.net.Uri
import androidx.lifecycle.viewModelScope
import com.grandsphere.papercut.data.DocumentPaths
import com.grandsphere.papercut.data.FolioItem
import com.grandsphere.papercut.data.ViewerSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

class FolioViewModel(application: Application) : DocumentViewModel(application) {
    private val _folioLive = MutableStateFlow<ViewerSettings?>(null)
    private val _folioIndex = MutableStateFlow(0)
    val folioIndex: StateFlow<Int> = _folioIndex.asStateFlow()
    val visibleFolioItems: StateFlow<List<FolioItem>> = folioItems.map { list ->
        list.filter { !it.hidden }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    private var currentFolioId: Long = 0L

    override val viewingSettings: StateFlow<ViewerSettings> =
        combine(settings, _folioLive) { s, live -> live ?: s }
            .stateIn(viewModelScope, SharingStarted.Eagerly, repo.snapshot)

    init {
        viewModelScope.launch { loadStart() }
    }

    override fun mutateView(transform: (ViewerSettings) -> ViewerSettings) {
        val current = _folioLive.value ?: viewingSettings.value
        _folioLive.value = transform(current)
    }

    override fun exportSource(): Uri? = currentCopy()?.let { Uri.fromFile(it) }

    override suspend fun resumeAfterPassword() {
        val item = visibleFolioItems.value.find { it.id == currentFolioId } ?: return
        clearPasswordPrompt()
        finishOpen(item)
    }

    private fun clearCurrent() {
        currentFolioId = 0L
        _folioIndex.value = 0
        _folioLive.value = null
        _sourceUri.value = null
        _document.value = null
        resetRotations()
    }

    private suspend fun loadStart() {
        try {
            val s = repo.get()
            val items = folioRepo.getAll().filter { !it.hidden }
            if (items.isEmpty()) {
                clearCurrent()
                return
            }
            val start = startFolioIndex(items, s)
            currentFolioId = items[start].id
            _folioIndex.value = start
            _folioLive.value = folioViewSettings(items[start], s)
            openItem(items[start])
        } finally {
            markStartupReady()
        }
    }

    fun addFromUris(uris: List<Uri>, onDone: (Int) -> Unit) {
        viewModelScope.launch {
            val wasEmpty = visibleFolioItems.value.isEmpty()
            var added = 0
            for (uri in uris) {
                val label = runCatching {
                    DocumentPaths.label(getApplication(), uri)
                }.getOrElse { DocumentPaths.fromUriString(uri.toString()) }
                val row = folioRepo.add(uri.toString(), label.displayName, label.displayPath, uri)
                if (row != null) added++
            }
            if (wasEmpty && added > 0) {
                loadStart()
            }
            onDone(added)
        }
    }

    fun storesLastPosition(): Boolean = repo.snapshot.folioStoreLastPosition

    fun showFolioIndex(index: Int, items: List<FolioItem> = visibleFolioItems.value) {
        viewModelScope.launch {
            if (items.isEmpty()) {
                clearCurrent()
                _pageLoading.value = false
                return@launch
            }
            val clamped = index.coerceIn(0, items.lastIndex)
            val item = items[clamped]
            if (_folioIndex.value == clamped && currentFolioId == item.id && engine.isOpened() && _document.value != null) {
                return@launch
            }
            disconnectJump()
            _folioIndex.value = clamped
            currentFolioId = item.id
            repo.update { it.copy(lastFolioItemId = item.id) }
            openItem(item)
        }
    }

    fun saveFolioSnapshot(page: Int, scrollX: Float, scrollY: Float) {
        val live = _folioLive.value ?: return
        val id = currentFolioId
        if (id == 0L) return
        viewModelScope.launch {
            folioRepo.saveSnapshot(
                id = id,
                zoomMode = live.zoomMode,
                customZoomPercent = live.customZoomPercent,
                lockZoom = live.lockZoom,
                lockSideScroll = live.lockSideScroll,
                originalColors = live.originalColors,
                pageIndex = page,
                scrollX = scrollX,
                scrollY = scrollY,
            )
            folioRepo.saveRotations(id, encodedRotations())
        }
    }

    fun setFolioHidden(ids: List<Long>, hidden: Boolean) {
        viewModelScope.launch { folioRepo.setHidden(ids, hidden) }
    }

    fun deleteFolioItems(ids: List<Long>) {
        viewModelScope.launch {
            folioRepo.delete(ids)
            val visible = folioRepo.getAll().filter { !it.hidden }
            if (visible.isEmpty() || visible.none { it.id == currentFolioId }) {
                loadStart()
            }
        }
    }

    fun reorderFolio(ids: List<Long>) {
        viewModelScope.launch { folioRepo.reorder(ids) }
    }

    fun setFolioStart(id: Long) {
        viewModelScope.launch { folioRepo.setStartItem(id) }
    }

    fun currentCopy(): File? {
        val item = folioItems.value.find { it.id == currentFolioId } ?: return null
        val file = folioRepo.fileFor(item)
        return file.takeIf { it.exists() && it.length() > 0L }
    }

    private suspend fun openItem(item: FolioItem) {
        _folioLive.value = folioViewSettings(item, repo.get())
        _sourceUri.value = item.uri
        _pageLoading.value = true
        _document.value = null
        try {
            if (!folioRepo.ensureLocal(item)) {
                _pageLoading.value = false
                emitError("OPEN")
                return
            }
            engine.openFile(folioRepo.fileFor(item), repo.get().fontScale, startPage = item.lastPageIndex)
            finishOpen(item)
        } catch (t: Throwable) {
            if (t.message == "PASSWORD") {
                showPasswordPrompt()
                return
            }
            _pageLoading.value = false
            _document.value = null
            emitError("OPEN")
        }
    }

    private suspend fun finishOpen(item: FolioItem) {
        _sourceUri.value = item.uri
        _folioLive.value = folioViewSettings(item, repo.get())
        stashRestore(item.lastPageIndex, item.lastScrollXPdf, item.lastScrollYPdf)
        val pages = engine.pages.toList()
        loadRotations(item.pageRotations, pages.size)
        _document.value = DocumentState(
            Uri.fromFile(folioRepo.fileFor(item)),
            pages,
            emptyList(),
            engine.mime,
        )
        resetSearchState()
        fillPagesAndOutline()
    }

    private fun folioViewSettings(item: FolioItem, global: ViewerSettings): ViewerSettings =
        global.copy(
            zoomMode = item.zoomMode,
            customZoomPercent = item.customZoomPercent,
            rememberLastZoom = true,
            lockZoom = item.lockZoom,
            lockSideScroll = item.lockSideScroll,
            originalColors = item.originalColors,
            twoFingerPan = true,
        )

    private fun startFolioIndex(items: List<FolioItem>, s: ViewerSettings): Int {
        if (s.folioOpenLastViewed && s.lastFolioItemId != 0L) {
            val idx = items.indexOfFirst { it.id == s.lastFolioItemId }
            if (idx >= 0) return idx
        }
        val pinned = items.indexOfFirst { it.isStartItem }
        return if (pinned >= 0) pinned else 0
    }
}
