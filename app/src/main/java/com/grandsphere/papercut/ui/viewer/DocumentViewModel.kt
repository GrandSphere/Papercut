package com.grandsphere.papercut.ui.viewer

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.grandsphere.papercut.PapercutApp
import com.grandsphere.papercut.data.AppFiles
import com.grandsphere.papercut.data.Bookmark
import com.grandsphere.papercut.data.FolioItem
import com.grandsphere.papercut.data.JumpBookmarks
import com.grandsphere.papercut.data.ViewerSettings
import com.grandsphere.papercut.data.ZoomMode
import com.grandsphere.papercut.pdf.MuPdfEngine
import com.grandsphere.papercut.pdf.OutlineItem
import com.grandsphere.papercut.pdf.PageContent
import com.grandsphere.papercut.pdf.PageInfo
import com.grandsphere.papercut.pdf.PageLink
import com.grandsphere.papercut.pdf.PageRotation
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
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import java.io.File
import kotlin.math.abs

/** State and behaviour shared by the single-document viewer and the Folio viewer. */
@OptIn(ExperimentalCoroutinesApi::class)
abstract class DocumentViewModel(application: Application) : AndroidViewModel(application) {
    private val papercut = application as PapercutApp
    protected val repo = papercut.settingsRepository
    protected val bookmarksRepo = papercut.bookmarkRepository
    protected val folioRepo = papercut.folioRepository
    protected val engine = MuPdfEngine()

    data class DocumentState(
        val uri: Uri,
        val pages: List<PageInfo>,
        val outline: List<OutlineItem> = emptyList(),
        val mime: String = "application/pdf",
    )

    val settings: StateFlow<ViewerSettings> = repo.observe().stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        repo.snapshot,
    )
    abstract val viewingSettings: StateFlow<ViewerSettings>

    val folioItems: StateFlow<List<FolioItem>> = folioRepo.observeAll().stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        emptyList(),
    )

    protected val _document = MutableStateFlow<DocumentState?>(null)
    val document: StateFlow<DocumentState?> = _document.asStateFlow()

    private val _busy = MutableStateFlow<String?>(null)
    val busy: StateFlow<String?> = _busy.asStateFlow()

    private val _exportReady = MutableSharedFlow<File>(extraBufferCapacity = 1)
    val exportReady: SharedFlow<File> = _exportReady.asSharedFlow()

    private val _errors = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val errors: SharedFlow<String> = _errors.asSharedFlow()

    private val _pageRotations = MutableStateFlow(IntArray(0))
    val pageRotations: StateFlow<IntArray> = _pageRotations.asStateFlow()

    private val _toasts = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val toasts: SharedFlow<String> = _toasts.asSharedFlow()

    private val _goToBookmark = MutableSharedFlow<Bookmark>(extraBufferCapacity = 1)
    val goToBookmark: SharedFlow<Bookmark> = _goToBookmark.asSharedFlow()

    private var connectedJump: String? = null
    private var panSaveJob: Job? = null

    protected val _pageLoading = MutableStateFlow(false)
    val pageLoading: StateFlow<Boolean> = _pageLoading.asStateFlow()

    private val _startupReady = MutableStateFlow(false)
    val startupReady: StateFlow<Boolean> = _startupReady.asStateFlow()

    private val _hits = MutableStateFlow<List<SearchHit>>(emptyList())
    val hits: StateFlow<List<SearchHit>> = _hits.asStateFlow()

    private val _hitIndex = MutableStateFlow(0)
    val hitIndex: StateFlow<Int> = _hitIndex.asStateFlow()

    private val _searching = MutableStateFlow(false)
    val searching: StateFlow<Boolean> = _searching.asStateFlow()

    protected val _sourceUri = MutableStateFlow<String?>(null)

    private val _passwordNeeded = MutableStateFlow(false)
    val passwordNeeded: StateFlow<Boolean> = _passwordNeeded.asStateFlow()

    private val _passwordWrong = MutableStateFlow(false)
    val passwordWrong: StateFlow<Boolean> = _passwordWrong.asStateFlow()

    val bookmarks: StateFlow<List<Bookmark>> = _sourceUri.flatMapLatest { uri ->
        if (uri.isNullOrBlank()) flowOf(emptyList()) else bookmarksRepo.observeBookmarks(uri)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val renderJobs = HashMap<Int, Job>()
    private var searchJob: Job? = null
    private var pendingPositionRestore = false
    private var pendingRestorePage = 0
    private var pendingRestoreX = 0f
    private var pendingRestoreOffset = 0f

    protected abstract fun mutateView(transform: (ViewerSettings) -> ViewerSettings)

    protected abstract fun exportSource(): Uri?

    protected abstract suspend fun resumeAfterPassword()

    protected open fun clearPendingPassword() {}

    protected fun markStartupReady() {
        _startupReady.value = true
    }

    protected suspend fun emitError(code: String) {
        _errors.emit(code)
    }

    protected fun resetSearchState() {
        _hits.value = emptyList()
        _hitIndex.value = 0
    }

    protected fun cancelAllRenders() {
        renderJobs.values.forEach { it.cancel() }
        renderJobs.clear()
    }

    fun requestPage(index: Int, width: Int, urgent: Boolean = false, onReady: (Int, Int, Bitmap) -> Unit) {
        renderJobs[index]?.cancel()
        val paint = viewingSettings.value
        renderJobs[index] = viewModelScope.launch {
            try {
                val bmp = engine.render(
                    index,
                    width,
                    paint.fgColor,
                    paint.bgColor,
                    paint.imageColorBlend,
                    paint.originalColors,
                    paint.imageAlpha,
                    urgent = urgent,
                    rotateCw = _pageRotations.value.getOrElse(index) { 0 },
                )
                if (isActive) {
                    _pageLoading.value = false
                    val measured = engine.pages.toList()
                    val current = _document.value
                    if (current != null && measured.size == current.pages.size && measured != current.pages) {
                        ensureRotations(measured.size)
                        _document.value = current.copy(pages = measured)
                    }
                    onReady(index, width, bmp)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
            }
        }
    }

    fun cancelStaleRenders(keep: IntRange) {
        val iter = renderJobs.entries.iterator()
        while (iter.hasNext()) {
            val entry = iter.next()
            if (entry.key !in keep) {
                entry.value.cancel()
                iter.remove()
            }
        }
    }

    suspend fun pageContent(index: Int): PageContent {
        val raw = engine.pageContent(index)
        val orig = engine.pages.getOrNull(index) ?: return raw
        return PageRotation.rotateContent(raw, orig, _pageRotations.value.getOrElse(index) { 0 })
    }

    suspend fun pageLinks(index: Int): List<PageLink> =
        runCatching { pageContent(index).links }.getOrDefault(emptyList())

    suspend fun resolveInternalLink(uri: String): Pair<Int, Float?>? = engine.resolveUri(uri)

    protected fun fillPagesAndOutline() {
        val uri = _document.value?.uri ?: return
        viewModelScope.launch {
            val pages = runCatching { engine.measureRemainingPages() }.getOrDefault(engine.pages.toList())
            val outline = runCatching { engine.outline() }.getOrDefault(emptyList())
            val current = _document.value ?: return@launch
            if (current.uri != uri) return@launch
            ensureRotations(pages.size)
            _document.value = current.copy(pages = pages, outline = outline)
        }
    }

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

    open fun setOriginalColors(on: Boolean) {
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
            resetSearchState()
            _searching.value = false
            return
        }
        _searching.value = true
        searchJob = viewModelScope.launch {
            try {
                yield()
                val doc = _document.value
                if (doc == null) {
                    resetSearchState()
                    return@launch
                }
                val pages = doc.pages.indices.map { pageContent(it).chars }
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

    fun selectHit(index: Int) {
        val n = _hits.value.size
        if (n == 0) return
        _hitIndex.value = index.coerceIn(0, n - 1)
    }

    fun nextHit() = stepHit(1)

    fun prevHit() = stepHit(-1)

    private fun stepHit(delta: Int) {
        val n = _hits.value.size
        if (n == 0) return
        _hitIndex.value = (_hitIndex.value + delta + n) % n
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
        viewModelScope.launch {
            val remainingNames = bookmarks.value.filter { it.id !in ids }.map { it.name }.toSet()
            bookmarksRepo.deleteBookmarks(ids)
            if (connectedJump != null &&
                (JumpBookmarks.Jump1 !in remainingNames || JumpBookmarks.Jump2 !in remainingNames)
            ) {
                disconnectJump()
            }
        }
    }

    fun reorderBookmarks(ids: List<Long>) {
        viewModelScope.launch { bookmarksRepo.reorder(ids) }
    }

    fun isPdf(): Boolean = document.value?.mime == "application/pdf"

    fun submitPassword(password: String) {
        viewModelScope.launch {
            val ok = runCatching { engine.authenticatePassword(password) }.getOrDefault(false)
            if (!ok) {
                _passwordWrong.value = true
                return@launch
            }
            resumeAfterPassword()
        }
    }

    fun cancelPassword() {
        viewModelScope.launch { engine.cancelPassword() }
        clearPendingPassword()
        clearPasswordPrompt()
        _pageLoading.value = false
        _document.value = null
    }

    protected fun showPasswordPrompt() {
        _pageLoading.value = false
        _passwordWrong.value = false
        _passwordNeeded.value = true
    }

    protected fun clearPasswordPrompt() {
        _passwordNeeded.value = false
        _passwordWrong.value = false
    }

    protected fun stashRestore(page: Int, scrollX: Float, offsetOnPage: Float, enabled: Boolean = true) {
        pendingRestorePage = page
        pendingRestoreX = if (enabled) scrollX else 0f
        pendingRestoreOffset = if (enabled) offsetOnPage else 0f
        pendingPositionRestore = enabled
    }

    fun consumeRestorePage(): Triple<Int, Float, Float>? {
        if (!pendingPositionRestore) return null
        pendingPositionRestore = false
        return Triple(pendingRestorePage, pendingRestoreX, pendingRestoreOffset)
    }

    fun displayPages(): List<PageInfo> =
        PageRotation.apply(_document.value?.pages.orEmpty(), _pageRotations.value)

    fun encodedRotations(): String = PageRotation.encode(_pageRotations.value)

    fun loadRotations(json: String?, count: Int) {
        _pageRotations.value = PageRotation.decode(json, count)
    }

    fun resetRotations() {
        _pageRotations.value = IntArray(0)
        disconnectJump()
    }

    private fun ensureRotations(count: Int) {
        val cur = _pageRotations.value
        if (cur.size == count) return
        val next = IntArray(count)
        System.arraycopy(cur, 0, next, 0, minOf(cur.size, count))
        _pageRotations.value = next
    }

    fun rotatePage(index: Int) {
        val pages = _document.value?.pages ?: return
        if (index !in pages.indices) return
        ensureRotations(pages.size)
        val next = _pageRotations.value.copyOf()
        next[index] = PageRotation.norm(next[index] + 90)
        _pageRotations.value = next
        disconnectJump()
    }

    fun rotateDocument() {
        val pages = _document.value?.pages ?: return
        if (pages.isEmpty()) return
        ensureRotations(pages.size)
        _pageRotations.value = IntArray(pages.size) { i ->
            PageRotation.norm(_pageRotations.value.getOrElse(i) { 0 } + 90)
        }
        disconnectJump()
    }

    fun hasRotation(): Boolean = _pageRotations.value.any { PageRotation.norm(it) != 0 }

    fun disconnectJump() {
        connectedJump = null
        panSaveJob?.cancel()
    }

    fun onUserPan(page: Int, scrollX: Float, scrollY: Float) {
        if (!viewingSettings.value.quickJumpEnabled) {
            disconnectJump()
            return
        }
        val name = connectedJump ?: return
        val uri = _sourceUri.value ?: return
        panSaveJob?.cancel()
        panSaveJob = viewModelScope.launch {
            kotlinx.coroutines.delay(80)
            bookmarksRepo.upsertNamed(uri, name, page, scrollX, scrollY)
        }
    }

    fun onOpenedBookmark(mark: Bookmark) {
        if (!viewingSettings.value.quickJumpEnabled || !JumpBookmarks.isJumpName(mark.name)) {
            connectedJump = null
            return
        }
        val uri = _sourceUri.value
        if (uri == null) {
            connectedJump = null
            return
        }
        viewModelScope.launch {
            val other = if (mark.name == JumpBookmarks.Jump1) JumpBookmarks.Jump2 else JumpBookmarks.Jump1
            connectedJump = if (bookmarksRepo.findBookmarkByName(uri, other) != null) mark.name else null
        }
    }

    fun performQuickJump(page: Int, scrollX: Float, scrollY: Float) {
        if (!viewingSettings.value.quickJumpEnabled) return
        val uri = _sourceUri.value ?: return
        viewModelScope.launch {
            val jump1 = bookmarksRepo.findBookmarkByName(uri, JumpBookmarks.Jump1)
            val jump2 = bookmarksRepo.findBookmarkByName(uri, JumpBookmarks.Jump2)
            val plan = JumpBookmarks.plan(
                jump1,
                jump2,
                jump1 != null && JumpBookmarks.at(jump1, page, scrollX, scrollY),
            )
            if (plan.createName != null) {
                bookmarksRepo.upsertNamed(uri, plan.createName, page, scrollX, scrollY)
            }
            plan.toast?.let { _toasts.emit(it) }
            val bothExist = (jump1 != null || plan.createName == JumpBookmarks.Jump1) &&
                (jump2 != null || plan.createName == JumpBookmarks.Jump2)
            val goName = plan.goToName
            if (goName != null && bothExist) {
                val mark = bookmarksRepo.findBookmarkByName(uri, goName) ?: return@launch
                connectedJump = goName
                _goToBookmark.emit(mark)
            } else {
                connectedJump = null
            }
        }
    }

    fun exportPartial(pages: List<Int>, keepRotation: Boolean = true) {
        val source = exportSource() ?: return
        viewModelScope.launch {
            _busy.value = "Building PDF"
            runCatching {
                val dest = AppFiles.exportPdf(getApplication())
                val rotates = if (keepRotation) {
                    pages.map { _pageRotations.value.getOrElse(it) { 0 } }
                } else {
                    emptyList()
                }
                PdfComposer.extractPages(getApplication(), source, pages, dest, rotates)
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

    fun exportFull(keepRotation: Boolean) {
        val count = _document.value?.pages?.size ?: return
        if (count <= 0) return
        if (!keepRotation || _pageRotations.value.all { it == 0 }) {
            val source = exportSource() ?: return
            viewModelScope.launch {
                _busy.value = "Building PDF"
                runCatching {
                    val dest = AppFiles.exportPdf(getApplication())
                    PdfComposer.extractPages(
                        getApplication(),
                        source,
                        (0 until count).toList(),
                        dest,
                    )
                    dest
                }.onSuccess {
                    _busy.value = null
                    _exportReady.emit(it)
                }.onFailure {
                    _busy.value = null
                    _errors.emit(it.message ?: "EXPORT")
                }
            }
            return
        }
        exportPartial((0 until count).toList(), keepRotation = true)
    }

    override fun onCleared() {
        cancelAllRenders()
        searchJob?.cancel()
        engine.shutdown()
        super.onCleared()
    }
}
