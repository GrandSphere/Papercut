package com.grandsphere.papercut.ui.viewer

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import android.os.SystemClock
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.OverScroller
import com.grandsphere.papercut.data.PenButtonAction
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
        fun requestPage(index: Int, width: Int, urgent: Boolean = false) {}
        fun cancelRendersExcept(keep: IntRange) {}
        fun loadText(index: Int, done: (List<CharBox>) -> Unit) {}
        fun requestLinks(index: Int) {}
        fun onPinchEnd(percent: Float) {}
        fun onSelectionText(text: String?) {}
        fun onVisiblePage(index: Int, count: Int) {}
        fun onDoubleTap() {}
        fun onTapZoom(mode: ZoomMode, percent: Float) {}
        fun onLockSideScroll(on: Boolean) {}
        fun onLinkTapped(uri: String, isExternal: Boolean) {}
        fun onPageLongPressed(index: Int) {}
        fun onPageTapped(index: Int) {}
        fun onFolioSwipe(delta: Int) {}
        fun onUserPan() {}
        fun onNonScrollNavigation() {}
        fun onPenAction(action: PenButtonAction) {}
        fun onDrawerGesturesEnabled(enabled: Boolean) {}

        object None : Callbacks
    }

    var callbacks: Callbacks? = null
    var folioPaging: Boolean = false
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
    private var customPercent: Float = 100f

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
    private val folioSwipeSlopPx = 96f * resources.displayMetrics.density
    private var penModeEnabled = false
    private var penIgnoresScrollLock = false
    private var penTapAction = PenButtonAction.DEFAULT_INTERACTION
    private var penScreenDoubleAction = PenButtonAction.DO_NOTHING
    private var penScreenHoldAction = PenButtonAction.DO_NOTHING
    private var penPressAction = PenButtonAction.OPEN_BOOKMARKS
    private var penHoldAction = PenButtonAction.ADD_QUICK_BOOKMARK
    private var airPressAction = PenButtonAction.OPEN_BOOKMARKS
    private var airHoldAction = PenButtonAction.ADD_QUICK_BOOKMARK
    private var penDoubleAction = PenButtonAction.OPEN_BOOKMARKS
    private var airDoubleAction = PenButtonAction.OPEN_BOOKMARKS
    private var penDoubleTapPeriodMs = ViewerSettings.DEFAULT_PEN_DOUBLE_TAP_MS
    private var penHoldPeriodMs = ViewerSettings.DEFAULT_PEN_HOLD_MS
    private var lastStylusX = 0f
    private var lastStylusY = 0f
    private var presenting = false
    private var presenterPan = 0f
    private var stylusPressActive = false
    private var stylusHoldPosted = false
    private var stylusHoldFired = false
    private var stylusIgnoreStickyUntilZero = false
    private var lastDigitizerButtonAt = 0L
    private var lastStylusHoverAt = 0L
    private var pendingPenPress = false
    private val pendingPenPressRunnable = Runnable {
        pendingPenPress = false
        firePenAction(false)
    }
    private val stylusHoldRunnable = Runnable {
        if (!stylusPressActive || stylusHoldFired) return@Runnable
        stylusHoldFired = true
        cancelPendingPenPress()
        firePenAction(true)
    }
    private var airButtonDown = false
    private var airHoldPosted = false
    private var airHoldFired = false
    private val airHoldRunnable = Runnable {
        if (!airButtonDown || airHoldFired) return@Runnable
        airHoldFired = true
        fireAirAction(true)
    }
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
    private var searchByPage: Map<Int, List<Int>> = emptyMap()
    private var pendingRestoreX: Float? = null
    private var pendingRestorePage: Int? = null
    private var pendingRestoreOffset: Float? = null

    private val bitmaps = LinkedHashMap<Int, CachedPage>(16, 0.75f, true)
    private val inFlight = HashSet<Int>()
    private val textCache = HashMap<Int, List<CharBox>>()
    private val linkCache = HashMap<Int, List<PageLink>>()
    private val linksInFlight = HashSet<Int>()

    private val scroller = OverScroller(context)
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

    private class CachedPage(val width: Int, val key: Long, val bitmap: Bitmap) {
        val bytes: Long = bitmap.byteCount.toLong()
    }

    private var cachedBytes = 0L

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

    fun replacePageSizes(newPages: List<PageInfo>, recycle: Boolean = false) {
        if (newPages.isEmpty() || newPages.size != pages.size) return
        pages = newPages
        if (recycle) {
            recycleBitmaps()
            inFlight.clear()
            textCache.clear()
            linkCache.clear()
            linksInFlight.clear()
        }
        rebuildTopsPreservingScroll()
        applyPendingPageRestore()
        clampScroll()
        invalidate()
    }

    fun hasDocument(): Boolean = pages.isNotEmpty()

    private fun hasPainted(index: Int): Boolean {
        val cached = bitmaps[index] ?: return false
        return cached.key == renderKey && !cached.bitmap.isRecycled
    }

    private fun keepRange(center: Int): IntRange {
        if (pages.isEmpty()) return IntRange.EMPTY
        return (center - PREFETCH_PAGES).coerceAtLeast(0)..(center + PREFETCH_PAGES).coerceAtMost(pages.lastIndex)
    }

    private fun targetWidthPx(): Int = (maxWidthPdf * scale).roundToInt().coerceIn(1, 4096)

    private fun requestPagesAround(center: Int, widthHint: Int = 0) {
        if (pages.isEmpty() || width <= 0) return
        val keep = keepRange(center)
        callbacks?.cancelRendersExcept(keep)
        inFlight.removeAll { it !in keep }
        val targetWidth = if (widthHint > 0) widthHint else targetWidthPx()
        requestIfNeeded(center, targetWidth, urgent = true)
        for (d in 1..PREFETCH_PAGES) {
            val before = center - d
            val after = center + d
            if (before >= keep.first) requestIfNeeded(before, targetWidth, urgent = false)
            if (after <= keep.last) requestIfNeeded(after, targetWidth, urgent = false)
        }
    }

    private fun requestIfNeeded(index: Int, targetWidth: Int, urgent: Boolean) {
        val cached = bitmaps[index]
        val sharp = cached != null && cached.key == renderKey && !cached.bitmap.isRecycled &&
            abs(cached.width - targetWidth) <= 1
        if (!sharp && inFlight.add(index)) {
            callbacks?.requestPage(index, targetWidth, urgent)
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
        searchByPage = emptyMap()
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
            val restoring = hasPendingPageRestore()
            applyZoomInternal(zoomMode, customPercent, keepScroll = restoring, centerX = !restoring)
            applyPendingPageRestore()
            if (!restoring) {
                scrollXPdf = 0f
                scrollYPdf = 0f
                clampScroll()
            }
        } else if (!hasPendingPageRestore()) {
            scrollXPdf = 0f
            scrollYPdf = 0f
        }
        reportVisiblePage()
        ensureFitWidthLock()
        invalidate()
    }

    fun paintKey(): Long = renderKey

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
        scroller.abortAnimation()
        requestPagesAround(index)
        invalidate()
        return true
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
        penModeEnabled = settings.penModeEnabled
        penIgnoresScrollLock = settings.penIgnoresScrollLock
        penTapAction = settings.penTapAction()
        penScreenDoubleAction = settings.penScreenDoubleAction()
        penScreenHoldAction = settings.penScreenHoldAction()
        penPressAction = settings.penPressAction()
        penHoldAction = settings.penHoldAction()
        airPressAction = settings.airPressAction()
        airHoldAction = settings.airHoldAction()
        penDoubleAction = settings.penDoubleAction()
        airDoubleAction = settings.airDoubleAction()
        penDoubleTapPeriodMs = ViewerSettings.clampPenTimingMs(settings.penDoubleTapPeriodMs)
        penHoldPeriodMs = ViewerSettings.clampPenTimingMs(settings.penHoldPeriodMs)
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
        val byPage = HashMap<Int, MutableList<Int>>()
        val needed = HashSet<Int>()
        hits.forEachIndexed { i, hit ->
            needed.add(hit.startPage)
            needed.add(hit.endPage)
            for (p in min(hit.startPage, hit.endPage)..max(hit.startPage, hit.endPage)) {
                byPage.getOrPut(p) { ArrayList() }.add(i)
            }
        }
        searchByPage = byPage
        needed.forEach { p -> ensureText(p) { invalidate() } }
        if (hits.isNotEmpty()) {
            callbacks?.onNonScrollNavigation()
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
        val page = CachedPage(width, renderKey, bitmap)
        val old = bitmaps.put(index, page)
        cachedBytes += page.bytes
        if (old != null) {
            cachedBytes -= old.bytes
            if (old.bitmap !== bitmap) old.bitmap.recycle()
        }
        trimCache()
        invalidate()
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
            if (index !in inFlight && !hasPainted(index)) {
                inFlight.add(index)
                callbacks?.requestPage(index, targetWidthPx(), urgent = true)
            }
        } else {
            requestPagesAround(index)
        }
        invalidate()
    }

    fun setScrubbing(on: Boolean) {
        if (scrubbing == on) return
        scrubbing = on
        if (!on) invalidate()
    }

    fun applyFitWidth() {
        userZoomed = true
        fitWidthLockHeld = false
        presenting = false
        zoomMode = ZoomMode.FIT_WIDTH
        applyZoomInternal(ZoomMode.FIT_WIDTH, customPercent, keepScroll = true, centerX = true)
        scrollXPdf = centerScrollX()
        clampScroll()
        lockSideScroll = true
        callbacks?.onLockSideScroll(true)
        invalidate()
    }

    fun applyFitHeight() {
        if (pages.isEmpty() || width <= 0 || height <= 0) return
        userZoomed = true
        presenting = false
        noteUserZoomTo(ZoomMode.FIT_HEIGHT)
        zoomMode = ZoomMode.FIT_HEIGHT
        applyZoomInternal(ZoomMode.FIT_HEIGHT, customPercent, keepScroll = true, centerX = true)
        invalidate()
    }

    fun setPresentation(on: Boolean) {
        if (presenting == on) return
        presenting = on
        presenterPan = 0f
        if (on && pages.isNotEmpty() && width > 0) {
            applyPresenterPage(currentVisiblePage())
        }
        invalidate()
    }

    fun isPresenting(): Boolean = presenting

    fun stepPage(delta: Int) {
        if (pages.isEmpty() || delta == 0) return
        val next = (currentVisiblePage() + delta).coerceIn(0, pages.lastIndex)
        callbacks?.onNonScrollNavigation()
        if (presenting) {
            applyPresenterPage(next)
        } else {
            goToPage(next)
        }
    }

    fun scrollByScreen(direction: Int) {
        if (pages.isEmpty() || scale <= 0f || direction == 0 || height <= 0) return
        scroller.abortAnimation()
        scrollYPdf += direction * (height / scale)
        clampScroll()
        callbacks?.onUserPan()
        invalidate()
    }

    private fun applyPresenterPage(index: Int) {
        if (pages.isEmpty() || width <= 0 || index !in pages.indices) return
        zoomMode = ZoomMode.FIT_PAGE
        val page = pages[index]
        scale = min(width / max(page.width, 1f), height / max(page.height, 1f))
            .coerceIn(minScale(), maxScale())
        rebuildTops()
        scrollXPdf = (page.width * scale - width) / (2f * scale)
        val viewH = if (scale <= 0f) 0f else height / scale
        val top = pageTops[index]
        scrollYPdf = if (page.height < viewH) {
            top - (viewH - page.height) / 2f
        } else {
            top
        }
        clampScroll()
        requestPagesAround(index)
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
            ZoomMode.FIT_HEIGHT -> {
                val page = pages.getOrNull(currentVisiblePage()) ?: pages[0]
                height / max(page.height, 1f)
            }
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

    private fun allowSidePan(pointerCount: Int, stylus: Boolean = false): Boolean {
        if (presenting) return false
        val penScroll = stylus && penModeEnabled
        if (folioPaging && pointerCount < 2 && !twoFingerGesture && !penScroll) return false
        if (lockSideScroll && !(penScroll && (penIgnoresScrollLock || folioPaging))) return false
        if (penScroll) return true
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
        val focusY = screenCenterPdfY()
        val focusX = screenCenterPdfX()
        val offsetInPage = if (vis in pages.indices && vis in pageTops.indices) {
            focusY - pageTops[vis]
        } else {
            0f
        }
        val dpi = resources.displayMetrics.densityDpi.toFloat()
        val fitPage = pages.getOrNull(vis) ?: pages[0]
        val next = when (mode) {
            ZoomMode.FIT_WIDTH -> fitWidthUsable() / fitWidthPageWidth()
            ZoomMode.FIT_HEIGHT -> height / max(fitPage.height, 1f)
            ZoomMode.FIT_PAGE -> min(width / fitPage.width, height / max(fitPage.height, 1f))
            ZoomMode.CUSTOM -> (dpi / 72f) * (percent / 100f)
        }.coerceIn(minScale(), maxScale())
        scale = next
        rebuildTops()
        if (keepScroll) {
            scrollXPdf = focusX - (width / 2f) / scale
            scrollYPdf = when {
                mode == ZoomMode.FIT_HEIGHT && vis in pageTops.indices -> pageTops[vis]
                vis in pageTops.indices -> pageTops[vis] + offsetInPage - (height / 2f) / scale
                else -> focusY - (height / 2f) / scale
            }
        } else {
            scrollYPdf = 0f
        }
        if (centerX) {
            scrollXPdf = centerScrollX()
        }
        clampScroll()
        inFlight.clear()
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
        val restoring = hasPendingPageRestore()
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
            applyPendingPageRestore()
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
            callbacks?.onUserPan()
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
        val active = currentVisiblePage()
        val left = -scrollXPdf * scale

        for (i in first..last) {
            val info = pages[i]
            val top = (pageTops[i] - scrollYPdf) * scale
            destRect.set(left, top, left + info.width * scale, top + info.height * scale)
            val cached = bitmaps[i]
            if (cached != null && cached.key == renderKey && !cached.bitmap.isRecycled) {
                canvas.drawBitmap(cached.bitmap, null, destRect, bitmapPaint)
            }
            val showFocusBorder = i == active && (pageSelectMode || pageBorderEnabled)
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
            requestPagesAround(active, targetWidthPx())
            val keep = keepRange(active)
            trimOffscreen(keep.first, keep.last)
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
        val hitIdx = searchByPage[page] ?: return
        val chars = textCache[page] ?: return
        for (i in hitIdx) {
            val hit = searchHits[i]
            val range = selectedIndices(
                page,
                SelectionRange(hit.startPage, hit.startIndex, hit.endPage, hit.endIndex)
            ) ?: continue
            drawChars(canvas, page, chars, range, if (i == searchIndex) searchCurrentPaint else searchPaint)
        }
    }

    private fun drawSelectionOnPage(canvas: Canvas, page: Int) {
        val sel = selection ?: return
        val chars = textCache[page] ?: return
        val range = selectedIndices(page, sel) ?: return
        drawChars(canvas, page, chars, range, highlightPaint)
    }

    private fun drawChars(canvas: Canvas, page: Int, chars: List<CharBox>, range: IntRange, paint: Paint) {
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
            if (penModeEnabled && isStylus(event) &&
                penScreenDoubleAction != PenButtonAction.DEFAULT_INTERACTION
            ) {
                cancelPendingZoom()
            } else if (inSlop && !lockZoom && !selecting && draggingHandle == null && !pinching) {
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
        rememberStylusPos(event)
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            if (penModeEnabled && isStylus(event)) {
                parent.requestDisallowInterceptTouchEvent(true)
                callbacks?.onDrawerGesturesEnabled(false)
            } else if (event.x > edgeSlop && !folioPaging) {
                parent.requestDisallowInterceptTouchEvent(true)
            }
        }
        handleStylusButton(event)
        if (penModeEnabled && isStylus(event) && event.actionMasked == MotionEvent.ACTION_MOVE) {
            parent.requestDisallowInterceptTouchEvent(true)
        }
        if (folioPaging && !(penModeEnabled && isStylus(event))) {
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
                    if (abs(dx) > folioSwipeSlopPx && abs(dx) > 2f * abs(dy)) {
                        folioSwiped = true
                        parent.requestDisallowInterceptTouchEvent(true)
                        callbacks?.onNonScrollNavigation()
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
        gestureDetector.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            if (event.actionMasked == MotionEvent.ACTION_CANCEL) cancelPendingZoom()
            endTwoFingerZoom()
            selecting = false
            draggingHandle = null
            twoFingerGesture = false
            callbacks?.onDrawerGesturesEnabled(true)
        }
        return true
    }

    private fun handleBottomMenuSwipe(event: MotionEvent): Boolean {
        if (pageSelectMode || !swipeFromBottomMenu || penBlocksMenu(event)) {
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

    private inner class GestureListener : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean {
            if (!scroller.isFinished) scroller.abortAnimation()
            draggingHandle = if (textSelectionAllowed(isStylus(e))) hitHandle(e.x, e.y) else null
            return true
        }

        override fun onScroll(
            e1: MotionEvent?,
            e2: MotionEvent,
            distanceX: Float,
            distanceY: Float
        ): Boolean {
            if (pinching || twoFingerTracking || bottomMenuTracking) return false
            cancelPendingZoom()
            val stylus = isStylus(e2)
            val canSelect = textSelectionAllowed(stylus)
            if (canSelect && (selecting || draggingHandle != null)) {
                extendSelection(e2.x, e2.y)
                return true
            }
            if (stylus && penModeEnabled) {
                parent.requestDisallowInterceptTouchEvent(true)
            }
            if (presenting) {
                val dominant = if (abs(distanceX) > abs(distanceY)) distanceX else distanceY
                presenterPan += dominant
                val step = 48f * resources.displayMetrics.density
                if (presenterPan <= -step) {
                    presenterPan = 0f
                    stepPage(-1)
                } else if (presenterPan >= step) {
                    presenterPan = 0f
                    stepPage(1)
                }
                return true
            }
            if (allowSidePan(e2.pointerCount, stylus)) scrollXPdf += distanceX / scale
            scrollYPdf += distanceY / scale
            callbacks?.onUserPan()
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
            if (presenting) {
                if (max(abs(velocityX), abs(velocityY)) > 200f) {
                    val next = if (abs(velocityX) > abs(velocityY)) {
                        if (velocityX > 0f) -1 else 1
                    } else {
                        if (velocityY > 0f) -1 else 1
                    }
                    stepPage(next)
                }
                return true
            }
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
                if (allowSidePan(if (twoFingerGesture) 2 else 1, isStylus(e2))) -velocityX.toInt() else 0,
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
            if (penModeEnabled && isStylus(e)) {
                performMappedAction(penScreenDoubleAction, e.x, e.y) {
                    fingerDoubleTap(e.x, e.y)
                    true
                }
                return true
            }
            fingerDoubleTap(e.x, e.y)
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
            if (penModeEnabled && isStylus(e)) {
                return performMappedAction(penTapAction, e.x, e.y) {
                    fingerSingleTap(e.x, e.y)
                }
            }
            return fingerSingleTap(e.x, e.y)
        }

        override fun onLongPress(e: MotionEvent) {
            if (pageSelectMode || pages.isEmpty()) return
            if (penModeEnabled && isStylus(e)) {
                performMappedAction(penScreenHoldAction, e.x, e.y) {
                    startFingerSelection(e.x, e.y)
                    true
                }
                return
            }
            startFingerSelection(e.x, e.y)
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
        cachedBytes = 0L
    }

    private fun dropCached(key: Int) {
        val old = bitmaps.remove(key) ?: return
        cachedBytes -= old.bytes
        if (!old.bitmap.isRecycled) old.bitmap.recycle()
    }

    private fun trimCache() {
        val keep = keepRange(currentVisiblePage())
        while (bitmaps.size > MAX_CACHED_PAGES || cachedBytes > MAX_CACHE_BYTES) {
            val eldest = bitmaps.keys.firstOrNull { it !in keep } ?: break
            dropCached(eldest)
        }
    }

    private fun trimOffscreen(first: Int, last: Int) {
        val keepFrom = (first - PREFETCH_PAGES).coerceAtLeast(0)
        val keepTo = (last + PREFETCH_PAGES).coerceAtMost(pages.lastIndex)
        val it = bitmaps.entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            if (e.key < keepFrom || e.key > keepTo) {
                it.remove()
                cachedBytes -= e.value.bytes
                if (!e.value.bitmap.isRecycled) e.value.bitmap.recycle()
            }
        }
        trimCache()
    }
    override fun onDetachedFromWindow() {
        cancelPendingZoom()
        cancelStylusHold()
        cancelAirHold()
        cancelPendingPenPress()
        recycleBitmaps()
        super.onDetachedFromWindow()
    }

    private fun isStylusPointer(event: MotionEvent): Boolean {
        if (event.pointerCount > 0) {
            val index = event.actionIndex.coerceIn(0, event.pointerCount - 1)
            val type = event.getToolType(index)
            if (type == MotionEvent.TOOL_TYPE_STYLUS || type == MotionEvent.TOOL_TYPE_ERASER) return true
        }
        return event.source and InputDevice.SOURCE_STYLUS == InputDevice.SOURCE_STYLUS
    }

    private fun isStylus(event: MotionEvent): Boolean {
        if (isStylusPointer(event)) return true
        return SystemClock.uptimeMillis() - lastStylusHoverAt < 400L
    }

    private fun penBlocksMenu(event: MotionEvent): Boolean =
        penModeEnabled && isStylus(event) && penTapAction != PenButtonAction.DEFAULT_INTERACTION

    private fun rememberStylusPos(event: MotionEvent) {
        if (event.pointerCount == 0) return
        if (!isStylus(event)) return
        val index = event.actionIndex.coerceIn(0, event.pointerCount - 1)
        lastStylusX = event.getX(index)
        lastStylusY = event.getY(index)
    }

    override fun onHoverEvent(event: MotionEvent): Boolean {
        if (handleStylusHover(event)) return true
        return super.onHoverEvent(event)
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (handleStylusHover(event)) return true
        return super.onGenericMotionEvent(event)
    }

    fun handleStylusHover(event: MotionEvent): Boolean {
        if (isStylusPointer(event)) lastStylusHoverAt = SystemClock.uptimeMillis()
        rememberStylusPos(event)
        if (!penModeEnabled) return false
        val action = event.actionMasked
        val stylusButtonEvent = action == MotionEvent.ACTION_BUTTON_PRESS ||
            action == MotionEvent.ACTION_BUTTON_RELEASE ||
            stylusSideButtonBit(event)
        if (!isStylusPointer(event) && !stylusButtonEvent) return false
        when (action) {
            MotionEvent.ACTION_HOVER_ENTER,
            MotionEvent.ACTION_HOVER_MOVE,
            MotionEvent.ACTION_HOVER_EXIT,
            MotionEvent.ACTION_BUTTON_PRESS,
            MotionEvent.ACTION_BUTTON_RELEASE -> {
                handleStylusButton(event)
                return true
            }
        }
        return false
    }

    fun handleAirActionKey(event: KeyEvent): Boolean {
        if (!penModeEnabled) return false
        if (event.keyCode == KeyEvent.KEYCODE_F11) {
            if (SystemClock.uptimeMillis() - lastDigitizerButtonAt < 400L) return true
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                performMappedAction(airDoubleAction, lastStylusX, lastStylusY) {
                    fingerDoubleTap(lastStylusX, lastStylusY)
                    true
                }
            }
            return true
        }
        if (!isAirActionKey(event)) return false
        if (SystemClock.uptimeMillis() - lastDigitizerButtonAt < 400L) {
            cancelAirHold()
            return true
        }
        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (event.repeatCount > 0) {
                    if (airButtonDown && !airHoldFired) {
                        airHoldFired = true
                        if (airHoldPosted) removeCallbacks(airHoldRunnable)
                        airHoldPosted = false
                        fireAirAction(true)
                    }
                    return true
                }
                if (airButtonDown) return true
                airButtonDown = true
                airHoldFired = false
                airHoldPosted = true
                postDelayed(airHoldRunnable, ViewConfiguration.getLongPressTimeout().toLong())
            }
            KeyEvent.ACTION_UP -> finishAirButton()
            else -> {
                cancelAirHold()
                return false
            }
        }
        return true
    }

    private fun textSelectionAllowed(stylus: Boolean): Boolean {
        if (stylus && penModeEnabled) {
            return penScreenHoldAction == PenButtonAction.DEFAULT_INTERACTION && allowTextSelection
        }
        return allowTextSelection
    }

    private fun fingerSingleTap(x: Float, y: Float): Boolean {
        if (allowFollowLinks && followLinkAt(x, y)) return true
        if (!singleTapMenu || selecting || draggingHandle != null || pinching) return false
        callbacks?.onDoubleTap()
        return true
    }

    private fun fingerDoubleTap(x: Float, y: Float) {
        if (!doubleTapMenu) {
            toggleTapZoom(x, y)
            return
        }
        callbacks?.onDoubleTap()
        cancelPendingZoom()
        pendingZoom = true
        pendingZoomX = x
        pendingZoomY = y
        postDelayed(pendingZoomRunnable, ViewConfiguration.getDoubleTapTimeout().toLong())
    }

    private fun startFingerSelection(x: Float, y: Float) {
        if (!allowTextSelection || pages.isEmpty()) return
        selecting = true
        startWordAt(x, y)
    }

    private fun followLinkAt(x: Float, y: Float): Boolean {
        val page = pageAt(y) ?: return false
        val pdfX = viewToPageX(page, x)
        val pdfY = viewToPageY(page, y)
        val hit = linkCache[page]?.firstOrNull { link ->
            val b = link.bounds
            pdfX >= b.left - 8f && pdfX <= b.right + 8f &&
                pdfY >= b.top - 8f && pdfY <= b.bottom + 8f
        } ?: return false
        callbacks?.onLinkTapped(hit.uri, hit.isExternal)
        return true
    }

    private fun performMappedAction(
        action: PenButtonAction,
        x: Float,
        y: Float,
        default: () -> Boolean,
    ): Boolean {
        if (presenting) {
            stepPage(1)
            return true
        }
        return when (action) {
            PenButtonAction.DEFAULT_INTERACTION -> default()
            PenButtonAction.DO_NOTHING -> true
            PenButtonAction.FOLLOW_LINK -> {
                followLinkAt(x, y)
                true
            }
            PenButtonAction.SCROLL_NEXT_PAGE -> {
                scrollByScreen(1)
                true
            }
            PenButtonAction.SCROLL_PREV_PAGE -> {
                scrollByScreen(-1)
                true
            }
            else -> {
                callbacks?.onPenAction(action)
                true
            }
        }
    }

    private fun stylusSideButtonBit(event: MotionEvent): Boolean {
        val mask = MotionEvent.BUTTON_STYLUS_PRIMARY or MotionEvent.BUTTON_STYLUS_SECONDARY
        return event.buttonState and mask != 0
    }

    private fun isStylusSideActionButton(event: MotionEvent): Boolean {
        val button = event.actionButton
        if (button == MotionEvent.BUTTON_STYLUS_PRIMARY ||
            button == MotionEvent.BUTTON_STYLUS_SECONDARY
        ) {
            return true
        }
        return button == 0 && stylusSideButtonBit(event)
    }

    private fun handleStylusButton(event: MotionEvent) {
        if (!penModeEnabled) return
        val action = event.actionMasked
        val bit = stylusSideButtonBit(event)
        val explicitPress = action == MotionEvent.ACTION_BUTTON_PRESS && isStylusSideActionButton(event)
        val explicitRelease = action == MotionEvent.ACTION_BUTTON_RELEASE && isStylusSideActionButton(event)
        val stylusRelated = isStylus(event) || explicitPress || explicitRelease || bit
        if (!stylusRelated) return
        if (action == MotionEvent.ACTION_CANCEL) {
            cancelStylusHold()
            return
        }
        if (stylusIgnoreStickyUntilZero && !bit) {
            stylusIgnoreStickyUntilZero = false
        }
        val pressed = when {
            explicitPress -> true
            explicitRelease -> false
            action == MotionEvent.ACTION_HOVER_EXIT -> false
            stylusIgnoreStickyUntilZero -> false
            else -> bit
        }
        if (pressed && !stylusPressActive) {
            beginStylusButton()
        } else if (!pressed && stylusPressActive) {
            if (bit && (explicitRelease || action == MotionEvent.ACTION_HOVER_EXIT)) {
                stylusIgnoreStickyUntilZero = true
            }
            finishStylusButton()
        }
    }

    private fun beginStylusButton() {
        stylusPressActive = true
        lastDigitizerButtonAt = SystemClock.uptimeMillis()
        stylusHoldFired = false
        stylusHoldPosted = true
        postDelayed(stylusHoldRunnable, penHoldPeriodMs.toLong())
    }

    private fun finishStylusButton() {
        val hold = stylusHoldFired
        cancelStylusHold()
        if (hold) return
        onPenButtonClick()
    }

    private fun onPenButtonClick() {
        if (pendingPenPress) {
            cancelPendingPenPress()
            performMappedAction(penDoubleAction, lastStylusX, lastStylusY) {
                fingerDoubleTap(lastStylusX, lastStylusY)
                true
            }
            return
        }
        pendingPenPress = true
        postDelayed(pendingPenPressRunnable, penDoubleTapPeriodMs.toLong())
    }

    private fun cancelPendingPenPress() {
        if (pendingPenPress) removeCallbacks(pendingPenPressRunnable)
        pendingPenPress = false
    }

    private fun cancelStylusHold() {
        if (stylusHoldPosted) removeCallbacks(stylusHoldRunnable)
        stylusHoldPosted = false
        stylusPressActive = false
        stylusHoldFired = false
    }

    private fun finishAirButton() {
        val hold = airHoldFired
        cancelAirHold()
        if (!hold) fireAirAction(false)
    }

    private fun cancelAirHold() {
        if (airHoldPosted) removeCallbacks(airHoldRunnable)
        airHoldPosted = false
        airButtonDown = false
        airHoldFired = false
    }

    private fun firePenAction(hold: Boolean) {
        if (presenting) {
            stepPage(if (hold) -1 else 1)
            return
        }
        val action = if (hold) penHoldAction else penPressAction
        performMappedAction(action, lastStylusX, lastStylusY) {
            if (hold) startFingerSelection(lastStylusX, lastStylusY)
            else fingerSingleTap(lastStylusX, lastStylusY)
            true
        }
    }

    private fun fireAirAction(hold: Boolean) {
        val action = if (hold) airHoldAction else airPressAction
        performMappedAction(action, lastStylusX, lastStylusY) {
            if (hold) startFingerSelection(lastStylusX, lastStylusY)
            else fingerSingleTap(lastStylusX, lastStylusY)
            true
        }
    }

    private fun isAirActionKey(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_F10) return true
        return event.keyCode == KEYCODE_STYLUS_BUTTON
    }

    companion object {
        private const val PREFETCH_PAGES = 8
        private const val MAX_CACHED_PAGES = 32
        private const val MAX_CACHE_BYTES = 96L * 1024L * 1024L
        private const val KEYCODE_STYLUS_BUTTON = 757
    }
}
