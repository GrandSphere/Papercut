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
import com.grandsphere.papercut.data.ZoomMode
import com.grandsphere.papercut.pdf.CharBox
import com.grandsphere.papercut.pdf.DocumentMagic
import com.grandsphere.papercut.ui.about.AboutActivity
import com.grandsphere.papercut.ui.settings.SettingsActivity
import com.grandsphere.papercut.ui.theme.PapercutTheme
import kotlinx.coroutines.launch

class FolioActivity : AppCompatActivity() {
    private val vm: FolioViewModel by viewModels()
    private var pdfView: PdfStripView? = null
    private var loadedUri: Uri? = null
    private var selectedText: String? = null
    private var chromeBg: Int = com.grandsphere.papercut.data.ViewerSettings.DEFAULT_BG
    private var pendingExportSave = false
    private var pendingExportFile: java.io.File? = null
    private var manageFolio by mutableStateOf(false)
    private var pageSelect by mutableStateOf(false)
    private var selectedPages by mutableStateOf(listOf<Int>())

    private val addDocuments = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        if (uris.isNullOrEmpty()) return@registerForActivityResult
        val total = uris.size
        uris.forEach { DocumentMagic.persistRead(this, it) }
        val valid = uris.filter { DocumentMagic.isReadable(this, it) }
        vm.addFromUris(valid) { added ->
            Toast.makeText(this, "Added $added/$total files", Toast.LENGTH_SHORT).show()
        }
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
        WindowCompat.setDecorFitsSystemWindows(window, false)
        hideSystemBars()
        setContent {
            val settings by vm.viewingSettings.collectAsStateWithLifecycle()
            val document by vm.document.collectAsStateWithLifecycle()
            val hits by vm.hits.collectAsStateWithLifecycle()
            val hitIndex by vm.hitIndex.collectAsStateWithLifecycle()
            val searching by vm.searching.collectAsStateWithLifecycle()
            val bookmarks by vm.bookmarks.collectAsStateWithLifecycle()
            val passwordNeeded by vm.passwordNeeded.collectAsStateWithLifecycle()
            val passwordWrong by vm.passwordWrong.collectAsStateWithLifecycle()
            val folioItems by vm.visibleFolioItems.collectAsStateWithLifecycle()
            val folioIndex by vm.folioIndex.collectAsStateWithLifecycle()
            val busy by vm.busy.collectAsStateWithLifecycle()
            val pageLoading by vm.pageLoading.collectAsStateWithLifecycle()
            val startupReady by vm.startupReady.collectAsStateWithLifecycle()
            val allFolio by vm.folioItems.collectAsStateWithLifecycle()
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
                        onOpen = { addDocuments.launch(DocumentMagic.OPEN_MIME_TYPES) },
                        onSettings = { startActivity(Intent(this@FolioActivity, SettingsActivity::class.java)) },
                        onGoToPageIndex = { index, y ->
                            pdfView?.goToPage(index, y)
                        },
                        onScrubbing = { on ->
                            pdfView?.setScrubbing(on)
                            if (!on) pdfView?.invalidate()
                        },
                        onFitWidth = {
                            if (document == null) {
                                Toast.makeText(this@FolioActivity, R.string.no_document, Toast.LENGTH_SHORT).show()
                            } else {
                                pdfView?.applyFitWidth()
                                vm.setZoomMode(ZoomMode.FIT_WIDTH)
                            }
                        },
                        onZoomPercent = { percent ->
                            if (document == null) {
                                Toast.makeText(this@FolioActivity, R.string.no_document, Toast.LENGTH_SHORT).show()
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
                        onAbout = { startActivity(Intent(this@FolioActivity, AboutActivity::class.java)) },
                        onQuit = { finishAffinity() },
                        onCopy = {
                            val text = selectedText
                            if (text != null) {
                                getSystemService(ClipboardManager::class.java)
                                    .setPrimaryClip(ClipData.newPlainText("pdf", text))
                                Toast.makeText(this@FolioActivity, R.string.copied, Toast.LENGTH_SHORT).show()
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
                        onShareDocument = { shareCurrentCopy() },
                        inFolio = true,
                        folioEnabled = true,
                        folioItems = folioItems,
                        folioIndex = folioIndex,
                        folioOpened = emptySet(),
                        alreadyInFolio = true,
                        isPdf = vm.isPdf(),
                        busy = busy,
                        pageLoading = pageLoading,
                        startupReady = startupReady,
                        onEnterFolio = { },
                        onLeaveFolio = { finish() },
                        onAddToFolio = { },
                        onFolioIndex = { index ->
                            rememberFolioPosition()
                            vm.showFolioIndex(index)
                        },
                        onSaveFolio = {
                            saveCurrentFolioPosition()
                            Toast.makeText(this@FolioActivity, R.string.saved, Toast.LENGTH_SHORT).show()
                        },
                        onShareFull = { shareCurrentCopy() },
                        onSharePartial = {
                            pageSelect = true
                            selectedPages = emptyList()
                        },
                        onCombine = { },
                        onManageFolio = { manageFolio = true },
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
                        },
                    )
                    if (manageFolio) {
                        ManageFolioOverlay(
                            items = allFolio,
                            onDismiss = { manageFolio = false },
                            onDelete = vm::deleteFolioItems,
                            onReorder = vm::reorderFolio,
                            onToggleHidden = vm::setFolioHidden,
                            onSetStart = vm::setFolioStart,
                        )
                    }
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
                    loadedUri = null
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
                    "NOT_PDF" -> "PDF files only"
                    "PASSWORD" -> getString(R.string.password_protected)
                    "EXPORT" -> "Could not build PDF"
                    else -> getString(R.string.open_failed)
                }
                Toast.makeText(this@FolioActivity, msg, Toast.LENGTH_SHORT).show()
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
        (application as PapercutApp).registerFolio(this)
    }

    override fun onPause() {
        rememberFolioPosition()
        super.onPause()
    }

    override fun onDestroy() {
        (application as PapercutApp).unregisterFolio(this)
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    private fun bindPdf(view: PdfStripView) {
        view.folioPaging = true
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
                    val links = runCatching { vm.pageLinks(index) }.getOrDefault(emptyList())
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

            override fun onLinkTapped(link: String, isExternal: Boolean) {
                followLink(link, isExternal)
            }

            override fun onFolioSwipe(delta: Int) {
                val items = vm.visibleFolioItems.value
                if (items.isEmpty()) return
                val next = (vm.folioIndex.value + delta).coerceIn(0, items.lastIndex)
                if (next == vm.folioIndex.value) return
                rememberFolioPosition()
                vm.showFolioIndex(next)
            }
        }
    }

    private fun rememberFolioPosition() {
        if (!vm.storesLastPosition()) return
        saveCurrentFolioPosition()
    }

    private fun saveCurrentFolioPosition() {
        val view = pdfView ?: return
        if (vm.document.value == null) return
        vm.saveFolioSnapshot(
            view.currentPageIndex(),
            view.currentScrollXPdf(),
            view.currentOffsetOnPage(),
        )
    }

    private fun shareCurrentCopy() {
        val file = vm.currentCopy()
        if (file == null) {
            Toast.makeText(this, R.string.no_document, Toast.LENGTH_SHORT).show()
            return
        }
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        val mime = DocumentMagic.shareType(vm.document.value?.mime ?: DocumentMagic.PDF)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri("document", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(send, getString(R.string.share)))
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

    private fun hideSystemBars() {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        window.statusBarColor = chromeBg
        window.navigationBarColor = chromeBg
    }

    private fun followLink(uri: String, isExternal: Boolean) {
        val text = uri.trim()
        val lower = text.lowercase()
        val parsed = when {
            lower.startsWith("http://") || lower.startsWith("https://") -> Uri.parse(text)
            lower.startsWith("mailto:") -> Uri.parse(text)
            lower.startsWith("www.") -> Uri.parse("https://$text")
            else -> if (isExternal) Uri.parse(text) else null
        }
        if (parsed != null) {
            runCatching { startActivity(Intent(Intent.ACTION_VIEW, parsed)) }
            return
        }
        lifecycleScope.launch {
            val dest = vm.resolveInternalLink(uri) ?: return@launch
            pdfView?.goToPage(dest.first, dest.second)
        }
    }
}
