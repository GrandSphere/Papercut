package com.grandsphere.papercut.ui.viewer

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.FileProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.grandsphere.papercut.PapercutApp
import com.grandsphere.papercut.R
import com.grandsphere.papercut.data.BackupJson
import com.grandsphere.papercut.data.ZoomMode
import com.grandsphere.papercut.pdf.CharBox
import com.grandsphere.papercut.pdf.DocumentMagic
import com.grandsphere.papercut.ui.about.AboutActivity
import com.grandsphere.papercut.ui.settings.SettingsActivity
import com.grandsphere.papercut.ui.theme.PapercutTheme
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File

open class ViewerActivity : AppCompatActivity() {
    private val vm: ViewerViewModel by viewModels()
    private var pdfView: PdfStripView? = null
    private var loadedUri: Uri? = null
    private var selectedText: String? = null
    private var chromeBg: Int = com.grandsphere.papercut.data.ViewerSettings.DEFAULT_BG
    private var previewMode = false

    private var pendingExportSave = false
    private var pendingExportFile: java.io.File? = null
    private var pageSelect by mutableStateOf(false)
    private var selectedPages by mutableStateOf(listOf<Int>())
    private var overlayReset by mutableStateOf(0)

    private val openDocument = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) persistAndOpen(uri)
    }

    private val createExport = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/pdf")
    ) { uri ->
        val src = pendingExportFile ?: return@registerForActivityResult
        if (uri == null) return@registerForActivityResult
        runCatching {
            contentResolver.openOutputStream(uri)?.use { out ->
                src.inputStream().use { it.copyTo(out) }
            }
        }
        Toast.makeText(this, R.string.saved, Toast.LENGTH_SHORT).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val previewPath = intent.getStringExtra(EXTRA_PREVIEW)
        if (previewPath != null) {
            previewMode = true
            WindowCompat.setDecorFitsSystemWindows(window, false)
            hideSystemBars()
            vm.openPreview(File(previewPath))
            setContent {
                val document by vm.document.collectAsStateWithLifecycle()
                val settings by vm.viewingSettings.collectAsStateWithLifecycle()
                PreviewScreen(
                    pages = document?.pages.orEmpty(),
                    background = settings.bgColor,
                    onBack = { finish() },
                    onView = { view ->
                        pdfView = view
                        bindPdf(view)
                        view.setSettings(settings)
                    },
                )
            }
            return
        }
        WindowCompat.setDecorFitsSystemWindows(window, false)
        hideSystemBars()
        val hasDocIntent = intentUri(intent) != null && !isJsonIntent(intent)
        handleIntent(intent)
        vm.maybeRestoreLast(hasDocIntent)
        setContent {
            val settings by vm.viewingSettings.collectAsStateWithLifecycle()
            val document by vm.document.collectAsStateWithLifecycle()
            val hits by vm.hits.collectAsStateWithLifecycle()
            val hitIndex by vm.hitIndex.collectAsStateWithLifecycle()
            val searching by vm.searching.collectAsStateWithLifecycle()
            val bookmarks by vm.bookmarks.collectAsStateWithLifecycle()
            val passwordNeeded by vm.passwordNeeded.collectAsStateWithLifecycle()
            val passwordWrong by vm.passwordWrong.collectAsStateWithLifecycle()
            val busy by vm.busy.collectAsStateWithLifecycle()
            val pageLoading by vm.pageLoading.collectAsStateWithLifecycle()
            val startupReady by vm.startupReady.collectAsStateWithLifecycle()
            PapercutTheme(
                fontScale = settings.fontScale,
                appFontColor = settings.appFontColor,
                backgroundColor = settings.bgColor,
                actionColor = settings.actionColor,
            ) {
                Box(Modifier.fillMaxSize()) {
                ViewerScreen(
                    settings = settings,
                    hasDocument = document != null,
                    outline = document?.outline.orEmpty(),
                    searchHits = hits,
                    hitIndex = hitIndex,
                    searching = searching,
                    bookmarks = bookmarks,
                    pages = document?.pages.orEmpty(),
                    readingPosition = {
                        val view = pdfView
                        Triple(
                            view?.currentPageIndex() ?: 0,
                            view?.currentScrollXPdf() ?: 0f,
                            view?.currentOffsetOnPage() ?: 0f,
                        )
                    },
                    currentZoomPercent = pdfView?.currentZoomPercent() ?: settings.effectivePercent(),
                    zoomMode = pdfView?.zoomMode ?: settings.effectiveMode(),
                    onOpen = {
                        openDocument.launch(DocumentMagic.OPEN_MIME_TYPES)
                    },
                    onSettings = { startActivity(Intent(this@ViewerActivity, SettingsActivity::class.java)) },
                    onGoToPageIndex = { index, y ->
                        pdfView?.goToPage(index, y)
                    },
                    onScrubbing = { on ->
                        pdfView?.setScrubbing(on)
                        if (!on) pdfView?.invalidate()
                    },
                    onFitWidth = {
                        if (document == null) {
                            Toast.makeText(this@ViewerActivity, R.string.no_document, Toast.LENGTH_SHORT).show()
                        } else {
                            pdfView?.applyFitWidth()
                            vm.setZoomMode(ZoomMode.FIT_WIDTH)
                        }
                    },
                    onZoomPercent = { percent ->
                        if (document == null) {
                            Toast.makeText(this@ViewerActivity, R.string.no_document, Toast.LENGTH_SHORT).show()
                        } else {
                            pdfView?.applyCustomZoom(percent)
                            vm.setCustomZoom(percent)
                        }
                    },
                    onLockZoom = vm::setLockZoom,
                    onLockSide = { on ->
                        pdfView?.clearReLockOnFitWidth()
                        vm.setLockSideScroll(on)
                    },
                    onOriginalColors = vm::setOriginalColors,
                    onAbout = { startActivity(Intent(this@ViewerActivity, AboutActivity::class.java)) },
                    onQuit = { finishAffinity() },
                    onCopy = {
                        val text = selectedText
                        if (text != null) {
                            getSystemService(ClipboardManager::class.java)
                                .setPrimaryClip(ClipData.newPlainText("pdf", text))
                            Toast.makeText(this@ViewerActivity, R.string.copied, Toast.LENGTH_SHORT).show()
                        }
                    },
                    onShare = {
                        val text = selectedText
                        if (text != null) {
                            startActivity(
                                Intent.createChooser(
                                    Intent(Intent.ACTION_SEND).apply {
                                        type = "text/plain"
                                        putExtra(Intent.EXTRA_TEXT, text)
                                    },
                                    getString(R.string.share)
                                )
                            )
                        }
                    },
                    onShareDocument = { shareOpenPdf() },
                    inFolio = false,
                    folioEnabled = settings.folioEnabled,
                    folioItems = emptyList(),
                    folioIndex = 0,
                    folioOpened = emptySet(),
                    alreadyInFolio = vm.alreadyInFolio(),
                    isPdf = vm.isPdf(),
                    busy = busy,
                    pageLoading = pageLoading,
                    startupReady = startupReady,
                    overlayReset = overlayReset,
                    onEnterFolio = {
                        startActivity(Intent(this@ViewerActivity, FolioActivity::class.java))
                    },
                    onLeaveFolio = { },
                    onAddToFolio = { vm.addCurrentToFolio() },
                    onFolioIndex = { },
                    onSaveFolio = { },
                    onShareFull = { shareOpenPdf() },
                    onSharePartial = {
                        pageSelect = true
                        selectedPages = emptyList()
                    },
                    onCombine = {
                        startActivity(Intent(this@ViewerActivity, CombineActivity::class.java))
                    },
                    onManageFolio = { },
                    pageSelectMode = pageSelect,
                    selectedPages = selectedPages,
                        onTogglePage = { page ->
                            selectedPages = if (page in selectedPages) selectedPages - page else selectedPages + page
                        },
                        onSetSelectedPages = { selectedPages = it },
                    onExitPageSelect = {
                        pageSelect = false
                        selectedPages = emptyList()
                    },
                    onShareSelectedPages = {
                        if (selectedPages.isNotEmpty()) {
                            pendingExportSave = false
                            vm.exportPartial(selectedPages.toList())
                        }
                    },
                    passwordNeeded = passwordNeeded,
                    passwordWrong = passwordWrong,
                    onSubmitPassword = vm::submitPassword,
                    onCancelPassword = vm::cancelPassword,
                    onQuery = vm::search,
                    onPrevHit = vm::prevHit,
                    onNextHit = vm::nextHit,
                    onSelectHit = vm::selectHit,
                    onAddBookmark = vm::addBookmark,
                    onUpdateBookmark = vm::updateBookmark,
                    onDeleteBookmarks = vm::deleteBookmarks,
                    onReorderBookmarks = vm::reorderBookmarks,
                    onJumpBookmark = { mark ->
                        pdfView?.goToPageOffset(mark.pageIndex, mark.scrollYPdf, mark.scrollXPdf)
                    },
                    pdfFactory = {
                            PdfStripView(it).also { view ->
                                pdfView = view
                                bindPdf(view)
                                view.setSettings(settings)
                            }
                    }
                )
                }
            }

            LaunchedEffect(settings.bgColor) {
                chromeBg = settings.bgColor
                window.statusBarColor = settings.bgColor
                window.navigationBarColor = settings.bgColor
            }
            LaunchedEffect(settings) {
                pdfView?.setSettings(settings)
            }
            LaunchedEffect(document?.uri, document?.pages) {
                val view = pdfView ?: return@LaunchedEffect
                val doc = document
                if (doc == null) {
                    if (loadedUri != null) {
                        loadedUri = null
                        view.setDocument(emptyList())
                    }
                    return@LaunchedEffect
                }
                if (doc.uri != loadedUri) {
                    loadedUri = doc.uri
                    vm.consumeRestorePage()?.let { (page, x, offset) ->
                        view.pendingGoToPage(page, offset, x)
                    }
                    view.setDocument(doc.pages)
                } else {
                    view.replacePageSizes(doc.pages)
                }
            }
            LaunchedEffect(hits, hitIndex) {
                pdfView?.setSearchHits(hits, hitIndex)
            }
        }

        lifecycleScope.launch {
            vm.errors.collect { code ->
                val msg = when (code) {
                    "FOLIO_ADDED" -> "Added to Folio"
                    "FOLIO_COPY" -> "Could not add to Folio"
                    "NOT_PDF" -> "PDF files only"
                    "PASSWORD" -> getString(R.string.password_protected)
                    "EXPORT" -> "Could not build PDF"
                    else -> getString(R.string.open_failed)
                }
                Toast.makeText(this@ViewerActivity, msg, Toast.LENGTH_SHORT).show()
            }
        }
        lifecycleScope.launch {
            vm.launchFolio.collect {
                startActivity(Intent(this@ViewerActivity, FolioActivity::class.java))
            }
        }
        lifecycleScope.launch {
            vm.exportReady.collect { file ->
                pendingExportFile = file
                pageSelect = false
                selectedPages = emptyList()
                if (pendingExportSave) {
                    createExport.launch("Papercut.pdf")
                } else {
                    shareExportFile(file)
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        if (!previewMode) (application as PapercutApp).registerViewer(this)
    }

    override fun onDestroy() {
        if (!previewMode) (application as PapercutApp).unregisterViewer(this)
        super.onDestroy()
    }

    override fun onPause() {
        if (!previewMode) saveCurrentReadingPosition()
        super.onPause()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (previewMode) return
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    private fun bindPdf(view: PdfStripView) {
        view.callbacks = object : PdfStripView.Callbacks {
            override fun requestPage(index: Int, width: Int, urgent: Boolean) {
                val key = view.paintKey()
                vm.requestPage(index, width, urgent) { page, w, bmp ->
                    view.post {
                        if (!isDestroyed) view.setPageBitmap(page, w, bmp, key)
                    }
                }
            }

            override fun cancelRendersExcept(keep: Set<Int>) {
                vm.cancelStaleRenders(keep)
            }

            override fun loadText(index: Int, done: (List<CharBox>) -> Unit) {
                lifecycleScope.launch {
                    val chars = runCatching { vm.pageContent(index).chars }.getOrDefault(emptyList())
                    view.post { done(chars) }
                }
            }

            override fun requestLinks(index: Int) {
                lifecycleScope.launch {
                    val links = vm.pageLinks(index)
                    view.post {
                        if (!isDestroyed) view.setPageLinks(index, links)
                    }
                }
            }

            override fun onPinchEnd(percent: Float) {
                vm.setPinchZoom(percent)
            }

            override fun onTapZoom(mode: ZoomMode, percent: Float) {
                when (mode) {
                    ZoomMode.FIT_WIDTH, ZoomMode.FIT_PAGE -> vm.setZoomMode(mode)
                    ZoomMode.CUSTOM -> vm.setCustomZoom(percent)
                }
            }

            override fun onLockSideScroll(on: Boolean) {
                vm.setLockSideScroll(on)
            }

            override fun onSelectionText(text: String?) {
                selectedText = text
            }

            override fun onVisiblePage(index: Int, count: Int) {
            }

            override fun onDoubleTap() {
            }

            override fun onLinkTapped(uri: String, isExternal: Boolean) {
                followLink(uri, isExternal)
            }
        }
    }

    private fun shareOpenPdf() {
        if (vm.document.value == null) {
            Toast.makeText(this, R.string.no_document, Toast.LENGTH_SHORT).show()
            return
        }
        val content = vm.sourceContentUri()
        val uri = when {
            content != null -> content
            vm.lastPdfCopy().exists() -> FileProvider.getUriForFile(
                this,
                "$packageName.fileprovider",
                vm.lastPdfCopy(),
            )
            else -> {
                Toast.makeText(this, R.string.no_document, Toast.LENGTH_SHORT).show()
                return
            }
        }
        val mime = DocumentMagic.shareType(vm.document.value?.mime ?: DocumentMagic.PDF)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri("document", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(send, getString(R.string.share)))
    }

    private fun persistAndOpen(uri: Uri) {
        overlayReset += 1
        pageSelect = false
        selectedPages = emptyList()
        persistUri(uri)
        val view = pdfView
        vm.open(
            uri,
            previousPage = view?.currentPageIndex(),
            previousScrollX = view?.currentScrollXPdf(),
            previousScrollY = view?.currentScrollYPdf(),
        )
    }

    private fun persistUri(uri: Uri) {
        DocumentMagic.persistRead(this, uri)
    }

    private fun shareExportFile(file: java.io.File) {
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = DocumentMagic.PDF
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri("document", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(send, getString(R.string.share)))
    }

    private fun saveCurrentReadingPosition() {
        val view = pdfView ?: return
        if (vm.document.value == null) return
        vm.saveReadingPosition(
            view.currentPageIndex(),
            view.currentScrollXPdf(),
            view.currentOffsetOnPage(),
        )
    }

    private fun hideSystemBars() {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        window.statusBarColor = chromeBg
        window.navigationBarColor = chromeBg
    }

    private fun followLink(uri: String, isExternal: Boolean) {
        val external = externalUri(uri)
        if (external != null) {
            openExternal(external.first, external.second)
            return
        }
        if (isExternal) {
            val parsed = Uri.parse(uri)
            openExternal(Intent(Intent.ACTION_VIEW, parsed), parsed)
            return
        }
        lifecycleScope.launch {
            val dest = vm.resolveInternalLink(uri) ?: return@launch
            pdfView?.goToPage(dest.first, dest.second)
        }
    }

    private fun externalUri(raw: String): Pair<Intent, Uri>? {
        val text = raw.trim()
        if (text.isEmpty()) return null
        val lower = text.lowercase()
        val uri = when {
            lower.startsWith("tel:") || lower.startsWith("callto:") -> {
                val number = text.substringAfter(":").filter { it.isDigit() || it == '+' }
                if (number.isEmpty()) null else Uri.parse("tel:$number")
            }
            lower.startsWith("mailto:") -> Uri.parse(text)
            lower.startsWith("http://") || lower.startsWith("https://") -> Uri.parse(text)
            lower.startsWith("sms:") || lower.startsWith("smsto:") -> Uri.parse(text)
            lower.startsWith("www.") -> Uri.parse("https://$text")
            else -> null
        } ?: return null
        val action = if (uri.scheme.equals("tel", ignoreCase = true)) {
            Intent.ACTION_DIAL
        } else {
            Intent.ACTION_VIEW
        }
        return Intent(action, uri) to uri
    }

    private fun openExternal(intent: Intent, uri: Uri) {
        val launched = runCatching {
            startActivity(intent)
            true
        }.getOrDefault(false)
        if (!launched) {
            runCatching { startActivity(Intent.createChooser(Intent(intent.action, uri), null)) }
        }
    }

    private fun importJson(source: Intent): Boolean {
        val uri = intentUri(source) ?: return false
        val text = runCatching {
            contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
        }.getOrNull() ?: return false
        val parsed = runCatching { BackupJson.parse(text) }.getOrNull()
        if (parsed == null) {
            Toast.makeText(this, "Not a Papercut file", Toast.LENGTH_SHORT).show()
            return true
        }
        val app = application as PapercutApp
        runBlocking {
            parsed.settingsJson?.let { obj ->
                app.settingsRepository.update { current -> BackupJson.mergeSettings(current, obj) }
            }
            app.bookmarkRepository.importParsed(parsed)
        }
        Toast.makeText(this, "Imported", Toast.LENGTH_SHORT).show()
        return true
    }

    private fun isJsonIntent(source: Intent): Boolean {
        val type = source.type?.lowercase()
        if (type == "application/json" || type == "text/json") return true
        val uri = intentUri(source) ?: return false
        val name = uri.lastPathSegment?.lowercase().orEmpty()
        return name.endsWith(".json")
    }

    private fun intentUri(intent: Intent?): Uri? {
        if (intent == null) return null
        return when (intent.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> {
                if (android.os.Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_STREAM)
                }
            }
            else -> null
        }
    }

    private fun handleIntent(intent: Intent?) {
        if (intent != null && isJsonIntent(intent)) {
            importJson(intent)
            return
        }
        val uri = intentUri(intent) ?: return
        persistAndOpen(uri)
    }

    companion object {
        const val EXTRA_PREVIEW = "com.grandsphere.papercut.PREVIEW"
    }
}
