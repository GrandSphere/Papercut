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
import com.grandsphere.papercut.data.PdfDocument
import com.grandsphere.papercut.data.ViewerSettings
import com.grandsphere.papercut.data.ZoomMode
import com.grandsphere.papercut.pdf.DocumentMagic
import com.grandsphere.papercut.pdf.MuPdfEngine
import com.grandsphere.papercut.pdf.OutlineItem
import com.grandsphere.papercut.pdf.PageContent
import com.grandsphere.papercut.pdf.PageInfo
import com.grandsphere.papercut.pdf.PageLink
import com.grandsphere.papercut.pdf.PdfComposer
import com.grandsphere.papercut.pdf.SearchHit
import com.grandsphere.papercut.pdf.TextFinder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
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
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs

@OptIn(ExperimentalCoroutinesApi::class)
class ViewerViewModel(application: Application) : AndroidViewModel(application) {
    private val repo = (application as PapercutApp).settingsRepository
    private val bookmarksRepo = (application as PapercutApp).bookmarkRepository
    private val folioRepo = (application as PapercutApp).folioRepository
    private val engine = MuPdfEngine()

    val settings: StateFlow<ViewerSettings> = repo.observe().stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        repo.snapshot
    )

    private val startupDarkActive = MutableStateFlow(repo.snapshot.alwaysDarkMode)
    val folioItems: StateFlow<List<FolioItem>> = folioRepo.observeAll().stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        emptyList(),
    )
    private val _busy = MutableStateFlow<String?>(null)
    val busy: StateFlow<String?> = _busy.asStateFlow()
    private val _exportReady = MutableSharedFlow<File>(extraBufferCapacity = 1)
    val exportReady: SharedFlow<File> = _exportReady.asSharedFlow()
    private val _launchFolio = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val launchFolio: SharedFlow<Unit> = _launchFolio.asSharedFlow()
    private val _startupReady = MutableStateFlow(false)
    val startupReady: StateFlow<Boolean> = _startupReady.asStateFlow()

    val viewingSettings: StateFlow<ViewerSettings> = combine(
        settings,
        startupDarkActive,
    ) { s, dark ->
        if (!dark) {
            s
        } else {
            s.copy(
                originalColors = false,
                imageColorBlend = s.darkImageBlend,
                imageAlpha = s.darkImageAlpha,
            )
        }
    }.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        if (repo.snapshot.alwaysDarkMode) {
            repo.snapshot.copy(
                originalColors = false,
                imageColorBlend = repo.snapshot.darkImageBlend,
                imageAlpha = repo.snapshot.darkImageAlpha,
            )
        } else {
            repo.snapshot
        },
    )

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

    private val renderJobs = HashMap<Int, Job>()
    private val openGate = Mutex()
    private var openGeneration = 0
    private var openJob: Job? = null
    private var restoredLast = false
    private val _pageLoading = MutableStateFlow(false)
    val pageLoading: StateFlow<Boolean> = _pageLoading.asStateFlow()
    private var pendingPositionRestore = false
    private var pendingRestorePage = 0
    private var pendingRestoreX = 0f
    private var pendingRestoreOffset = 0f
    private val _sourceUri = MutableStateFlow<String?>(null)
    private val _passwordNeeded = MutableStateFlow(false)
    val passwordNeeded: StateFlow<Boolean> = _passwordNeeded.asStateFlow()
    private val _passwordWrong = MutableStateFlow(false)
    val passwordWrong: StateFlow<Boolean> = _passwordWrong.asStateFlow()
    private var pendingOpenUri: Uri? = null
    private var pendingFromLastPdf = false

    val bookmarks: StateFlow<List<Bookmark>> = _sourceUri.flatMapLatest { uri ->
        if (uri.isNullOrBlank()) flowOf(emptyList()) else bookmarksRepo.observeBookmarks(uri)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    init {
        viewModelScope.launch {
            repo.update { current ->
                if (current.alwaysDarkMode && current.originalColors) {
                    current.copy(originalColors = false)
                } else {
                    current
                }
            }
        }
    }

    fun open(
        uri: Uri,
        reportError: Boolean = true,
        previousPage: Int? = null,
        previousScrollX: Float? = null,
        previousScrollY: Float? = null,
    ) {
        val generation = ++openGeneration
        openJob?.cancel()
        renderJobs.values.forEach { it.cancel() }
        renderJobs.clear()
        _document.value = null
        _hits.value = emptyList()
        _hitIndex.value = 0
        _pageLoading.value = true
        openJob = viewModelScope.launch {
            openGate.withLock {
                if (generation != openGeneration) return@withLock
                val previousKey = _sourceUri.value
                if (
                    previousKey != null &&
                    previousPage != null &&
                    previousScrollX != null &&
                    previousScrollY != null
                ) {
                    bookmarksRepo.saveLastPosition(
                        previousKey,
                        previousPage,
                        previousScrollX,
                        previousScrollY,
                    )
                    val s = repo.get()
                    if (s.restoreLastPdf) {
                        repo.update {
                            it.copy(
                                lastPageIndex = previousPage,
                                lastScrollXPdf = previousScrollX,
                                lastScrollYPdf = previousScrollY,
                            )
                        }
                    }
                }
                if (generation != openGeneration) return@withLock
                try {
                    persistUri(uri)
                    val s = repo.get()
                    val fromLastPdf = isLastPdfCopy(uri)
                    val label = documentLabel(uri, fromLastPdf)
                    val stored = resolveStoredDocument(uri, fromLastPdf, label)
                    val startPage = if (s.restoreLastPosition) {
                        stored?.lastPageIndex ?: 0
                    } else {
                        0
                    }
                    engine.open(getApplication(), uri, s.fontScale, startPage = startPage)
                    if (generation != openGeneration) return@withLock
                    completeOpen(uri, fromLastPdf)
                } catch (t: Throwable) {
                    if (t is CancellationException) throw t
                    if (generation != openGeneration) return@withLock
                    if (t.message == "PASSWORD") {
                        pendingOpenUri = uri
                        pendingFromLastPdf = false
                        _document.value = null
                        _pageLoading.value = false
                        _passwordWrong.value = false
                        _passwordNeeded.value = true
                        return@withLock
                    }
                    _document.value = null
                    _pageLoading.value = false
                    if (reportError) {
                        _errors.emit("OPEN")
                    }
                }
            }
        }
    }

    fun openPreview(file: File) {
        val generation = ++openGeneration
        openJob?.cancel()
        renderJobs.values.forEach { it.cancel() }
        renderJobs.clear()
        _document.value = null
        _hits.value = emptyList()
        _hitIndex.value = 0
        _pageLoading.value = true
        openJob = viewModelScope.launch {
            openGate.withLock {
                if (generation != openGeneration) return@withLock
                try {
                    val s = repo.get()
                    engine.openFile(file, s.fontScale, startPage = 0)
                    if (generation != openGeneration) return@withLock
                    _document.value = DocumentState(
                        Uri.fromFile(file),
                        engine.pages.toList(),
                        emptyList(),
                        engine.mime,
                    )
                    _pageLoading.value = false
                    fillPagesAndOutline()
                } catch (t: Throwable) {
                    if (t is CancellationException) throw t
                    if (generation != openGeneration) return@withLock
                    _document.value = null
                    _pageLoading.value = false
                    _errors.emit("OPEN")
                }
            }
        }
    }

    fun submitPassword(password: String) {
        viewModelScope.launch {
            val target = currentEngine()
            val ok = runCatching { target.authenticatePassword(password) }.getOrDefault(false)
            if (!ok) {
                _passwordWrong.value = true
                return@launch
            }
            val uri = pendingOpenUri ?: return@launch
            val fromLastPdf = pendingFromLastPdf
            pendingOpenUri = null
            pendingFromLastPdf = false
            _passwordWrong.value = false
            _passwordNeeded.value = false
            completeOpen(uri, fromLastPdf)
        }
    }

    fun cancelPassword() {
        viewModelScope.launch { currentEngine().cancelPassword() }
        pendingOpenUri = null
        pendingFromLastPdf = false
        _passwordNeeded.value = false
        _passwordWrong.value = false
        _pageLoading.value = false
        _document.value = null
    }

    private suspend fun completeOpen(uri: Uri, fromLastPdf: Boolean) {
        val generation = openGeneration
        val s = repo.get()
        val lastPdf = fromLastPdf || isLastPdfCopy(uri)
        val label = documentLabel(uri, lastPdf)
        val stored = resolveStoredDocument(uri, lastPdf, label)
        val key = when {
            stored != null -> stored.uri
            lastPdf -> s.lastPdfUri?.takeIf { !isLastPdfCopy(Uri.parse(it)) }
            else -> uri.toString()
        }
        val doc = if (key.isNullOrBlank()) {
            null
        } else {
            bookmarksRepo.ensureDocument(key, label.displayName, label.displayPath)
        }
        if (generation != openGeneration) return
        _sourceUri.value = doc?.uri
        val restorePos = s.restoreLastPosition
        val startPage = if (restorePos) {
            doc?.lastPageIndex ?: s.lastPageIndex
        } else {
            0
        }
        val restoreX = if (restorePos) {
            doc?.lastScrollXPdf ?: s.lastScrollXPdf
        } else {
            0f
        }
        val restoreY = if (restorePos) {
            doc?.lastScrollYPdf ?: s.lastScrollYPdf
        } else {
            0f
        }
        stashRestore(startPage, restoreX, restoreY, restorePos)
        _pageLoading.value = true
        _document.value = DocumentState(uri, engine.pages.toList(), emptyList(), engine.mime)
        _hits.value = emptyList()
        _hitIndex.value = 0
        if (!lastPdf && s.restoreLastPdf) {
            copyLastPdf(uri)
            if (generation != openGeneration) return
            repo.update { it.copy(lastPdfUri = doc?.uri ?: uri.toString()) }
        }
        if (generation != openGeneration) return
        fillPagesAndOutline()
    }

    private fun fillPagesAndOutline() {
        val uri = _document.value?.uri ?: return
        viewModelScope.launch {
            val pages = runCatching { engine.measureRemainingPages() }.getOrDefault(engine.pages.toList())
            val outline = runCatching { engine.outline() }.getOrDefault(emptyList())
            val current = _document.value ?: return@launch
            if (current.uri != uri) return@launch
            _document.value = current.copy(pages = pages, outline = outline)
        }
    }

    fun maybeRestoreLast(hasDocumentIntent: Boolean) {
        if (hasDocumentIntent || restoredLast) {
            restoredLast = true
            _startupReady.value = true
            return
        }
        restoredLast = true
        viewModelScope.launch {
            try {
                val s = repo.get()
                if (s.folioEnabled && s.openFolioOnStartup) {
                    _launchFolio.emit(Unit)
                    return@launch
                }
                if (!s.restoreLastPdf) return@launch
                val file = lastPdfFile()
                if (file.exists() && file.length() > 0L) {
                    try {
                        val stored = resolveLastPdfDocument(s)
                        val startPage = if (s.restoreLastPosition) {
                            stored?.lastPageIndex ?: s.lastPageIndex
                        } else {
                            0
                        }
                        _pageLoading.value = true
                        engine.openFile(file, s.fontScale, startPage = startPage)
                        completeOpen(Uri.fromFile(file), fromLastPdf = true)
                    } catch (t: Throwable) {
                        if (t.message == "PASSWORD") {
                            pendingOpenUri = Uri.fromFile(file)
                            pendingFromLastPdf = true
                            _pageLoading.value = false
                            _passwordWrong.value = false
                            _passwordNeeded.value = true
                        } else {
                            _pageLoading.value = false
                            _document.value = null
                        }
                    }
                }
            } finally {
                _startupReady.value = true
            }
        }
    }

    fun requestPage(index: Int, width: Int, urgent: Boolean = false, onReady: (Int, Int, Bitmap) -> Unit) {
        requestPage(currentEngine(), index, width, viewingSettings.value, urgent, onReady)
    }

    fun requestPage(
        target: MuPdfEngine,
        index: Int,
        width: Int,
        paint: ViewerSettings = viewingSettings.value,
        urgent: Boolean = false,
        onReady: (Int, Int, Bitmap) -> Unit,
    ) {
        val track = target === engine
        if (track) renderJobs[index]?.cancel()
        val job = viewModelScope.launch {
            try {
                val bmp = target.render(
                    index,
                    width,
                    paint.fgColor,
                    paint.bgColor,
                    paint.imageColorBlend,
                    paint.originalColors,
                    paint.imageAlpha,
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
        if (track) renderJobs[index] = job
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
        startupDarkActive.value = false
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

    suspend fun pageLinks(index: Int): List<PageLink> =
        runCatching { currentEngine().pageContent(index).links }.getOrDefault(emptyList())

    suspend fun resolveInternalLink(uri: String): Pair<Int, Float?>? = currentEngine().resolveUri(uri)

    private var searchJob: Job? = null

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

    fun saveReadingPosition(page: Int, scrollX: Float, scrollY: Float) {
        viewModelScope.launch {
            if (_document.value == null) return@launch
            val key = _sourceUri.value
            if (key != null) {
                bookmarksRepo.saveLastPosition(key, page, scrollX, scrollY)
            }
            repo.update {
                it.copy(
                    lastPageIndex = page,
                    lastScrollXPdf = scrollX,
                    lastScrollYPdf = scrollY,
                )
            }
        }
    }

    fun consumeRestorePage(): Triple<Int, Float, Float>? {
        if (!pendingPositionRestore) return null
        pendingPositionRestore = false
        return Triple(pendingRestorePage, pendingRestoreX, pendingRestoreOffset)
    }

    private suspend fun stashRestore(page: Int, scrollX: Float, offsetOnPage: Float, restorePos: Boolean) {
        pendingRestorePage = page
        pendingRestoreX = if (restorePos) scrollX else 0f
        pendingRestoreOffset = if (restorePos) offsetOnPage else 0f
        pendingPositionRestore = restorePos
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

    fun alreadyInFolio(): Boolean {
        val key = _sourceUri.value ?: return false
        return folioItems.value.any { it.uri == key }
    }

    fun isPdf(): Boolean = document.value?.mime == "application/pdf"

    fun addCurrentToFolio() {
        viewModelScope.launch {
            val key = _sourceUri.value ?: return@launch
            val uri = Uri.parse(key)
            val source = _document.value?.uri ?: uri
            val label = runCatching {
                DocumentPaths.label(getApplication(), uri)
            }.getOrElse { DocumentPaths.fromUriString(key) }
            val added = folioRepo.add(
                key,
                label.displayName,
                label.displayPath,
                source = source,
                snapshot = viewingSettings.value,
            )
            _errors.emit(if (added != null) "FOLIO_ADDED" else "FOLIO_COPY")
        }
    }

    fun exportPartial(pages: List<Int>) {
        val uri = _document.value?.uri ?: sourceOpenUri() ?: return
        viewModelScope.launch {
            _busy.value = "Building PDF"
            runCatching {
                val dest = exportFile()
                PdfComposer.extractPages(getApplication(), uri, pages, dest)
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

    private fun persistUri(uri: Uri) {
        DocumentMagic.persistRead(getApplication(), uri)
    }

    private fun currentEngine(): MuPdfEngine = engine

    private fun mutateView(transform: (ViewerSettings) -> ViewerSettings) {
        viewModelScope.launch { repo.update(transform) }
    }

    private fun sourceOpenUri(): Uri? {
        val raw = _sourceUri.value ?: return null
        return Uri.parse(raw)
    }

    private fun exportFile(): File = AppFiles.exportPdf(getApplication())

    override fun onCleared() {
        renderJobs.values.forEach { it.cancel() }
        searchJob?.cancel()
        engine.shutdown()
        super.onCleared()
    }

    private fun lastPdfFile(): File =
        AppFiles.lastPdf(getApplication())

    private fun isLastPdfCopy(uri: Uri): Boolean {
        if (!uri.scheme.equals("file", ignoreCase = true)) return false
        val path = uri.path ?: return false
        return File(path).absoluteFile.normalize() == lastPdfFile().absoluteFile.normalize()
    }

    private fun documentLabel(uri: Uri, lastPdf: Boolean): DocumentPaths.Label {
        val s = repo.snapshot
        val labelUri = if (lastPdf && !s.lastPdfUri.isNullOrBlank()) {
            runCatching { Uri.parse(s.lastPdfUri) }.getOrDefault(uri)
        } else {
            uri
        }
        return runCatching {
            DocumentPaths.label(getApplication(), labelUri)
        }.getOrElse { DocumentPaths.fromUriString(labelUri.toString()) }
    }

    private suspend fun resolveLastPdfDocument(s: ViewerSettings): PdfDocument? {
        val lastKey = s.lastPdfUri
        if (!lastKey.isNullOrBlank() && !isLastPdfCopy(Uri.parse(lastKey))) {
            bookmarksRepo.getDocument(lastKey)?.let { return it }
        }
        return null
    }

    private suspend fun resolveStoredDocument(
        uri: Uri,
        fromLastPdf: Boolean,
        label: DocumentPaths.Label,
    ): PdfDocument? {
        val lastPdf = fromLastPdf || isLastPdfCopy(uri)
        if (!lastPdf) {
            bookmarksRepo.getDocument(uri.toString())?.let { return it }
        }
        if (lastPdf) {
            resolveLastPdfDocument(repo.get())?.let { return it }
        }
        return bookmarksRepo.findByNameAndPath(label.displayName, label.displayPath)
    }

    fun lastPdfCopy(): File = lastPdfFile()

    fun sourceContentUri(): Uri? {
        val raw = _sourceUri.value ?: return null
        val uri = Uri.parse(raw)
        return uri.takeIf { it.scheme.equals("content", ignoreCase = true) }
    }

    private suspend fun copyLastPdf(uri: Uri) {
        val dest = lastPdfFile()
        if (uri.scheme == "file" && uri.path == dest.absolutePath) return
        withContext(Dispatchers.IO) {
            val tmp = File(dest.parentFile, "${AppFiles.LAST_PDF}.tmp")
            val copied = runCatching {
                getApplication<Application>().contentResolver.openInputStream(uri)?.use { input ->
                    tmp.outputStream().use { output -> input.copyTo(output) }
                } != null
            }.getOrDefault(false)
            if (copied && tmp.exists() && tmp.length() > 0L) {
                if (dest.exists()) dest.delete()
                tmp.renameTo(dest)
            } else if (tmp.exists()) {
                tmp.delete()
            }
        }
    }
}
