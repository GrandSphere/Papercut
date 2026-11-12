package com.grandsphere.papercut.ui.viewer

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.grandsphere.papercut.R
import com.grandsphere.papercut.data.FolioItem
import com.grandsphere.papercut.data.PenButtonAction
import com.grandsphere.papercut.data.ViewerSettings
import com.grandsphere.papercut.data.ZoomMode
import com.grandsphere.papercut.pdf.CharBox
import com.grandsphere.papercut.pdf.PageRotation
import com.grandsphere.papercut.ui.about.AboutActivity
import com.grandsphere.papercut.ui.settings.SettingsActivity
import com.grandsphere.papercut.ui.theme.PapercutTheme
import kotlinx.coroutines.launch

/** Folio-related inputs of [ViewerScreen] that differ between the viewer and the Folio host. */
class HostFolio(
    val enabled: Boolean,
    val items: List<FolioItem> = emptyList(),
    val index: Int = 0,
    val alreadyIn: Boolean = false,
)

/** Shared host for [ViewerScreen] + [PdfStripView]; subclasses provide only what differs. */
abstract class PdfHostActivity : AppCompatActivity() {
    protected abstract val vm: DocumentViewModel

    protected var pdfView: PdfStripView? = null
    protected var chromeBg: Int = ViewerSettings.DEFAULT_BG
    protected var pageSelect by mutableStateOf(false)
    protected var selectedPages by mutableStateOf(listOf<Int>())
    protected var overlayReset by mutableStateOf(0)
    protected var presenting by mutableStateOf(false)
    protected var openBookmarksTick by mutableIntStateOf(0)
    private var presenterZoomMode: ZoomMode? = null
    private var presenterZoomPercent: Float = 100f

    private var loadedUri: Uri? = null
    private var selectedText: String? = null
    private var lastAppliedRotations: IntArray = IntArray(0)

    protected open val isFolio: Boolean = false
    protected open val clearViewWhenNoDocument: Boolean = true

    @Composable
    protected abstract fun hostFolio(settings: ViewerSettings): HostFolio

    @Composable
    protected open fun HostOverlays() {}

    protected abstract fun onOpenRequested()
    protected abstract fun shareDocument(keepRotation: Boolean = true)
    protected open fun onEnterFolio() {}
    protected open fun onLeaveFolio() {}
    protected open fun onAddToFolio() {}
    protected open fun onFolioIndex(index: Int) {}
    protected open fun onSaveFolio() {}
    protected open fun onCombine() {}
    protected open fun onManageFolio() {}
    protected open fun onFolioSwipe(delta: Int) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        hideSystemBars(chromeBg)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars(chromeBg)
    }

    protected fun setHostContent() {
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
            val rotations by vm.pageRotations.collectAsStateWithLifecycle()
            val folio = hostFolio(settings)
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
                        pages = PageRotation.apply(document?.pages.orEmpty(), rotations),
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
                        onOpen = { onOpenRequested() },
                        onSettings = { startActivity(Intent(this@PdfHostActivity, SettingsActivity::class.java)) },
                        onGoToPageIndex = { index, y ->
                            vm.disconnectJump()
                            pdfView?.goToPage(index, y)
                        },
                        onScrubbing = { on ->
                            pdfView?.setScrubbing(on)
                            if (!on) pdfView?.invalidate()
                        },
                        onFitWidth = {
                            if (document == null) {
                                toast(R.string.no_document)
                            } else {
                                presenting = false
                                pdfView?.setPresentation(false)
                                vm.disconnectJump()
                                pdfView?.applyFitWidth()
                                vm.setZoomMode(ZoomMode.FIT_WIDTH)
                            }
                        },
                        onFitHeight = {
                            if (document == null) {
                                toast(R.string.no_document)
                            } else {
                                presenting = false
                                pdfView?.setPresentation(false)
                                vm.disconnectJump()
                                pdfView?.applyFitHeight()
                                vm.setZoomMode(ZoomMode.FIT_HEIGHT)
                            }
                        },
                        onRotatePage = {
                            val page = pdfView?.currentPageIndex() ?: 0
                            vm.rotatePage(page)
                        },
                        onRotateDocument = vm::rotateDocument,
                        onQuickJump = {
                            val view = pdfView ?: return@ViewerScreen
                            vm.performQuickJump(
                                view.currentPageIndex(),
                                view.currentScrollXPdf(),
                                view.currentOffsetOnPage(),
                            )
                        },
                        presenting = presenting,
                        onPresenter = { on -> setPresenter(on) },
                        onZoomPercent = { percent ->
                            if (document == null) {
                                toast(R.string.no_document)
                            } else {
                                vm.disconnectJump()
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
                        onAbout = { startActivity(Intent(this@PdfHostActivity, AboutActivity::class.java)) },
                        onQuit = { finishAffinity() },
                        onCopy = { selectedText?.let { copyText(it) } },
                        onShare = { selectedText?.let { shareText(it) } },
                        onShareDocument = { shareDocument() },
                        inFolio = isFolio,
                        folioEnabled = folio.enabled,
                        folioItems = folio.items,
                        folioIndex = folio.index,
                        folioOpened = emptySet(),
                        alreadyInFolio = folio.alreadyIn,
                        isPdf = vm.isPdf(),
                        busy = busy,
                        pageLoading = pageLoading,
                        startupReady = startupReady,
                        overlayReset = overlayReset,
                        openBookmarksTick = openBookmarksTick,
                        onEnterFolio = { onEnterFolio() },
                        onLeaveFolio = { onLeaveFolio() },
                        onAddToFolio = { onAddToFolio() },
                        onFolioIndex = { onFolioIndex(it) },
                        onSaveFolio = { onSaveFolio() },
                        onShareFull = { keep -> shareDocument(keep) },
                        onSharePartial = {
                            pageSelect = true
                            selectedPages = emptyList()
                        },
                        onCombine = { onCombine() },
                        onManageFolio = { onManageFolio() },
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
                        onShareSelectedPages = { keep ->
                            if (selectedPages.isNotEmpty()) vm.exportPartial(selectedPages.toList(), keep)
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
                            vm.onOpenedBookmark(mark)
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
                    HostOverlays()
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
            LaunchedEffect(document?.uri, document?.pages, rotations) {
                val view = pdfView ?: return@LaunchedEffect
                val doc = document
                val display = PageRotation.apply(doc?.pages.orEmpty(), rotations)
                if (doc == null) {
                    if (!clearViewWhenNoDocument) {
                        loadedUri = null
                    } else if (loadedUri != null) {
                        loadedUri = null
                        presenting = false
                        view.setPresentation(false)
                        view.setDocument(emptyList())
                    }
                    return@LaunchedEffect
                }
                val rotChanged = !lastAppliedRotations.contentEquals(rotations)
                lastAppliedRotations = rotations.copyOf()
                if (doc.uri != loadedUri) {
                    loadedUri = doc.uri
                    presenting = false
                    view.setPresentation(false)
                    vm.consumeRestorePage()?.let { (page, x, offset) ->
                        view.pendingGoToPage(page, offset, x)
                    }
                    view.setDocument(display)
                } else {
                    view.replacePageSizes(display, recycle = rotChanged)
                }
            }
            LaunchedEffect(hits, hitIndex) {
                pdfView?.setSearchHits(hits, hitIndex)
            }
        }

        lifecycleScope.launch {
            vm.errors.collect { code -> toast(errorMessage(code)) }
        }
        lifecycleScope.launch {
            vm.toasts.collect { toast(it) }
        }
        lifecycleScope.launch {
            vm.goToBookmark.collect { mark ->
                pdfView?.goToPageOffset(mark.pageIndex, mark.scrollYPdf, mark.scrollXPdf)
            }
        }
        lifecycleScope.launch {
            vm.exportReady.collect { file ->
                pageSelect = false
                selectedPages = emptyList()
                shareFile(file)
            }
        }
    }

    protected fun bindPdf(view: PdfStripView) {
        view.callbacks = object : PdfStripView.Callbacks {
            override fun requestPage(index: Int, width: Int, urgent: Boolean) {
                val key = view.paintKey()
                vm.requestPage(index, width, urgent) { page, w, bmp ->
                    view.post {
                        if (isDestroyed) bmp.recycle() else view.setPageBitmap(page, w, bmp, key)
                    }
                }
            }

            override fun cancelRendersExcept(keep: IntRange) {
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
                    ZoomMode.FIT_WIDTH, ZoomMode.FIT_PAGE, ZoomMode.FIT_HEIGHT -> vm.setZoomMode(mode)
                    ZoomMode.CUSTOM -> vm.setCustomZoom(percent)
                }
            }

            override fun onLockSideScroll(on: Boolean) {
                vm.setLockSideScroll(on)
            }

            override fun onSelectionText(text: String?) {
                selectedText = text
            }

            override fun onLinkTapped(uri: String, isExternal: Boolean) {
                followLink(uri, isExternal)
            }

            override fun onFolioSwipe(delta: Int) {
                this@PdfHostActivity.onFolioSwipe(delta)
            }

            override fun onUserPan() {
                val view = pdfView ?: return
                vm.onUserPan(view.currentPageIndex(), view.currentScrollXPdf(), view.currentOffsetOnPage())
            }

            override fun onNonScrollNavigation() {
                vm.disconnectJump()
            }

            override fun onPenAction(action: PenButtonAction) {
                when (action) {
                    PenButtonAction.OPEN_BOOKMARKS -> openBookmarksTick += 1
                    PenButtonAction.ADD_QUICK_BOOKMARK -> {
                        val view = pdfView ?: return
                        vm.addBookmark(
                            "Quick Bookmark",
                            view.currentPageIndex(),
                            view.currentScrollXPdf(),
                            view.currentOffsetOnPage(),
                        )
                    }
                    PenButtonAction.QUICK_JUMP -> {
                        val view = pdfView ?: return
                        vm.performQuickJump(
                            view.currentPageIndex(),
                            view.currentScrollXPdf(),
                            view.currentOffsetOnPage(),
                        )
                    }
                    PenButtonAction.SCROLL_NEXT_PAGE,
                    PenButtonAction.SCROLL_PREV_PAGE,
                    PenButtonAction.DEFAULT_INTERACTION,
                    PenButtonAction.DO_NOTHING,
                    PenButtonAction.FOLLOW_LINK -> { }
                }
            }
        }
    }

    private fun setPresenter(on: Boolean) {
        val view = pdfView
        if (on && vm.document.value == null) {
            toast(R.string.no_document)
            return
        }
        if (on == presenting) {
            view?.setPresentation(on)
            return
        }
        if (on) {
            presenterZoomMode = view?.zoomMode
            presenterZoomPercent = view?.currentZoomPercent() ?: 100f
            vm.disconnectJump()
            presenting = true
            view?.setPresentation(true)
        } else {
            presenting = false
            view?.setPresentation(false)
            when (val mode = presenterZoomMode) {
                ZoomMode.FIT_WIDTH -> view?.applyFitWidth()
                ZoomMode.FIT_HEIGHT -> view?.applyFitHeight()
                ZoomMode.FIT_PAGE -> view?.applyFitPage()
                ZoomMode.CUSTOM -> view?.applyCustomZoom(presenterZoomPercent)
                null -> {}
            }
        }
    }

    override fun onStart() {
        super.onStart()
        window.decorView.setOnHoverListener { _, event ->
            pdfView?.handleStylusHover(event) == true
        }
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (pdfView?.handleStylusHover(event) == true) return true
        return super.dispatchGenericMotionEvent(event)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (pdfView?.handleAirActionKey(event) == true) return true
        return super.dispatchKeyEvent(event)
    }

    private fun followLink(uri: String, isExternal: Boolean) {
        val external = externalIntent(uri)
        if (external != null) {
            openExternal(external)
            return
        }
        if (isExternal) {
            openExternal(Intent(Intent.ACTION_VIEW, Uri.parse(uri)))
            return
        }
        lifecycleScope.launch {
            val dest = vm.resolveInternalLink(uri) ?: return@launch
            vm.disconnectJump()
            pdfView?.goToPage(dest.first, dest.second)
        }
    }

    private fun externalIntent(raw: String): Intent? {
        val text = raw.trim()
        if (text.isEmpty()) return null
        val lower = text.lowercase()
        val uri = when {
            lower.startsWith("tel:") || lower.startsWith("callto:") -> {
                val number = text.substringAfter(":").filter { it.isDigit() || it == '+' }
                if (number.isEmpty()) null else Uri.parse("tel:$number")
            }
            lower.startsWith("mailto:") ||
                lower.startsWith("http://") || lower.startsWith("https://") ||
                lower.startsWith("sms:") || lower.startsWith("smsto:") -> Uri.parse(text)
            lower.startsWith("www.") -> Uri.parse("https://$text")
            else -> null
        } ?: return null
        val action = if (uri.scheme.equals("tel", ignoreCase = true)) Intent.ACTION_DIAL else Intent.ACTION_VIEW
        return Intent(action, uri)
    }

    private fun openExternal(intent: Intent) {
        val launched = runCatching { startActivity(intent) }.isSuccess
        if (!launched) {
            runCatching { startActivity(Intent.createChooser(Intent(intent.action, intent.data), null)) }
        }
    }
}
