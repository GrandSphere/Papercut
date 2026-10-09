package com.grandsphere.papercut.ui.viewer

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.grandsphere.papercut.PapercutApp
import com.grandsphere.papercut.data.AppFiles
import com.grandsphere.papercut.data.Bookmark
import com.grandsphere.papercut.data.DocumentPaths
import com.grandsphere.papercut.data.FolioItem
import com.grandsphere.papercut.data.ViewerSettings
import com.grandsphere.papercut.data.ZoomMode
import com.grandsphere.papercut.pdf.MuPdfEngine
import com.grandsphere.papercut.pdf.OutlineItem
import com.grandsphere.papercut.pdf.PageContent
import com.grandsphere.papercut.pdf.PageInfo
import com.grandsphere.papercut.pdf.PageLink
import com.grandsphere.papercut.pdf.PdfComposer
import com.grandsphere.papercut.pdf.SearchHit
import com.grandsphere.papercut.pdf.TextFinder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.abs

@OptIn(ExperimentalCoroutinesApi::class)
class FolioViewModel(application: Application) : AndroidViewModel(application) {
    private val repo = (application as PapercutApp).settingsRepository
    private val bookmarksRepo = (application as PapercutApp).bookmarkRepository
    private val folioRepo = (application as PapercutApp).folioRepository
    private val engine = MuPdfEngine()

    val settings: StateFlow<ViewerSettings> = repo.observe().stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        repo.snapshot,
    )

    private val _folioLive = MutableStateFlow<ViewerSettings?>(null)
    private val _folioIndex = MutableStateFlow(0)
    val folioIndex: StateFlow<Int> = _folioIndex.asStateFlow()
    val folioItems: StateFlow<List<FolioItem>> = folioRepo.observeAll().stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        emptyList(),
    )
    val visibleFolioItems: StateFlow<List<FolioItem>> = folioItems.map { list ->
        list.filter { !it.hidden }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    private val _busy = MutableStateFlow<String?>(null)
    val busy: StateFlow<String?> = _busy.asStateFlow()
    private val _pageLoading = MutableStateFlow(false)
    val pageLoading: StateFlow<Boolean> = _pageLoading.asStateFlow()
    private val _startupReady = MutableStateFlow(false)
    val startupReady: StateFlow<Boolean> = _startupReady.asStateFlow()
    private val _exportReady = MutableSharedFlow<File>(extraBufferCapacity = 1)
    val exportReady: SharedFlow<File> = _exportReady.asSharedFlow()
    private var currentFolioId: Long = 0L

    val viewingSettings: StateFlow<ViewerSettings> = combine(settings, _folioLive) { s, live ->
        live ?: s
    }.stateIn(viewModelScope, SharingStarted.Eagerly, repo.snapshot)

    data class DocumentState(
        val uri: Uri,
        val pages: List<PageInfo>,
        val outline: List<OutlineItem> = emptyList(),
        val mime: String = "application/pdf",
    )

    private val _document = MutableStateFlow<DocumentState?>(null)
    val document: StateFlow<DocumentState?> = _document.asStateFlow()
    private val _errors = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val errors: SharedFlow<String> = _errors.asSharedFlow()
    private val _hits = MutableStateFlow<List<SearchHit>>(emptyList())
    val hits: StateFlow<List<SearchHit>> = _hits.asStateFlow()
    private val _hitIndex = MutableStateFlow(0)
    val hitIndex: StateFlow<Int> = _hitIndex.asStateFlow()
    private val _searching = MutableStateFlow(false)
    val searching: StateFlow<Boolean> = _searching.asStateFlow()
    private val _sourceUri = MutableStateFlow<String?>(null)
    private val _passwordNeeded = MutableStateFlow(false)
    val passwordNeeded: StateFlow<Boolean> = _passwordNeeded.asStateFlow()
    private val _passwordWrong = MutableStateFlow(false)
    val passwordWrong: StateFlow<Boolean> = _passwordWrong.asStateFlow()
    private var pendingOpenUri: Uri? = null
    private var searchJob: Job? = null
    private val renderJobs = HashMap<Int, Job>()
    private var pendingPositionRestore = false
    private var pendingRestorePage = 0
    private var pendingRestoreX = 0f
    private var pendingRestoreOffset = 0f

    val bookmarks: StateFlow<List<Bookmark>> = _sourceUri.flatMapLatest { uri ->
        if (uri.isNullOrBlank()) flowOf(emptyList()) else bookmarksRepo.observeBookmarks(uri)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    init {
        viewModelScope.launch { loadStart() }
    }

    private suspend fun loadStart() {
        try {
            val s = repo.get()
            val items = folioRepo.getAll().filter { !it.hidden }
            if (items.isEmpty()) {
                currentFolioId = 0L
                _folioIndex.value = 0
                _folioLive.value = null
                _sourceUri.value = null
                _document.value = null
                return
            }
            val start = startFolioIndex(items, s)
            currentFolioId = items[start].id
            _folioIndex.value = start
            _folioLive.value = folioViewSettings(items[start], s)
            openItem(items[start], reportError = true)
        } finally {
            _startupReady.value = true
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

    fun requestPage(
        index: Int,
        width: Int,
        urgent: Boolean = false,
        onReady: (Int, Int, Bitmap) -> Unit,
    ) {
        renderJobs[index]?.cancel()
        val job = viewModelScope.launch {
            try {
                val bmp = engine.render(
                    index,
                    width,
                    viewingSettings.value.fgColor,
                    viewingSettings.value.bgColor,
                    viewingSettings.value.imageColorBlend,
                    viewingSettings.value.originalColors,
                    viewingSettings.value.imageAlpha,
                    urgent = urgent,
                )
                if (isActive) {
                    _pageLoading.value = false
                    onReady(index, width, bmp)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
            }
        }
        renderJobs[index] = job
    }

    fun cancelStaleRenders(keep: Set<Int>) {
        val iter = renderJobs.entries.iterator()
        while (iter.hasNext()) {
            val entry = iter.next()
            if (entry.key !in keep) {
                entry.value.cancel()
                iter.remove()
            }
        }
    }

    suspend fun pageContent(index: Int): PageContent = currentEngine().pageContent(index)

    suspend fun pageLinks(index: Int): List<PageLink> =
        runCatching { currentEngine().pageContent(index).links }.getOrDefault(emptyList())

    suspend fun resolveInternalLink(uri: String): Pair<Int, Float?>? = currentEngine().resolveUri(uri)

    fun setZoomMode(mode: ZoomMode) {
        mutateView {
            val unlock = it.autoDisablePan && it.lockSideScroll && mode != ZoomMode.FIT_WIDTH
            it.copy(
                zoomMode = mode.name,
                lockSideScroll = when {
                    mode == ZoomMode.FIT_WIDTH -> true
                    unlock -> false
                    else -> it.lockSideScroll
                },
            )
        }
    }

    fun setPinchZoom(percent: Float) {
        mutateView {
            val p = percent.coerceIn(25f, 400f)
            val unlock = it.autoDisablePan && it.lockSideScroll
            it.copy(
                zoomMode = if (it.rememberLastZoom) ZoomMode.CUSTOM.name else it.zoomMode,
                customZoomPercent = if (it.rememberLastZoom) p else it.customZoomPercent,
                lockSideScroll = if (unlock) false else it.lockSideScroll,
            )
        }
    }

    fun setLockZoom(on: Boolean) {
        mutateView { it.copy(lockZoom = on) }
    }

    fun setLockSideScroll(on: Boolean) {
        mutateView { it.copy(lockSideScroll = on) }
    }

    fun setOriginalColors(on: Boolean) {
        mutateView { it.copy(originalColors = on) }
    }

    fun setCustomZoom(percent: Float) {
        mutateView {
            val p = percent.coerceIn(25f, 400f)
            val zoomChanged = it.zoomMode != ZoomMode.CUSTOM.name || abs(it.customZoomPercent - p) > 0.4f
            val unlock = it.autoDisablePan && it.lockSideScroll && zoomChanged
            it.copy(
                zoomMode = ZoomMode.CUSTOM.name,
                customZoomPercent = p,
                lockSideScroll = if (unlock) false else it.lockSideScroll,
            )
        }
    }

    fun search(query: String) {
        searchJob?.cancel()
        if (query.isBlank()) {
            _hits.value = emptyList()
            _hitIndex.value = 0
            _searching.value = false
            return
        }
        _searching.value = true
        searchJob = viewModelScope.launch {
            try {
                kotlinx.coroutines.yield()
                val doc = _document.value
                if (doc == null) {
                    _hits.value = emptyList()
                    _hitIndex.value = 0
                    return@launch
                }
                val pages = doc.pages.indices.map { currentEngine().pageContent(it).chars }
                if (!isActive) return@launch
                val found = TextFinder.find(pages, query)
                if (!isActive) return@launch
                _hits.value = found
                _hitIndex.value = 0
            } catch (cancelled: CancellationException) {
                throw cancelled
            } finally {
                if (isActive) _searching.value = false
            }
        }
    }

    fun addBookmark(name: String, pageIndex: Int, scrollX: Float, scrollY: Float) {
        val key = _sourceUri.value ?: return
        viewModelScope.launch {
            bookmarksRepo.addBookmark(key, name, pageIndex, scrollX, scrollY)
        }
    }

    fun updateBookmark(bookmark: Bookmark) {
        viewModelScope.launch { bookmarksRepo.updateBookmark(bookmark) }
    }

    fun deleteBookmarks(ids: List<Long>) {
        viewModelScope.launch { bookmarksRepo.deleteBookmarks(ids) }
    }

    fun reorderBookmarks(ids: List<Long>) {
        viewModelScope.launch { bookmarksRepo.reorder(ids) }
    }

    fun selectHit(index: Int) {
        val n = _hits.value.size
        if (n == 0) return
        _hitIndex.value = index.coerceIn(0, n - 1)
    }

    fun nextHit() {
        val n = _hits.value.size
        if (n == 0) return
        _hitIndex.value = (_hitIndex.value + 1) % n
    }

    fun prevHit() {
        val n = _hits.value.size
        if (n == 0) return
        _hitIndex.value = (_hitIndex.value - 1 + n) % n
    }

    fun isPdf(): Boolean = document.value?.mime == "application/pdf"

    fun submitPassword(password: String) {
        viewModelScope.launch {
            val ok = runCatching { engine.authenticatePassword(password) }.getOrDefault(false)
            if (!ok) {
                _passwordWrong.value = true
                return@launch
            }
            val item = visibleFolioItems.value.find { it.id == currentFolioId } ?: return@launch
            pendingOpenUri = null
            _passwordWrong.value = false
            _passwordNeeded.value = false
            finishOpen(item)
        }
    }

    fun cancelPassword() {
        viewModelScope.launch { engine.cancelPassword() }
        pendingOpenUri = null
        _passwordNeeded.value = false
        _passwordWrong.value = false
        _pageLoading.value = false
        _document.value = null
    }

    fun storesLastPosition(): Boolean = repo.snapshot.folioStoreLastPosition

    fun showFolioIndex(index: Int, items: List<FolioItem> = visibleFolioItems.value) {
        viewModelScope.launch {
            if (items.isEmpty()) {
                currentFolioId = 0L
                _folioIndex.value = 0
                _folioLive.value = null
                _sourceUri.value = null
                _document.value = null
                _pageLoading.value = false
                return@launch
            }
            val clamped = index.coerceIn(0, items.lastIndex)
            val item = items[clamped]
            if (_folioIndex.value == clamped && currentFolioId == item.id && engine.isOpened() && _document.value != null) {
                return@launch
            }
            _folioIndex.value = clamped
            currentFolioId = item.id
            repo.update { it.copy(lastFolioItemId = item.id) }
            openItem(item, reportError = true)
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

    fun exportPartial(pages: List<Int>) {
        val file = currentCopy() ?: return
        viewModelScope.launch {
            _busy.value = "Building PDF"
            runCatching {
                val dest = exportFile()
                PdfComposer.extractPages(getApplication(), Uri.fromFile(file), pages, dest)
                dest
            }.onSuccess {
                _busy.value = null
                _exportReady.emit(it)
            }.onFailure {
                _busy.value = null
                _errors.emit(it.message ?: "EXPORT")
            }
        }
    }

    private suspend fun openItem(item: FolioItem, reportError: Boolean) {
        _folioLive.value = folioViewSettings(item, repo.get())
        _sourceUri.value = item.uri
        _pageLoading.value = true
        _document.value = null
        try {
            if (!folioRepo.ensureLocal(item)) {
                _pageLoading.value = false
                if (reportError) _errors.emit("OPEN")
                return
            }
            val file = folioRepo.fileFor(item)
            engine.openFile(file, repo.get().fontScale, startPage = item.lastPageIndex)
            finishOpen(item)
        } catch (t: Throwable) {
            if (t.message == "PASSWORD") {
                pendingOpenUri = Uri.parse(item.uri)
                _passwordWrong.value = false
                _passwordNeeded.value = true
                _pageLoading.value = false
                return
            }
            _pageLoading.value = false
            _document.value = null
            if (reportError) _errors.emit("OPEN")
        }
    }

    private suspend fun finishOpen(item: FolioItem) {
        _sourceUri.value = item.uri
        _folioLive.value = folioViewSettings(item, repo.get())
        pendingRestorePage = item.lastPageIndex
        pendingRestoreX = item.lastScrollXPdf
        pendingRestoreOffset = item.lastScrollYPdf
        pendingPositionRestore = true
        _document.value = DocumentState(
            Uri.fromFile(folioRepo.fileFor(item)),
            engine.pages.toList(),
            emptyList(),
            engine.mime,
        )
        _hits.value = emptyList()
        _hitIndex.value = 0
        viewModelScope.launch {
            val pages = runCatching { engine.measureRemainingPages() }.getOrDefault(engine.pages.toList())
            val outline = runCatching { engine.outline() }.getOrDefault(emptyList())
            val current = _document.value ?: return@launch
            _document.value = current.copy(pages = pages, outline = outline)
        }
    }

    fun consumeRestorePage(): Triple<Int, Float, Float>? {
        if (!pendingPositionRestore) return null
        pendingPositionRestore = false
        return Triple(pendingRestorePage, pendingRestoreX, pendingRestoreOffset)
    }

    private fun folioViewSettings(item: FolioItem, global: ViewerSettings): ViewerSettings {
        return global.copy(
            zoomMode = item.zoomMode,
            customZoomPercent = item.customZoomPercent,
            rememberLastZoom = true,
            lockZoom = item.lockZoom,
            lockSideScroll = item.lockSideScroll,
            originalColors = item.originalColors,
            twoFingerPan = true,
        )
    }

    private fun startFolioIndex(items: List<FolioItem>, s: ViewerSettings): Int {
        if (s.folioOpenLastViewed && s.lastFolioItemId != 0L) {
            val idx = items.indexOfFirst { it.id == s.lastFolioItemId }
            if (idx >= 0) return idx
        }
        val pinned = items.indexOfFirst { it.isStartItem }
        if (pinned >= 0) return pinned
        return 0
    }

    private fun currentEngine(): MuPdfEngine = engine

    private fun mutateView(transform: (ViewerSettings) -> ViewerSettings) {
        val current = _folioLive.value ?: viewingSettings.value
        _folioLive.value = transform(current)
    }

    private fun exportFile(): File = AppFiles.exportPdf(getApplication())

    override fun onCleared() {
        renderJobs.values.forEach { it.cancel() }
        searchJob?.cancel()
        engine.shutdown()
        super.onCleared()
    }
}
