package com.grandsphere.papercut.ui.viewer

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewConfiguration
import android.widget.OverScroller
import com.grandsphere.papercut.data.ViewerSettings
import com.grandsphere.papercut.data.ZoomMode
import com.grandsphere.papercut.pdf.CharBox
import com.grandsphere.papercut.pdf.PageInfo
import com.grandsphere.papercut.pdf.PageLink
import com.grandsphere.papercut.pdf.SearchHit
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class PdfStripView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    interface Callbacks {
        fun requestPage(index: Int, width: Int, urgent: Boolean = false)
        fun cancelRendersExcept(keep: Set<Int>) {}
        fun loadText(index: Int, done: (List<CharBox>) -> Unit)
        fun requestLinks(index: Int)
        fun onPinchEnd(percent: Float)
        fun onSelectionText(text: String?)
        fun onVisiblePage(index: Int, count: Int)
        fun onDoubleTap()
        fun onTapZoom(mode: ZoomMode, percent: Float)
        fun onLockSideScroll(on: Boolean)
        fun onLinkTapped(uri: String, isExternal: Boolean)
        fun onPageLongPressed(index: Int) {}
        fun onPageTapped(index: Int) {}
        fun onFolioSwipe(delta: Int) {}
    }

    var callbacks: Callbacks? = null
    var onPagePainted: ((Int) -> Unit)? = null
    var folioPaging: Boolean = false
    private var boundUri: String? = null
    var pageSelectMode: Boolean = false
        private set
    private var selectedPages: Collection<Int> = emptySet()

    private var pages: List<PageInfo> = emptyList()
    private var pageTops: FloatArray = FloatArray(0)
    private var totalHeightPdf = 0f
    private var maxWidthPdf = 1f

    private var scale = 1f
    private var scrollXPdf = 0f
    private var scrollYPdf = 0f
    var zoomMode: ZoomMode = ZoomMode.FIT_WIDTH
        private set
    var customPercent: Float = 100f
        private set

    private var bgColor: Int = ViewerSettings.DEFAULT_BG
    private var highlightColor: Int = ViewerSettings.DEFAULT_HIGHLIGHT
    private var lockZoom: Boolean = false
    private var lockSideScroll: Boolean = false
    private var pageSelectLockSide: Boolean = false
    private var savedLockSideScroll: Boolean = false
    private var autoDisablePan: Boolean = false
    private var reLockOnFitWidth: Boolean = false
    private var twoFingerPan: Boolean = false
    private var pagePaddingPx: Float = 0f
    private var horizontalPagePaddingPx: Float = 0f
    private var pageBorderEnabled: Boolean = false
    private var pageBorderColor: Int = ViewerSettings.DEFAULT_PAGE_BORDER
    private var allowFollowLinks: Boolean = false
    private var allowTextSelection: Boolean = true
    private var doubleTapMenu: Boolean = true
    private var singleTapMenu: Boolean = false
    private var swipeFromBottomMenu: Boolean = false
    private var bottomMenuTracking = false
    private var bottomMenuStartX = 0f
    private var bottomMenuStartY = 0f
    private var folioDownX = 0f
    private var folioDownY = 0f
    private var folioSwiped = false
    private val touchSlopPx = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private var pendingZoom = false
    private var pendingZoomX = 0f
    private var pendingZoomY = 0f
    private var swallowUntilUp = false
    private val tapSlopPx = ViewConfiguration.get(context).scaledDoubleTapSlop.toFloat()
    private val pendingZoomRunnable = Runnable { pendingZoom = false }
    private var scrubbing: Boolean = false
    private var twoFingerGesture: Boolean = false
    private var activePointers: Int = 1
    private var lastReportedPage: Int = -1
    private var lastReportedCount: Int = -1
    private var renderKey: Long = 0L
    private var fitWidthLockHeld: Boolean = false
    private var searchHits: List<SearchHit> = emptyList()
    private var searchIndex: Int = 0
    private var pendingRestoreX: Float? = null
    private var pendingRestoreY: Float? = null
    private var pendingRestorePage: Int? = null
    private var pendingRestoreOffset: Float? = null

    private val bitmaps = LinkedHashMap<Int, CachedPage>(16, 0.75f, true)
    private val inFlight = HashSet<Int>()
    private val textCache = HashMap<Int, List<CharBox>>()
    private val linkCache = HashMap<Int, List<PageLink>>()
    private val linksInFlight = HashSet<Int>()

    private val scroller = OverScroller(context)
    private val scaleDetector = ScaleGestureDetector(context, ScaleListener())
    private val gestureDetector = GestureDetector(context, GestureListener())

    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val highlightPaint = Paint().apply {
        color = 0x66B39DDB.toInt()
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    private val searchPaint = Paint().apply {
        color = 0x55B39DDB.toInt()
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    private val searchCurrentPaint = Paint().apply {
        color = 0x99B39DDB.toInt()
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFB3B3B3.toInt()
        style = Paint.Style.FILL
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ViewerSettings.DEFAULT_PAGE_BORDER
        style = Paint.Style.STROKE
        strokeWidth = 2f * resources.displayMetrics.density
    }
    private val path = Path()
    private val destRect = RectF()

    private val handleRadius = 10f * resources.displayMetrics.density
    private val edgeSlop = 24f * resources.displayMetrics.density
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    private var selecting = false
    private var draggingHandle: Handle? = null
    private var pinching = false
    private var twoFingerTracking = false
    private var twoFingerSpan = 0f
    private var twoFingerLastMidX = 0f
    private var twoFingerLastMidY = 0f
    private var twoFingerScaleStart = 1f
    private var selection: SelectionRange? = null

    private enum class Handle { START, END }

    private data class CachedPage(val width: Int, val key: Long, val bitmap: Bitmap)

    private data class TapZoomSnapshot(
        val mode: ZoomMode,
        val percent: Float,
        val centerPdfX: Float,
        val lockSideScroll: Boolean?,
    )

    private var tapZoomSnapshot: TapZoomSnapshot? = null
    private var userZoomed = false

    private data class SelectionRange(
        val startPage: Int,
        val startIndex: Int,
        val endPage: Int,
        val endIndex: Int
    )

    fun bindFolioItem(
        uri: String,
        newPages: List<PageInfo>,
        settings: ViewerSettings,
        scrollX: Float,
        scrollY: Float,
    ) {
        if (uri.isBlank()) {
            bindDocument("", emptyList())
            return
        }
        if (boundUri == uri && pages.isNotEmpty() && newPages.isNotEmpty()) {
            pendingRestoreX = null
            pendingRestoreY = null
            return
        }
        pendingRestoreX = scrollX
        pendingRestoreY = scrollY
        setSettings(settings)
        bindDocument(uri, newPages)
        if (pages.isNotEmpty() && width > 0 && hasPendingRestore()) {
            applyPendingRestore(consume = true)
        }
    }

    fun primeVisiblePages() {
        if (pages.isEmpty() || width <= 0 || height <= 0 || scale <= 0f) return
        requestPagesAround(currentVisiblePage())
    }

    fun bindDocument(uri: String, newPages: List<PageInfo>): Boolean {
        if (uri.isBlank() || newPages.isEmpty()) {
            boundUri = null
            setDocument(emptyList())
            return true
        }
        if (boundUri == uri && pages.size == newPages.size) {
            return false
        }
        boundUri = uri
        setDocument(newPages)
        return true
    }

    fun replacePageSizes(newPages: List<PageInfo>) {
        if (newPages.isEmpty() || newPages.size != pages.size) return
        pages = newPages
        rebuildTopsPreservingScroll()
        applyPendingPageRestore()
        clampScroll()
        invalidate()
    }

    fun hasDocument(): Boolean = pages.isNotEmpty()

    fun hasFirstPagePainted(): Boolean {
        if (pages.isEmpty() || width <= 0) return false
        return hasPainted(currentVisiblePage())
    }

    private fun hasPainted(index: Int): Boolean {
        val cached = bitmaps[index] ?: return false
        return cached.key == renderKey && !cached.bitmap.isRecycled
    }

    private fun keepRange(center: Int): Set<Int> {
        if (pages.isEmpty()) return emptySet()
        val from = (center - PREFETCH_PAGES).coerceAtLeast(0)
        val to = (center + PREFETCH_PAGES).coerceAtMost(pages.lastIndex)
        return (from..to).toSet()
    }

    private fun requestPagesAround(
        center: Int,
        firstVisible: Int = center,
        lastVisible: Int = center,
        widthHint: Int = 0,
    ) {
        if (pages.isEmpty() || width <= 0) return
        val keep = keepRange(center)
        callbacks?.cancelRendersExcept(keep)
        inFlight.removeAll { it !in keep }
        val targetWidth = if (widthHint > 0) {
            widthHint
        } else {
            (maxWidthPdf * scale).roundToInt().coerceIn(1, 4096)
        }
        val order = ArrayList<Int>(keep.size)
        order.add(center.coerceIn(0, pages.lastIndex))
        var d = 1
        val from = keep.minOrNull() ?: firstVisible
        val to = keep.maxOrNull() ?: lastVisible
        while (order.size < keep.size) {
            val before = center - d
            val after = center + d
            if (before >= from) order.add(before)
            if (after <= to) order.add(after)
            d++
            if (d > PREFETCH_PAGES + 1) break
        }
        for (i in order) {
            val cached = bitmaps[i]
            val ready = cached != null && cached.key == renderKey && !cached.bitmap.isRecycled
            val sharp = ready && cached != null && abs(cached.width - targetWidth) <= 1
            if (!sharp && i !in inFlight) {
                inFlight.add(i)
                callbacks?.requestPage(i, targetWidth, urgent = i == center)
            }
        }
    }

    fun applyFitWidthForPageSelect() {
        if (!pageSelectLockSide) {
            savedLockSideScroll = lockSideScroll
            pageSelectLockSide = true
        }
        lockSideScroll = true
        if (pages.isEmpty() || width <= 0) return
        zoomMode = ZoomMode.FIT_WIDTH
        applyZoomInternal(ZoomMode.FIT_WIDTH, customPercent, keepScroll = true, centerX = true)
        scrollXPdf = centerScrollX()
        clampScroll()
        invalidate()
    }

    fun setDocument(newPages: List<PageInfo>) {
        recycleBitmaps()
        inFlight.clear()
        textCache.clear()
        linkCache.clear()
        linksInFlight.clear()
        selection = null
        searchHits = emptyList()
        searchIndex = 0
        callbacks?.onSelectionText(null)
        tapZoomSnapshot = null
        userZoomed = false
        fitWidthLockHeld = false
        pages = newPages
        lastReportedPage = -1
        lastReportedCount = -1
        rebuildTops()
        if (width > 0 && pages.isNotEmpty()) {
            val restoring = hasPendingPageRestore() || hasPendingRestore()
            applyZoomInternal(zoomMode, customPercent, keepScroll = restoring, centerX = !restoring)
            if (!applyPendingPageRestore() && hasPendingRestore()) {
                applyPendingRestore(consume = true)
            } else if (!restoring) {
                scrollXPdf = 0f
                scrollYPdf = 0f
                clampScroll()
            }
        } else if (!hasPendingPageRestore() && !hasPendingRestore()) {
            scrollXPdf = 0f
            scrollYPdf = 0f
        }
        reportVisiblePage()
        ensureFitWidthLock()
        invalidate()
    }

    fun paintKey(): Long = renderKey

    fun restorePosition(scrollX: Float, scrollY: Float, sticky: Boolean = true) {
        pendingRestoreX = scrollX
        pendingRestoreY = scrollY
        if (!sticky) {
            applyPendingRestore(consume = true)
            return
        }
        if (width > 0 && pages.isNotEmpty()) {
            applyPendingRestore(consume = false)
        }
    }

    fun currentScrollXPdf(): Float = scrollXPdf

    fun currentScrollYPdf(): Float = scrollYPdf

    fun currentOffsetOnPage(): Float {
        if (pages.isEmpty()) return 0f
        val i = currentVisiblePage().coerceIn(0, pages.lastIndex)
        val top = pageTops.getOrNull(i) ?: return 0f
        val h = pages[i].height
        return (scrollYPdf - top).coerceIn(0f, h)
    }

    fun pendingGoToPage(index: Int, offsetFromTop: Float, scrollX: Float) {
        pendingRestorePage = index
        pendingRestoreOffset = offsetFromTop
        pendingRestoreX = scrollX
    }

    fun goToPageOffset(index: Int, offsetFromTop: Float, scrollX: Float? = null) {
        pendingRestorePage = index
        pendingRestoreOffset = offsetFromTop
        if (scrollX != null) pendingRestoreX = scrollX
        if (!applyPendingPageRestore()) return
        scroller.abortAnimation()
        val keep = keepRange(index.coerceIn(0, pages.lastIndex))
        dropInFlightExcept(keep)
        callbacks?.cancelRendersExcept(keep)
        requestPagesAround(index.coerceIn(0, pages.lastIndex))
        invalidate()
    }

    private fun hasPendingPageRestore(): Boolean = pendingRestorePage != null

    private fun applyPendingPageRestore(): Boolean {
        val index = pendingRestorePage ?: return false
        if (width <= 0 || pages.isEmpty() || index !in pages.indices || pageTops.size != pages.size) {
            return false
        }
        val h = pages[index].height
        val extra = pendingRestoreOffset.let { off ->
            if (off != null && off in 0f..h) off else 0f
        }
        pendingRestoreX?.let { scrollXPdf = it }
        scrollYPdf = pageTops[index] + extra
        clampScroll()
        pendingRestorePage = null
        pendingRestoreOffset = null
        pendingRestoreX = null
        pendingRestoreY = null
        scroller.abortAnimation()
        val keep = keepRange(index)
        dropInFlightExcept(keep)
        callbacks?.cancelRendersExcept(keep)
        requestPagesAround(index)
        invalidate()
        return true
    }

    private fun hasPendingRestore(): Boolean =
        pendingRestoreX != null && pendingRestoreY != null

    private fun applyPendingRestore(consume: Boolean = true) {
        val x = pendingRestoreX ?: return
        val y = pendingRestoreY ?: return
        if (width <= 0 || pages.isEmpty()) return
        scrollXPdf = x
        scrollYPdf = y
        clampScroll()
        if (consume) {
            pendingRestoreX = null
            pendingRestoreY = null
        }
        invalidate()
    }

    fun setPageSelect(on: Boolean, selected: Collection<Int>) {
        pageSelectMode = on
        selectedPages = selected
        if (on) {
            clearSelection()
        } else if (pageSelectLockSide) {
            lockSideScroll = savedLockSideScroll
            pageSelectLockSide = false
        }
        invalidate()
    }

    fun setSettings(settings: ViewerSettings) {
        bgColor = settings.bgColor
        highlightColor = settings.highlightColor
        lockZoom = settings.lockZoom
        autoDisablePan = settings.autoDisablePan
        if (!autoDisablePan) reLockOnFitWidth = false
        if (!reLockOnFitWidth && !pageSelectLockSide) {
            lockSideScroll = settings.lockSideScroll
        }
        twoFingerPan = settings.twoFingerPan
        val density = resources.displayMetrics.density
        val nextPad = settings.pagePaddingDp * density
        val nextHoriz = settings.horizontalPagePaddingDp * density
        val paddingChanged = abs(nextPad - pagePaddingPx) > 0.1f
        val horizChanged = abs(nextHoriz - horizontalPagePaddingPx) > 0.1f
        pagePaddingPx = nextPad
        horizontalPagePaddingPx = nextHoriz
        pageBorderEnabled = settings.pageBorderEnabled
        pageBorderColor = settings.pageBorderColor
        allowFollowLinks = settings.allowFollowLinks
        allowTextSelection = settings.allowTextSelection
        doubleTapMenu = settings.doubleTapMenu
        singleTapMenu = settings.singleTapMenu
        swipeFromBottomMenu = settings.swipeFromBottomMenu
        borderPaint.color = pageBorderColor
        tintSearchPaints()
        setBackgroundColor(bgColor)
        val newKey = settings.fgColor.toLong() shl 32 xor
            (settings.bgColor.toLong() and 0xFFFFFFFFL) xor
            (settings.imageColorBlend * 1000).toLong() xor
            (settings.imageAlpha * 1000).toLong() xor
            (if (settings.originalColors) 1L else 0L)
        val colorsChanged = newKey != renderKey
        renderKey = newKey
        val mode = settings.effectiveMode()
        val percent = settings.effectivePercent()
        val zoomChanged = mode != zoomMode || (mode == ZoomMode.CUSTOM && abs(percent - customPercent) > 0.4f)
        if (zoomChanged && !userZoomed) {
            zoomMode = mode
            customPercent = percent
        }
        if (colorsChanged) {
            recycleBitmaps()
            inFlight.clear()
        }
        if (width > 0 && pages.isNotEmpty() && zoomChanged && !userZoomed) {
            applyZoomInternal(
                mode,
                percent,
                keepScroll = mode == ZoomMode.CUSTOM,
                centerX = mode != ZoomMode.CUSTOM
            )
        } else if (width > 0 && pages.isNotEmpty() && colorsChanged) {
            inFlight.clear()
        } else if (pages.isNotEmpty() && (paddingChanged || horizChanged)) {
            if (horizChanged && zoomMode == ZoomMode.FIT_WIDTH && width > 0) {
                applyZoomInternal(ZoomMode.FIT_WIDTH, customPercent, keepScroll = true, centerX = true)
            } else if (paddingChanged) {
                rebuildTopsPreservingScroll()
                clampScroll()
            }
        }
        if (!allowTextSelection && selection != null) {
            clearSelection()
        }
        ensureFitWidthLock()
        invalidate()
    }

    fun setSearchHits(hits: List<SearchHit>, index: Int) {
        searchHits = hits
        searchIndex = index.coerceAtLeast(0)
        val pagesNeeded = hits.flatMap { listOf(it.startPage, it.endPage) }.toSet()
        pagesNeeded.forEach { p -> ensureText(p) { invalidate() } }
        if (hits.isNotEmpty()) {
            val hit = hits[searchIndex.coerceIn(0, hits.lastIndex)]
            ensureText(hit.startPage) {
                scrollToChar(hit.startPage, hit.startIndex)
            }
        }
        invalidate()
    }

    fun setPageBitmap(index: Int, width: Int, bitmap: Bitmap, key: Long) {
        if (key != renderKey) {
            bitmap.recycle()
            return
        }
        inFlight.remove(index)
        val old = bitmaps.put(index, CachedPage(width, renderKey, bitmap))
        if (old != null && old.bitmap !== bitmap) old.bitmap.recycle()
        trimCache()
        invalidate()
        onPagePainted?.invoke(index)
    }

    fun goToPage(index: Int, yOnPage: Float? = null) {
        if (pages.isEmpty() || index !in pages.indices) return
        val info = pages[index]
        val extra = if (yOnPage != null) {
            val rel = yOnPage - info.y0
            if (rel in 0f..info.height) rel else 0f
        } else {
            0f
        }
        scrollYPdf = pageTops[index] + extra
        clampScroll()
        scroller.abortAnimation()
        if (scrubbing) {
            val w = (maxWidthPdf * scale).roundToInt().coerceIn(1, 4096)
            if (index !in inFlight && !hasPainted(index)) {
                inFlight.add(index)
                callbacks?.requestPage(index, w, urgent = true)
            }
        } else {
            val keep = keepRange(index)
            dropInFlightExcept(keep)
            callbacks?.cancelRendersExcept(keep)
            requestPagesAround(index)
        }
        invalidate()
    }

    fun clearInFlight() {
        inFlight.clear()
    }

    fun setScrubbing(on: Boolean) {
        if (scrubbing == on) return
        scrubbing = on
        if (!on) invalidate()
    }

    fun isScrubbing(): Boolean = scrubbing

    fun dropInFlightExcept(keep: Set<Int>) {
        inFlight.removeAll { it !in keep }
    }

    fun applyFitWidth() {
        userZoomed = true
        fitWidthLockHeld = false
        zoomMode = ZoomMode.FIT_WIDTH
        applyZoomInternal(ZoomMode.FIT_WIDTH, customPercent, keepScroll = true, centerX = true)
        scrollXPdf = centerScrollX()
        clampScroll()
        lockSideScroll = true
        callbacks?.onLockSideScroll(true)
        invalidate()
    }

    fun applyCustomZoom(percent: Float) {
        val next = percent.coerceIn(25f, 400f)
        userZoomed = true
        if (zoomMode != ZoomMode.CUSTOM || abs(currentZoomPercent() - next) > 0.4f) {
            noteUserZoomTo(ZoomMode.CUSTOM)
        }
        zoomMode = ZoomMode.CUSTOM
        customPercent = next
        applyScale(scaleForPercent(next))
    }

    fun clearReLockOnFitWidth() {
        reLockOnFitWidth = false
        fitWidthLockHeld = true
    }

    private fun ensureFitWidthLock() {
        if (zoomMode != ZoomMode.FIT_WIDTH || fitWidthLockHeld || pageSelectLockSide) return
        if (lockSideScroll) return
        lockSideScroll = true
        callbacks?.onLockSideScroll(true)
    }

    private fun noteUserZoomTo(nextMode: ZoomMode) {
        if (!autoDisablePan) return
        if (lockSideScroll) {
            if (nextMode != ZoomMode.FIT_WIDTH) {
                reLockOnFitWidth = true
                lockSideScroll = false
                callbacks?.onLockSideScroll(false)
            }
        } else if (nextMode == ZoomMode.FIT_WIDTH && reLockOnFitWidth) {
            reLockOnFitWidth = false
            lockSideScroll = true
            callbacks?.onLockSideScroll(true)
        }
    }

    private fun screenCenterPdfX(): Float =
        scrollXPdf + if (scale <= 0f) 0f else (width / 2f) / scale

    private fun screenCenterPdfY(): Float =
        scrollYPdf + if (scale <= 0f) 0f else (height / 2f) / scale

    private fun scaleForPercent(percent: Float): Float {
        val dpi = resources.displayMetrics.densityDpi.toFloat()
        return ((dpi / 72f) * (percent / 100f)).coerceIn(minScale(), maxScale())
    }

    private fun applyScale(
        nextScale: Float,
        focusX: Float? = null,
        focusY: Float? = null,
        fromX: Float? = null,
        fromY: Float? = null,
    ) {
        if (pages.isEmpty() || width <= 0 || scale <= 0f) return
        if (autoDisablePan && abs(nextScale - scale) > 0.001f) {
            noteUserZoomTo(ZoomMode.CUSTOM)
        }
        val prev = scale
        val toX = focusX ?: (width / 2f)
        val toY = focusY ?: (height / 2f)
        val originX = fromX ?: toX
        val originY = fromY ?: toY
        val pinX = if (lockSideScroll) originX else toX
        val pdfX = originX / prev + scrollXPdf
        val vis = pageAt(originY) ?: currentVisiblePage()
        val offsetInPage = if (vis in pageTops.indices) {
            (originY / prev + scrollYPdf) - pageTops[vis]
        } else {
            0f
        }
        scale = nextScale.coerceIn(minScale(), maxScale())
        rebuildTops()
        scrollXPdf = pdfX - pinX / scale
        scrollYPdf = if (vis in pageTops.indices) {
            pageTops[vis] + offsetInPage - toY / scale
        } else {
            0f
        }
        clampScroll()
        inFlight.clear()
        invalidate()
    }

    private fun captureTapZoomSnapshot(): TapZoomSnapshot = TapZoomSnapshot(
        mode = zoomMode,
        percent = currentZoomPercent(),
        centerPdfX = screenCenterPdfX(),
        lockSideScroll = if (autoDisablePan) lockSideScroll else null,
    )

    private fun applyTapZoomSnapshot(snap: TapZoomSnapshot) {
        val keepPdfY = screenCenterPdfY()
        zoomMode = snap.mode
        if (snap.mode == ZoomMode.CUSTOM) {
            customPercent = snap.percent.coerceIn(25f, 400f)
        }
        val nextScale = when (snap.mode) {
            ZoomMode.FIT_WIDTH -> fitWidthUsable() / fitWidthPageWidth()
            ZoomMode.FIT_PAGE -> {
                val page = pages.getOrNull(currentVisiblePage()) ?: pages[0]
                min(width / page.width, height / max(page.height, 1f))
            }
            ZoomMode.CUSTOM -> scaleForPercent(snap.percent)
        }
        applyScale(nextScale.coerceIn(minScale(), maxScale()), width / 2f, height / 2f)
        if (scale > 0f) {
            scrollXPdf = snap.centerPdfX - (width / 2f) / scale
            scrollYPdf = keepPdfY - (height / 2f) / scale
        }
        if (autoDisablePan && snap.lockSideScroll != null) {
            lockSideScroll = snap.lockSideScroll
            reLockOnFitWidth = false
            callbacks?.onLockSideScroll(snap.lockSideScroll)
        }
        clampScroll()
        inFlight.clear()
        tapZoomSnapshot = null
        invalidate()
    }

    private fun finishPinchZoom() {
        if (lockZoom) return
        zoomMode = ZoomMode.CUSTOM
        customPercent = currentZoomPercent().coerceIn(25f, 400f)
        noteUserZoomTo(ZoomMode.CUSTOM)
        callbacks?.onPinchEnd(customPercent)
    }

    private fun cancelPendingZoom() {
        pendingZoom = false
        removeCallbacks(pendingZoomRunnable)
    }

    private fun toggleTapZoom(focusX: Float, focusY: Float) {
        if (pages.isEmpty() || width <= 0 || lockZoom) return
        userZoomed = true
        if (abs(currentZoomPercent() - 100f) < 1f) {
            val snap = tapZoomSnapshot
            tapZoomSnapshot = null
            if (snap == null) {
                applyFitWidth()
                callbacks?.onTapZoom(ZoomMode.FIT_WIDTH, currentZoomPercent())
            } else {
                applyTapZoomSnapshot(snap)
                callbacks?.onTapZoom(snap.mode, snap.percent)
            }
        } else {
            tapZoomSnapshot = captureTapZoomSnapshot()
            noteUserZoomTo(ZoomMode.CUSTOM)
            zoomToPercentAt(100f, focusX, focusY)
            callbacks?.onTapZoom(ZoomMode.CUSTOM, 100f)
        }
    }

    private fun zoomToPercentAt(percent: Float, focusX: Float, focusY: Float) {
        zoomMode = ZoomMode.CUSTOM
        customPercent = percent.coerceIn(25f, 400f)
        applyScale(scaleForPercent(customPercent), focusX, focusY)
    }

    fun setPageLinks(index: Int, links: List<PageLink>) {
        linksInFlight.remove(index)
        linkCache[index] = links
    }

    fun applyFitPage() {
        if (pages.isEmpty() || width <= 0) return
        zoomMode = ZoomMode.FIT_PAGE
        applyZoomInternal(ZoomMode.FIT_PAGE, customPercent, keepScroll = true, centerX = true)
        invalidate()
    }

    fun applyResetZoom(settings: ViewerSettings) {
        val mode = settings.effectiveMode()
        val percent = settings.effectivePercent()
        zoomMode = mode
        customPercent = percent
        applyZoomInternal(mode, percent, keepScroll = true, centerX = true)
        invalidate()
    }

    fun pageCount(): Int = pages.size

    fun currentPageIndex(): Int = currentVisiblePage()

    fun currentZoomPercent(): Float {
        val dpi = resources.displayMetrics.densityDpi.toFloat()
        val oneHundred = dpi / 72f
        return if (oneHundred <= 0f) 100f else (scale / oneHundred) * 100f
    }

    private fun allowSidePan(pointerCount: Int): Boolean {
        if (folioPaging && pointerCount < 2 && !twoFingerGesture) return false
        if (lockSideScroll) return false
        if (!twoFingerPan) return true
        return pointerCount >= 2 || twoFingerGesture
    }

    private fun rebuildTops() {
        pageTops = FloatArray(pages.size)
        var y = 0f
        var maxW = 1f
        val gap = if (scale > 0f) pagePaddingPx / scale else 0f
        for (i in pages.indices) {
            pageTops[i] = y
            y += pages[i].height
            if (i < pages.lastIndex) y += gap
            maxW = max(maxW, pages[i].width)
        }
        totalHeightPdf = y
        maxWidthPdf = maxW
    }

    private fun rebuildTopsPreservingScroll() {
        val vis = currentVisiblePage()
        val offset = if (vis in pages.indices && vis in pageTops.indices) {
            scrollYPdf - pageTops[vis]
        } else {
            0f
        }
        rebuildTops()
        if (vis in pageTops.indices) {
            scrollYPdf = pageTops[vis] + offset
        }
    }

    private fun applyZoomInternal(
        mode: ZoomMode,
        percent: Float,
        keepScroll: Boolean,
        centerX: Boolean
    ) {
        if (width <= 0 || pages.isEmpty()) return
        val vis = currentVisiblePage()
        val focusY = scrollYPdf + (if (scale == 0f) 0f else (height / 2f) / scale)
        val focusX = scrollXPdf + (if (scale == 0f) 0f else (width / 2f) / scale)
        val offsetInPage = if (vis in pages.indices && vis in pageTops.indices) {
            focusY - pageTops[vis]
        } else {
            0f
        }
        val dpi = resources.displayMetrics.densityDpi.toFloat()
        val fitPage = pages.getOrNull(vis) ?: pages[0]
        val next = when (mode) {
            ZoomMode.FIT_WIDTH -> fitWidthUsable() / fitWidthPageWidth()
            ZoomMode.FIT_PAGE -> min(width / fitPage.width, height / max(fitPage.height, 1f))
            ZoomMode.CUSTOM -> (dpi / 72f) * (percent / 100f)
        }.coerceIn(minScale(), maxScale())
        scale = next
        rebuildTops()
        val restoring = hasPendingRestore()
        if (keepScroll) {
            scrollXPdf = focusX - (width / 2f) / scale
            scrollYPdf = if (vis in pageTops.indices) {
                pageTops[vis] + offsetInPage - (height / 2f) / scale
            } else {
                focusY - (height / 2f) / scale
            }
        } else if (!restoring) {
            scrollYPdf = 0f
        }
        if (centerX && !restoring) {
            scrollXPdf = centerScrollX()
        }
        clampScroll()
        inFlight.clear()
        applyPendingRestore(consume = true)
    }

    private fun fitWidthPageWidth(): Float {
        if (pages.isEmpty()) return maxWidthPdf
        val w = pages.getOrNull(currentVisiblePage())?.width ?: maxWidthPdf
        return w.coerceAtLeast(1f)
    }

    private fun fitWidthUsable(): Float =
        (width - 2f * horizontalPagePaddingPx).coerceAtLeast(1f)

    private fun minScale(): Float {
        if (width <= 0 || pages.isEmpty()) return 0.1f
        return min(fitWidthUsable() / maxWidthPdf, height / max(pages[0].height, 1f)) * 0.5f
    }

    private fun maxScale(): Float {
        val dpi = resources.displayMetrics.densityDpi.toFloat()
        return max(8f * (dpi / 72f), (width / maxWidthPdf) * 8f)
    }

    private fun tintSearchPaints() {
        val c = highlightColor
        searchPaint.color = (c and 0x00FFFFFF) or 0x55000000.toInt()
        searchCurrentPaint.color = (c and 0x00FFFFFF) or 0x99000000.toInt()
        highlightPaint.color = (c and 0x00FFFFFF) or 0x66000000.toInt()
    }

    private fun scrollToChar(page: Int, index: Int) {
        if (page !in pages.indices) return
        val chars = textCache[page]
        val pageY = if (chars != null && index in chars.indices) {
            pageTops[page] + (chars[index].uly - pages[page].y0) - (height / scale) * 0.2f
        } else {
            pageTops[page]
        }
        scrollYPdf = pageY
        clampScroll()
        scroller.abortAnimation()
        invalidate()
    }

    private fun clampScroll() {
        if (pages.isEmpty() || scale <= 0f) return
        val contentW = maxWidthPdf * scale
        val minX = -(width / 2f) / scale
        val maxX = (contentW - width / 2f) / scale
        val minY = -(height / 2f) / scale
        val maxY = totalHeightPdf - (height / 2f) / scale
        scrollXPdf = scrollXPdf.coerceIn(min(minX, maxX), max(minX, maxX))
        scrollYPdf = scrollYPdf.coerceIn(min(minY, maxY), max(minY, maxY))
    }

    private fun centerScrollX(): Float {
        if (scale <= 0f) return 0f
        return (maxWidthPdf * scale - width) / (2f * scale)
    }

    private fun currentVisiblePage(): Int {
        if (pages.isEmpty()) return 0
        val y = scrollYPdf + (if (scale <= 0f) 0f else (height / 2f) / scale)
        return firstVisiblePage(y).coerceIn(0, pages.lastIndex)
    }

    private fun reportVisiblePage() {
        val count = pages.size
        val index = if (count == 0) 0 else currentVisiblePage()
        if (index != lastReportedPage || count != lastReportedCount) {
            lastReportedPage = index
            lastReportedCount = count
            callbacks?.onVisiblePage(index, count)
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || pages.isEmpty()) return
        val restoring = hasPendingPageRestore() || hasPendingRestore()
        if (oldw > 0 && w != oldw && scale > 0f && !restoring) {
            val oldUsable = (oldw - 2f * horizontalPagePaddingPx).coerceAtLeast(1f)
            val newUsable = (w - 2f * horizontalPagePaddingPx).coerceAtLeast(1f)
            scale = (scale * newUsable / oldUsable).coerceIn(minScale(), maxScale())
            if (zoomMode == ZoomMode.CUSTOM) {
                customPercent = currentZoomPercent()
                userZoomed = true
            }
            clampScroll()
            inFlight.clear()
            invalidate()
            return
        }
        if (oldw <= 0 || restoring) {
            applyZoomInternal(
                zoomMode,
                customPercent,
                keepScroll = oldw > 0 || restoring,
                centerX = oldw <= 0 && !restoring,
            )
            if (!applyPendingPageRestore()) {
                applyPendingRestore(consume = true)
            }
            invalidate()
        } else if (h != oldh) {
            clampScroll()
            invalidate()
        }
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            scrollXPdf = scroller.currX / scale
            scrollYPdf = scroller.currY / scale
            clampScroll()
            postInvalidateOnAnimation()
        }
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(bgColor)
        if (pages.isEmpty() || width == 0) {
            reportVisiblePage()
            return
        }

        val topPdf = scrollYPdf
        val botPdf = scrollYPdf + height / scale
        val first = firstVisiblePage(topPdf)
        val last = lastVisiblePage(botPdf)
        val targetWidth = (maxWidthPdf * scale).roundToInt().coerceIn(1, 4096)
        val active = currentVisiblePage()

        for (i in first..last) {
            val info = pages[i]
            val left = -scrollXPdf * scale
            val top = (pageTops[i] - scrollYPdf) * scale
            destRect.set(left, top, left + info.width * scale, top + info.height * scale)
            val cached = bitmaps[i]
            val drawable = cached != null && cached.key == renderKey && !cached.bitmap.isRecycled
            if (drawable && cached != null) {
                canvas.drawBitmap(cached.bitmap, null, destRect, bitmapPaint)
            }
            val showFocusBorder = i == active && (pageSelectMode || (!pageSelectMode && pageBorderEnabled))
            if (showFocusBorder) {
                canvas.drawRect(destRect, borderPaint)
            }
            if (pageSelectMode && i in selectedPages) {
                canvas.drawRect(destRect, highlightPaint)
            }
            drawSearchOnPage(canvas, i)
            drawSelectionOnPage(canvas, i)
        }
        drawHandles(canvas)
        if (!scrubbing) {
            requestPagesAround(active, first, last, targetWidth)
            val keep = keepRange(active)
            val keepFrom = keep.minOrNull() ?: first
            val keepTo = keep.maxOrNull() ?: last
            trimOffscreen(keepFrom, keepTo)
            if (hasPainted(active) && active !in linkCache && active !in linksInFlight) {
                linksInFlight.add(active)
                callbacks?.requestLinks(active)
            }
        }
        reportVisiblePage()
    }

    private fun firstVisiblePage(topPdf: Float): Int {
        if (pages.isEmpty()) return 0
        var lo = 0
        var hi = pages.lastIndex
        var ans = 0
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val bottom = pageTops[mid] + pages[mid].height
            if (bottom < topPdf) {
                lo = mid + 1
            } else {
                ans = mid
                hi = mid - 1
            }
        }
        return ans
    }

    private fun lastVisiblePage(botPdf: Float): Int {
        var i = firstVisiblePage(botPdf)
        while (i < pages.lastIndex && pageTops[i] < botPdf) i++
        return i.coerceIn(0, pages.lastIndex)
    }

    private fun drawSearchOnPage(canvas: Canvas, page: Int) {
        if (searchHits.isEmpty()) return
        val chars = textCache[page] ?: return
        searchHits.forEachIndexed { i, hit ->
            val paint = if (i == searchIndex) searchCurrentPaint else searchPaint
            val range = selectedIndices(
                page,
                SelectionRange(hit.startPage, hit.startIndex, hit.endPage, hit.endIndex)
            ) ?: return@forEachIndexed
            for (idx in range) {
                val c = chars[idx]
                path.reset()
                path.moveTo(pageToViewX(page, c.ulx), pageToViewY(page, c.uly))
                path.lineTo(pageToViewX(page, c.urx), pageToViewY(page, c.ury))
                path.lineTo(pageToViewX(page, c.lrx), pageToViewY(page, c.lry))
                path.lineTo(pageToViewX(page, c.llx), pageToViewY(page, c.lly))
                path.close()
                canvas.drawPath(path, paint)
            }
        }
    }

    private fun drawSelectionOnPage(canvas: Canvas, page: Int) {
        val sel = selection ?: return
        val chars = textCache[page] ?: return
        val range = selectedIndices(page, sel) ?: return
        for (idx in range) {
            val c = chars[idx]
            path.reset()
            path.moveTo(pageToViewX(page, c.ulx), pageToViewY(page, c.uly))
            path.lineTo(pageToViewX(page, c.urx), pageToViewY(page, c.ury))
            path.lineTo(pageToViewX(page, c.lrx), pageToViewY(page, c.lry))
            path.lineTo(pageToViewX(page, c.llx), pageToViewY(page, c.lly))
            path.close()
            canvas.drawPath(path, highlightPaint)
        }
    }

    private fun drawHandles(canvas: Canvas) {
        val start = startHandlePoint() ?: return
        val end = endHandlePoint() ?: return
        canvas.drawCircle(start.x, start.y, handleRadius, handlePaint)
        canvas.drawCircle(end.x, end.y, handleRadius, handlePaint)
    }

    private fun startHandlePoint(): PointF? {
        val sel = selection ?: return null
        val chars = textCache[sel.startPage] ?: return null
        if (sel.startIndex !in chars.indices) return null
        val c = chars[sel.startIndex]
        return PointF(pageToViewX(sel.startPage, c.llx), pageToViewY(sel.startPage, c.lly))
    }

    private fun endHandlePoint(): PointF? {
        val sel = selection ?: return null
        val chars = textCache[sel.endPage] ?: return null
        if (sel.endIndex !in chars.indices) return null
        val c = chars[sel.endIndex]
        return PointF(pageToViewX(sel.endPage, c.lrx), pageToViewY(sel.endPage, c.lry))
    }

    private fun selectedIndices(page: Int, sel: SelectionRange): IntRange? {
        val chars = textCache[page] ?: return null
        if (chars.isEmpty()) return null
        val startPage = min(sel.startPage, sel.endPage)
        val endPage = max(sel.startPage, sel.endPage)
        if (page !in startPage..endPage) return null
        val forward = sel.startPage < sel.endPage ||
            (sel.startPage == sel.endPage && sel.startIndex <= sel.endIndex)
        val (sp, si, ep, ei) = if (forward) {
            QuadIndex(sel.startPage, sel.startIndex, sel.endPage, sel.endIndex)
        } else {
            QuadIndex(sel.endPage, sel.endIndex, sel.startPage, sel.startIndex)
        }
        val from = if (page == sp) si else 0
        val to = if (page == ep) ei else chars.lastIndex
        if (from > to) return null
        return from..to
    }

    private data class QuadIndex(val sp: Int, val si: Int, val ep: Int, val ei: Int)

    private fun pageToViewX(page: Int, x: Float): Float =
        ((x - pages[page].x0) - scrollXPdf) * scale

    private fun pageToViewY(page: Int, y: Float): Float =
        ((pageTops[page] + (y - pages[page].y0)) - scrollYPdf) * scale

    private fun viewToPageX(page: Int, x: Float): Float =
        x / scale + scrollXPdf + pages[page].x0

    private fun viewToPageY(page: Int, y: Float): Float =
        y / scale + scrollYPdf - pageTops[page] + pages[page].y0

    private fun pageAt(y: Float): Int? {
        if (pages.isEmpty()) return null
        val pdfY = y / scale + scrollYPdf
        val idx = firstVisiblePage(pdfY)
        return idx.coerceIn(0, pages.lastIndex)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (swallowUntilUp) {
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                swallowUntilUp = false
                if (event.actionMasked == MotionEvent.ACTION_CANCEL) cancelPendingZoom()
            }
            return true
        }
        if (pageSelectMode) {
            if (event.actionMasked == MotionEvent.ACTION_DOWN && event.x > edgeSlop) {
                parent.requestDisallowInterceptTouchEvent(true)
            }
            gestureDetector.onTouchEvent(event)
            return true
        }
        if (event.actionMasked == MotionEvent.ACTION_DOWN && event.pointerCount == 1 && pendingZoom && doubleTapMenu) {
            val dx = event.x - pendingZoomX
            val dy = event.y - pendingZoomY
            val inSlop = dx * dx + dy * dy <= tapSlopPx * tapSlopPx
            if (inSlop && !lockZoom && !selecting && draggingHandle == null && !pinching) {
                cancelPendingZoom()
                toggleTapZoom(event.x, event.y)
                swallowUntilUp = true
                return true
            }
            if (!inSlop) {
                cancelPendingZoom()
            }
        }
        if (handleBottomMenuSwipe(event)) {
            return true
        }
        if (event.actionMasked == MotionEvent.ACTION_DOWN && event.x > edgeSlop && !folioPaging) {
            parent.requestDisallowInterceptTouchEvent(true)
        }
        if (folioPaging) {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    folioDownX = event.x
                    folioDownY = event.y
                    folioSwiped = false
                    if (event.x > edgeSlop) {
                        parent.requestDisallowInterceptTouchEvent(true)
                    }
                }
                MotionEvent.ACTION_MOVE -> if (event.pointerCount == 1 && !folioSwiped) {
                    val dx = event.x - folioDownX
                    val dy = event.y - folioDownY
                    if (abs(dx) > touchSlopPx && abs(dx) > abs(dy)) {
                        folioSwiped = true
                        parent.requestDisallowInterceptTouchEvent(true)
                        callbacks?.onFolioSwipe(if (dx < 0f) 1 else -1)
                        swallowUntilUp = true
                    } else if (abs(dy) > touchSlopPx) {
                        parent.requestDisallowInterceptTouchEvent(true)
                    }
                }
                MotionEvent.ACTION_POINTER_DOWN -> {
                    parent.requestDisallowInterceptTouchEvent(true)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    folioSwiped = false
                }
            }
        }
        val remaining = if (
            event.actionMasked == MotionEvent.ACTION_POINTER_UP ||
            event.actionMasked == MotionEvent.ACTION_UP
        ) {
            (event.pointerCount - 1).coerceAtLeast(0)
        } else {
            event.pointerCount
        }
        activePointers = remaining
        if (event.pointerCount >= 2) twoFingerGesture = true
        if (handleTwoFingerZoom(event)) {
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                selecting = false
                draggingHandle = null
                twoFingerGesture = false
            }
            return true
        }
        if (event.actionMasked == MotionEvent.ACTION_POINTER_DOWN) {
            selecting = false
            draggingHandle = null
            cancelPendingZoom()
        }
        val scaleHandled = scaleDetector.onTouchEvent(event)
        val gestureHandled = gestureDetector.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            if (event.actionMasked == MotionEvent.ACTION_CANCEL) cancelPendingZoom()
            endTwoFingerZoom()
            selecting = false
            draggingHandle = null
            twoFingerGesture = false
        }
        return scaleHandled || gestureHandled || true
    }

    private fun handleBottomMenuSwipe(event: MotionEvent): Boolean {
        if (pageSelectMode || !swipeFromBottomMenu) {
            bottomMenuTracking = false
            return false
        }
        if (event.pointerCount >= 2) {
            bottomMenuTracking = false
            return false
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val fromBottom = height > 0 && event.y >= height * 0.9f
                bottomMenuTracking = fromBottom &&
                    !selecting &&
                    draggingHandle == null &&
                    !pinching &&
                    !twoFingerTracking
                if (bottomMenuTracking) {
                    bottomMenuStartX = event.x
                    bottomMenuStartY = event.y
                    parent.requestDisallowInterceptTouchEvent(true)
                }
                return false
            }
            MotionEvent.ACTION_MOVE -> {
                if (!bottomMenuTracking) return false
                val dx = event.x - bottomMenuStartX
                val dy = bottomMenuStartY - event.y
                if (dx * dx + dy * dy < touchSlopPx * touchSlopPx) {
                    parent.requestDisallowInterceptTouchEvent(true)
                    return true
                }
                if (dy > abs(dx)) {
                    bottomMenuTracking = false
                    swallowUntilUp = true
                    callbacks?.onDoubleTap()
                    return true
                }
                bottomMenuTracking = false
                return false
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                bottomMenuTracking = false
                return false
            }
            else -> return false
        }
    }

    private fun handleTwoFingerZoom(event: MotionEvent): Boolean {
        if (pageSelectMode || lockZoom || pages.isEmpty()) return false
        val count = event.pointerCount
        val action = event.actionMasked
        if (action == MotionEvent.ACTION_POINTER_DOWN && count >= 2) {
            twoFingerTracking = true
            pinching = true
            twoFingerSpan = fingerSpan(event)
            twoFingerLastMidX = twoFingerMidX(event)
            twoFingerLastMidY = twoFingerMidY(event)
            twoFingerScaleStart = scale
            selecting = false
            draggingHandle = null
            cancelPendingZoom()
            scroller.abortAnimation()
            return true
        }
        if (twoFingerTracking && count >= 2 && action == MotionEvent.ACTION_MOVE) {
            val span = fingerSpan(event)
            val midX = twoFingerMidX(event)
            val midY = twoFingerMidY(event)
            if (twoFingerSpan > 0f) {
                applyScale(
                    scale * (span / twoFingerSpan),
                    midX,
                    midY,
                    twoFingerLastMidX,
                    twoFingerLastMidY,
                )
            }
            twoFingerSpan = span
            twoFingerLastMidX = midX
            twoFingerLastMidY = midY
            return true
        }
        if (twoFingerTracking && (
                action == MotionEvent.ACTION_POINTER_UP ||
                    action == MotionEvent.ACTION_UP ||
                    action == MotionEvent.ACTION_CANCEL
            )
        ) {
            val left = if (action == MotionEvent.ACTION_POINTER_UP || action == MotionEvent.ACTION_UP) {
                (count - 1).coerceAtLeast(0)
            } else {
                0
            }
            if (left < 2) {
                endTwoFingerZoom()
                return true
            }
            twoFingerSpan = fingerSpan(event)
            twoFingerLastMidX = twoFingerMidX(event)
            twoFingerLastMidY = twoFingerMidY(event)
            return true
        }
        return false
    }

    private fun endTwoFingerZoom() {
        if (!twoFingerTracking && !pinching) return
        val scaled = abs(scale - twoFingerScaleStart) > 0.001f
        twoFingerTracking = false
        twoFingerSpan = 0f
        twoFingerLastMidX = 0f
        twoFingerLastMidY = 0f
        pinching = false
        if (scaled) {
            userZoomed = true
            finishPinchZoom()
        }
    }

    private fun twoFingerMidX(event: MotionEvent): Float =
        (event.getX(0) + event.getX(1)) / 2f

    private fun twoFingerMidY(event: MotionEvent): Float =
        (event.getY(0) + event.getY(1)) / 2f

    private fun fingerSpan(event: MotionEvent): Float {
        if (event.pointerCount < 2) return 1f
        val dx = event.getX(1) - event.getX(0)
        val dy = event.getY(1) - event.getY(0)
        return hypot(dx, dy).coerceAtLeast(1f)
    }

    private inner class ScaleListener : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
            return false
        }

        override fun onScale(detector: ScaleGestureDetector): Boolean {
            return false
        }
    }

    private inner class GestureListener : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean {
            if (!scroller.isFinished) scroller.abortAnimation()
            draggingHandle = if (allowTextSelection) hitHandle(e.x, e.y) else null
            return true
        }

        override fun onScroll(
            e1: MotionEvent?,
            e2: MotionEvent,
            distanceX: Float,
            distanceY: Float
        ): Boolean {
            if (pinching || twoFingerTracking || scaleDetector.isInProgress || bottomMenuTracking) return false
            cancelPendingZoom()
            if (allowTextSelection && (selecting || draggingHandle != null)) {
                extendSelection(e2.x, e2.y)
                return true
            }
            if (allowSidePan(e2.pointerCount)) scrollXPdf += distanceX / scale
            scrollYPdf += distanceY / scale
            clampScroll()
            invalidate()
            return true
        }

        override fun onFling(
            e1: MotionEvent?,
            e2: MotionEvent,
            velocityX: Float,
            velocityY: Float
        ): Boolean {
            if (selecting || draggingHandle != null || pinching || twoFingerTracking || bottomMenuTracking || pages.isEmpty()) return false
            val minXPx = (-width / 2f).toInt()
            val maxXPx = (maxWidthPdf * scale - width / 2f).toInt()
            val loX = min(minXPx, maxXPx)
            val hiX = max(minXPx, maxXPx)
            val minYPx = (-height / 2f).toInt()
            val maxYPx = (totalHeightPdf * scale - height / 2f).toInt()
            val loY = min(minYPx, maxYPx)
            val hiY = max(minYPx, maxYPx)
            scroller.fling(
                (scrollXPdf * scale).toInt(),
                (scrollYPdf * scale).toInt(),
                if (allowSidePan(if (twoFingerGesture) 2 else 1)) -velocityX.toInt() else 0,
                -velocityY.toInt(),
                loX,
                hiX,
                loY,
                hiY
            )
            postInvalidateOnAnimation()
            return true
        }

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            return handleSingleTap(e)
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (pageSelectMode) return true
            if (selecting || draggingHandle != null || pinching || twoFingerTracking || pages.isEmpty()) return false
            if (!doubleTapMenu) {
                toggleTapZoom(e.x, e.y)
                return true
            }
            callbacks?.onDoubleTap()
            cancelPendingZoom()
            pendingZoom = true
            pendingZoomX = e.x
            pendingZoomY = e.y
            postDelayed(pendingZoomRunnable, ViewConfiguration.getDoubleTapTimeout().toLong())
            return true
        }

        override fun onSingleTapUp(e: MotionEvent): Boolean = false

        private fun handleSingleTap(e: MotionEvent): Boolean {
            if (pageSelectMode) {
                val page = pageAt(e.y) ?: return true
                callbacks?.onPageTapped(page)
                return true
            }
            if (selection != null && draggingHandle == null) {
                clearSelection()
                return true
            }
            if (allowFollowLinks) {
                val page = pageAt(e.y) ?: return openMenuIfSingleTap()
                val pdfX = viewToPageX(page, e.x)
                val pdfY = viewToPageY(page, e.y)
                val hit = linkCache[page]?.firstOrNull { link ->
                    val b = link.bounds
                    pdfX >= b.left - 8f && pdfX <= b.right + 8f &&
                        pdfY >= b.top - 8f && pdfY <= b.bottom + 8f
                }
                if (hit != null) {
                    callbacks?.onLinkTapped(hit.uri, hit.isExternal)
                    return true
                }
            }
            return openMenuIfSingleTap()
        }

        private fun openMenuIfSingleTap(): Boolean {
            if (!singleTapMenu || selecting || draggingHandle != null || pinching) return false
            callbacks?.onDoubleTap()
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            if (pageSelectMode) return
            if (!allowTextSelection || pages.isEmpty()) return
            selecting = true
            startWordAt(e.x, e.y)
        }
    }

    private fun hitHandle(x: Float, y: Float): Handle? {
        val start = startHandlePoint() ?: return null
        val end = endHandlePoint() ?: return null
        val r = handleRadius * 1.6f
        val ds = PointF(x - start.x, y - start.y)
        val de = PointF(x - end.x, y - end.y)
        val startHit = ds.x * ds.x + ds.y * ds.y <= r * r
        val endHit = de.x * de.x + de.y * de.y <= r * r
        if (startHit && endHit) {
            return if (ds.x * ds.x + ds.y * ds.y <= de.x * de.x + de.y * de.y) Handle.START else Handle.END
        }
        if (startHit) return Handle.START
        if (endHit) return Handle.END
        return null
    }

    private fun startWordAt(x: Float, y: Float) {
        val page = pageAt(y) ?: return
        ensureText(page) { chars ->
            if (chars.isEmpty()) return@ensureText
            val pdfX = viewToPageX(page, x)
            val pdfY = viewToPageY(page, y)
            val idx = chars.indexOfFirst { it.contains(pdfX, pdfY) }
            if (idx < 0) return@ensureText
            var start = idx
            var end = idx
            while (start > 0 && !chars[start - 1].c.isWhitespace()) start--
            while (end < chars.lastIndex && !chars[end + 1].c.isWhitespace()) end++
            selection = SelectionRange(page, start, page, end)
            emitSelectionText()
            invalidate()
        }
    }

    private fun extendSelection(x: Float, y: Float) {
        val page = pageAt(y) ?: return
        ensureText(page) { chars ->
            if (chars.isEmpty()) return@ensureText
            val pdfX = viewToPageX(page, x)
            val pdfY = viewToPageY(page, y)
            val idx = nearestChar(chars, pdfX, pdfY) ?: return@ensureText
            val current = selection ?: SelectionRange(page, idx, page, idx)
            selection = when (draggingHandle) {
                Handle.START -> current.copy(startPage = page, startIndex = idx)
                Handle.END -> current.copy(endPage = page, endIndex = idx)
                null -> current.copy(endPage = page, endIndex = idx)
            }
            emitSelectionText()
            invalidate()
        }
    }

    private fun nearestChar(chars: List<CharBox>, x: Float, y: Float): Int? {
        val hit = chars.indexOfFirst { it.contains(x, y) }
        if (hit >= 0) return hit
        var best = -1
        var bestD = Float.MAX_VALUE
        chars.forEachIndexed { i, c ->
            val cx = (c.ulx + c.urx + c.llx + c.lrx) * 0.25f
            val cy = (c.uly + c.ury + c.lly + c.lry) * 0.25f
            val d = (cx - x) * (cx - x) + (cy - y) * (cy - y)
            if (d < bestD) {
                bestD = d
                best = i
            }
        }
        return best.takeIf { it >= 0 }
    }

    private fun ensureText(page: Int, done: (List<CharBox>) -> Unit) {
        val cached = textCache[page]
        if (cached != null) {
            done(cached)
            return
        }
        callbacks?.loadText(page) { chars ->
            textCache[page] = chars
            done(chars)
        }
    }

    private fun emitSelectionText() {
        val sel = selection
        if (sel == null) {
            callbacks?.onSelectionText(null)
            return
        }
        val forward = sel.startPage < sel.endPage ||
            (sel.startPage == sel.endPage && sel.startIndex <= sel.endIndex)
        val sp = if (forward) sel.startPage else sel.endPage
        val si = if (forward) sel.startIndex else sel.endIndex
        val ep = if (forward) sel.endPage else sel.startPage
        val ei = if (forward) sel.endIndex else sel.startIndex
        val builder = StringBuilder()
        for (p in sp..ep) {
            val chars = textCache[p] ?: continue
            if (builder.isNotEmpty()) builder.append('\n')
            val from = if (p == sp) si else 0
            val to = if (p == ep) ei else chars.lastIndex
            if (from in chars.indices && to in chars.indices && from <= to) {
                for (i in from..to) builder.append(chars[i].c)
            }
        }
        val text = builder.toString()
        callbacks?.onSelectionText(text.ifBlank { null })
    }

    fun clearSelection() {
        selection = null
        selecting = false
        draggingHandle = null
        callbacks?.onSelectionText(null)
        invalidate()
    }

    private fun recycleBitmaps() {
        bitmaps.values.forEach { if (!it.bitmap.isRecycled) it.bitmap.recycle() }
        bitmaps.clear()
    }

    private fun trimCache() {
        val keep = if (pages.isEmpty()) emptySet() else keepRange(currentVisiblePage())
        while (bitmaps.size > MAX_CACHED_PAGES || cacheBytes() > MAX_CACHE_BYTES) {
            val eldest = bitmaps.entries.firstOrNull { it.key !in keep } ?: break
            if (!eldest.value.bitmap.isRecycled) eldest.value.bitmap.recycle()
            bitmaps.remove(eldest.key)
        }
    }

    private fun cacheBytes(): Long {
        var total = 0L
        bitmaps.values.forEach { total += it.bitmap.byteCount.toLong() }
        return total
    }

    private fun trimOffscreen(first: Int, last: Int) {
        val keepFrom = (first - PREFETCH_PAGES).coerceAtLeast(0)
        val keepTo = (last + PREFETCH_PAGES).coerceAtMost(pages.lastIndex)
        val remove = bitmaps.keys.filter { it < keepFrom || it > keepTo }
        for (k in remove) {
            val old = bitmaps.remove(k)
            if (old != null && !old.bitmap.isRecycled) old.bitmap.recycle()
        }
        trimCache()
    }

    override fun onDetachedFromWindow() {
        cancelPendingZoom()
        recycleBitmaps()
        super.onDetachedFromWindow()
    }

    companion object {
        private const val PREFETCH_PAGES = 8
        private const val MAX_CACHED_PAGES = 32
        private const val MAX_CACHE_BYTES = 96L * 1024L * 1024L
    }
}
