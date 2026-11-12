package com.grandsphere.papercut.ui.viewer

import android.app.Application
import android.net.Uri
import androidx.lifecycle.viewModelScope
import com.grandsphere.papercut.data.AppFiles
import com.grandsphere.papercut.data.DocumentPaths
import com.grandsphere.papercut.data.PdfDocument
import com.grandsphere.papercut.data.ViewerSettings
import com.grandsphere.papercut.data.copyAtomically
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

class ViewerViewModel(application: Application) : DocumentViewModel(application) {
    private val startupDarkActive = MutableStateFlow(repo.snapshot.alwaysDarkMode)

    private val _launchFolio = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val launchFolio: SharedFlow<Unit> = _launchFolio.asSharedFlow()

    override val viewingSettings: StateFlow<ViewerSettings> = combine(
        settings,
        startupDarkActive,
    ) { s, dark -> if (dark) s.asDark() else s }.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        repo.snapshot.let { if (it.alwaysDarkMode) it.asDark() else it },
    )

    private val openGate = Mutex()
    private var openGeneration = 0
    private var openJob: Job? = null
    private var restoredLast = false
    private var pendingOpenUri: Uri? = null
    private var pendingFromLastPdf = false

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

    private fun ViewerSettings.asDark(): ViewerSettings = copy(
        originalColors = false,
        imageColorBlend = darkImageBlend,
        imageAlpha = darkImageAlpha,
    )

    override fun mutateView(transform: (ViewerSettings) -> ViewerSettings) {
        viewModelScope.launch { repo.update(transform) }
    }

    override fun setOriginalColors(on: Boolean) {
        startupDarkActive.value = false
        super.setOriginalColors(on)
    }

    override fun exportSource(): Uri? =
        _document.value?.uri ?: _sourceUri.value?.let { Uri.parse(it) }

    override fun clearPendingPassword() {
        pendingOpenUri = null
        pendingFromLastPdf = false
    }

    override suspend fun resumeAfterPassword() {
        val uri = pendingOpenUri ?: return
        val fromLastPdf = pendingFromLastPdf
        clearPendingPassword()
        clearPasswordPrompt()
        completeOpen(uri, fromLastPdf)
    }

    private fun startOpen(body: suspend (generation: Int) -> Unit) {
        val generation = ++openGeneration
        openJob?.cancel()
        cancelAllRenders()
        _document.value = null
        resetSearchState()
        _pageLoading.value = true
        openJob = viewModelScope.launch {
            openGate.withLock {
                if (generation == openGeneration) body(generation)
            }
        }
    }

    fun open(
        uri: Uri,
        reportError: Boolean = true,
        previousPage: Int? = null,
        previousScrollX: Float? = null,
        previousScrollY: Float? = null,
    ) = startOpen { generation ->
        val previousKey = _sourceUri.value
        if (
            previousKey != null &&
            previousPage != null &&
            previousScrollX != null &&
            previousScrollY != null
        ) {
            bookmarksRepo.saveLastPosition(previousKey, previousPage, previousScrollX, previousScrollY)
            if (repo.get().restoreLastPdf) {
                repo.update {
                    it.copy(
                        lastPageIndex = previousPage,
                        lastScrollXPdf = previousScrollX,
                        lastScrollYPdf = previousScrollY,
                    )
                }
            }
        }
        if (generation != openGeneration) return@startOpen
        try {
            val s = repo.get()
            val fromLastPdf = isLastPdfCopy(uri)
            val label = documentLabel(uri, fromLastPdf)
            val stored = resolveStoredDocument(uri, fromLastPdf, label)
            val startPage = if (s.restoreLastPosition) stored?.lastPageIndex ?: 0 else 0
            engine.open(getApplication(), uri, s.fontScale, startPage = startPage)
            if (generation != openGeneration) return@startOpen
            completeOpen(uri, fromLastPdf)
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            if (generation != openGeneration) return@startOpen
            if (t.message == "PASSWORD") {
                pendingOpenUri = uri
                pendingFromLastPdf = false
                showPasswordPrompt()
                return@startOpen
            }
            _document.value = null
            _pageLoading.value = false
            if (reportError) emitError("OPEN")
        }
    }

    fun openPreview(file: File) = startOpen { generation ->
        try {
            engine.openFile(file, repo.get().fontScale, startPage = 0)
            if (generation != openGeneration) return@startOpen
            _document.value = DocumentState(Uri.fromFile(file), engine.pages.toList(), emptyList(), engine.mime)
            _pageLoading.value = false
            fillPagesAndOutline()
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            if (generation != openGeneration) return@startOpen
            _document.value = null
            _pageLoading.value = false
            emitError("OPEN")
        }
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
        resetRotations()
        _sourceUri.value = doc?.uri
        val restorePos = s.restoreLastPosition
        stashRestore(
            page = if (restorePos) doc?.lastPageIndex ?: s.lastPageIndex else 0,
            scrollX = if (restorePos) doc?.lastScrollXPdf ?: s.lastScrollXPdf else 0f,
            offsetOnPage = if (restorePos) doc?.lastScrollYPdf ?: s.lastScrollYPdf else 0f,
            enabled = restorePos,
        )
        _pageLoading.value = true
        _document.value = DocumentState(uri, engine.pages.toList(), emptyList(), engine.mime)
        resetSearchState()
        if (!lastPdf && s.restoreLastPdf) {
            copyLastPdf(uri)
            if (generation != openGeneration) return
            repo.update { it.copy(lastPdfUri = doc?.uri ?: uri.toString()) }
        }
        if (generation != openGeneration) return
        fillPagesAndOutline()
    }

    fun maybeRestoreLast(hasDocumentIntent: Boolean) {
        if (hasDocumentIntent || restoredLast) {
            restoredLast = true
            markStartupReady()
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
                val file = lastPdfCopy()
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
                            showPasswordPrompt()
                        } else {
                            _pageLoading.value = false
                            _document.value = null
                        }
                    }
                }
            } finally {
                markStartupReady()
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

    fun alreadyInFolio(): Boolean {
        val key = _sourceUri.value ?: return false
        return folioItems.value.any { it.uri == key }
    }

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
            emitError(if (added != null) "FOLIO_ADDED" else "FOLIO_COPY")
        }
    }

    fun lastPdfCopy(): File = AppFiles.lastPdf(getApplication())

    fun sourceContentUri(): Uri? {
        val raw = _sourceUri.value ?: return null
        return Uri.parse(raw).takeIf { it.scheme.equals("content", ignoreCase = true) }
    }

    private fun isLastPdfCopy(uri: Uri): Boolean {
        if (!uri.scheme.equals("file", ignoreCase = true)) return false
        val path = uri.path ?: return false
        return File(path).absoluteFile.normalize() == lastPdfCopy().absoluteFile.normalize()
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
            return bookmarksRepo.getDocument(lastKey)
        }
        return null
    }

    private suspend fun resolveStoredDocument(
        uri: Uri,
        fromLastPdf: Boolean,
        label: DocumentPaths.Label,
    ): PdfDocument? {
        val found = if (fromLastPdf || isLastPdfCopy(uri)) {
            resolveLastPdfDocument(repo.get())
        } else {
            bookmarksRepo.getDocument(uri.toString())
        }
        return found ?: bookmarksRepo.findByNameAndPath(label.displayName, label.displayPath)
    }

    private suspend fun copyLastPdf(uri: Uri) {
        val dest = lastPdfCopy()
        if (uri.scheme == "file" && uri.path == dest.absolutePath) return
        copyAtomically(dest, File(dest.parentFile, "${AppFiles.LAST_PDF}.tmp")) {
            getApplication<Application>().contentResolver.openInputStream(uri)
        }
    }
}
