package com.grandsphere.papercut.ui.viewer

import android.os.SystemClock
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsIgnoringVisibility
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material.icons.outlined.CheckBoxOutlineBlank
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Reorder
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.WidthFull
import androidx.compose.material.icons.outlined.ZoomIn
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import kotlin.math.abs
import kotlin.math.roundToInt
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.grandsphere.papercut.data.Bookmark
import com.grandsphere.papercut.data.FolioItem
import com.grandsphere.papercut.data.ViewerSettings
import com.grandsphere.papercut.data.ZoomMode
import com.grandsphere.papercut.pdf.CharBox
import com.grandsphere.papercut.pdf.OutlineItem
import com.grandsphere.papercut.pdf.PageInfo
import com.grandsphere.papercut.pdf.SearchHit
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.grandsphere.papercut.ui.chrome.PapercutAlertDialog
import com.grandsphere.papercut.ui.chrome.PapercutOverlay
import com.grandsphere.papercut.ui.chrome.ThinGreySlider
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
fun ViewerScreen(
    settings: ViewerSettings,
    hasDocument: Boolean,
    outline: List<OutlineItem>,
    searchHits: List<SearchHit>,
    hitIndex: Int,
    searching: Boolean,
    bookmarks: List<Bookmark>,
    pages: List<PageInfo>,
    readingPosition: () -> Triple<Int, Float, Float>,
    currentZoomPercent: Float,
    zoomMode: ZoomMode,
    onOpen: () -> Unit,
    onSettings: () -> Unit,
    onGoToPageIndex: (Int, Float?) -> Unit,
    onScrubbing: (Boolean) -> Unit,
    onFitWidth: () -> Unit,
    onZoomPercent: (Float) -> Unit,
    onLockZoom: (Boolean) -> Unit,
    onLockSide: (Boolean) -> Unit,
    onOriginalColors: (Boolean) -> Unit,
    onAbout: () -> Unit,
    onQuit: () -> Unit,
    onCopy: () -> Unit,
    onShare: () -> Unit,
    onShareDocument: () -> Unit,
    inFolio: Boolean,
    folioEnabled: Boolean,
    folioItems: List<FolioItem>,
    folioIndex: Int,
    folioOpened: Set<String>,
    alreadyInFolio: Boolean,
    isPdf: Boolean,
    busy: String?,
    pageLoading: Boolean = false,
    startupReady: Boolean = true,
    overlayReset: Int = 0,
    onEnterFolio: () -> Unit,
    onLeaveFolio: () -> Unit,
    onAddToFolio: () -> Unit,
    onFolioIndex: (Int) -> Unit,
    onSaveFolio: () -> Unit,
    onShareFull: () -> Unit,
    onSharePartial: () -> Unit,
    onCombine: () -> Unit,
    onManageFolio: () -> Unit,
    pageSelectMode: Boolean,
    selectedPages: List<Int>,
    onTogglePage: (Int) -> Unit,
    onSetSelectedPages: (List<Int>) -> Unit,
    onExitPageSelect: () -> Unit,
    onShareSelectedPages: () -> Unit,
    passwordNeeded: Boolean,
    passwordWrong: Boolean,
    onSubmitPassword: (String) -> Unit,
    onCancelPassword: () -> Unit,
    onQuery: (String) -> Unit,
    onPrevHit: () -> Unit,
    onNextHit: () -> Unit,
    onSelectHit: (Int) -> Unit,
    onAddBookmark: (String, Int, Float, Float) -> Unit,
    onUpdateBookmark: (Bookmark) -> Unit,
    onDeleteBookmarks: (List<Long>) -> Unit,
    onReorderBookmarks: (List<Long>) -> Unit,
    onJumpBookmark: (Bookmark) -> Unit,
    pdfFactory: (android.content.Context) -> PdfStripView,
) {
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var findOpen by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var selection by remember { mutableStateOf<String?>(null) }
    var currentPage by remember { mutableIntStateOf(1) }
    var pageCount by remember { mutableIntStateOf(0) }
    var chromeOpen by remember { mutableStateOf(false) }
    var shareOpen by remember { mutableStateOf(false) }
    var showPageScrubber by remember { mutableStateOf(false) }
    var tocOpen by remember { mutableStateOf(false) }
    var zoomOpen by remember { mutableStateOf(false) }
    var gotoOpen by remember { mutableStateOf(false) }
    var hitsOpen by remember { mutableStateOf(false) }
    var bookmarksOpen by remember { mutableStateOf(false) }
    var folioJumpOpen by remember { mutableStateOf(false) }
    var rearrangeOpen by remember { mutableStateOf(false) }
    var forwarding by remember { mutableStateOf(false) }
    val suppressMenuUntil = remember { mutableLongStateOf(0L) }
    var pdfStrip by remember { mutableStateOf<PdfStripView?>(null) }
    val findFocus = remember { FocusRequester() }
    val hasOutline = outline.isNotEmpty()

    fun hint(text: String) {
        Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
    }

    BackHandler(enabled = pageSelectMode) { onExitPageSelect() }
    BackHandler(enabled = shareOpen) { shareOpen = false }
    BackHandler(enabled = drawerState.isOpen) {
        scope.launch { drawerState.close() }
    }
    BackHandler(enabled = tocOpen) { tocOpen = false }
    BackHandler(enabled = zoomOpen) { zoomOpen = false }
    BackHandler(enabled = gotoOpen) { gotoOpen = false }
    BackHandler(enabled = hitsOpen) { hitsOpen = false }
    BackHandler(enabled = bookmarksOpen) { bookmarksOpen = false }
    BackHandler(enabled = folioJumpOpen) { folioJumpOpen = false }
    BackHandler(enabled = chromeOpen && drawerState.isClosed && !tocOpen && !zoomOpen && !gotoOpen && !hitsOpen && !bookmarksOpen && !folioJumpOpen) {
        chromeOpen = false
    }
    BackHandler(enabled = !selection.isNullOrEmpty() && drawerState.isClosed) {
        pdfStrip?.clearSelection()
        selection = null
    }
    BackHandler(enabled = findOpen && drawerState.isClosed && !chromeOpen) {
        findOpen = false
        hitsOpen = false
        onQuery("")
    }

    var pageSelectWasOn by remember { mutableStateOf(false) }
    LaunchedEffect(pageSelectMode) {
        if (pageSelectMode) {
            pageSelectWasOn = true
            chromeOpen = false
            findOpen = false
            zoomOpen = false
            pdfStrip?.applyFitWidthForPageSelect()
        } else if (pageSelectWasOn) {
            pageSelectWasOn = false
            rearrangeOpen = false
            pdfStrip?.applyResetZoom(settings)
        }
    }
    LaunchedEffect(selectedPages.isEmpty()) {
        if (selectedPages.isEmpty()) rearrangeOpen = false
    }

    LaunchedEffect(inFolio) {
        if (!inFolio) {
            showPageScrubber = false
            folioJumpOpen = false
        }
    }

    var sawContent by remember { mutableStateOf(false) }
    LaunchedEffect(hasDocument, inFolio, folioItems.size) {
        if (hasDocument || (inFolio && folioItems.isNotEmpty())) {
            sawContent = true
            if (hasDocument) drawerState.close()
        }
    }
    LaunchedEffect(startupReady, hasDocument, pageLoading, inFolio, folioItems.size) {
        if (!startupReady || sawContent || pageLoading) return@LaunchedEffect
        if (inFolio && folioItems.isNotEmpty()) return@LaunchedEffect
        if (!hasDocument) drawerState.open()
    }

    LaunchedEffect(overlayReset) {
        if (overlayReset <= 0) return@LaunchedEffect
        findOpen = false
        query = ""
        onQuery("")
        pdfStrip?.clearSelection()
        selection = null
        chromeOpen = false
        shareOpen = false
        tocOpen = false
        zoomOpen = false
        gotoOpen = false
        hitsOpen = false
        bookmarksOpen = false
        folioJumpOpen = false
        rearrangeOpen = false
        drawerState.close()
    }

    LaunchedEffect(findOpen) {
        if (findOpen) {
            delay(50)
            runCatching { findFocus.requestFocus() }
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(
                drawerContainerColor = MaterialTheme.colorScheme.background,
                modifier = Modifier.width(280.dp),
            ) {
                Column(
                    Modifier
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState())
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .windowInsetsPadding(WindowInsets.statusBarsIgnoringVisibility)
                            .height(64.dp)
                            .padding(horizontal = DrawerInset),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        Text(
                            "Papercut",
                            fontSize = 22.sp,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    HorizontalDivider(Modifier.padding(vertical = 4.dp))
                    if (folioEnabled) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = DrawerInset, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(20.dp),
                        ) {
                            val on = MaterialTheme.colorScheme.onBackground
                            Text(
                                "Viewer",
                                color = on.copy(alpha = if (!inFolio) 1f else 0.4f),
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Normal,
                                modifier = Modifier.clickable(enabled = inFolio) {
                                    scope.launch { drawerState.close() }
                                    onLeaveFolio()
                                },
                            )
                            Text(
                                "Folio",
                                color = on.copy(alpha = if (inFolio) 1f else 0.4f),
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Normal,
                                modifier = Modifier.clickable(enabled = !inFolio) {
                                    scope.launch { drawerState.close() }
                                    onEnterFolio()
                                },
                            )
                        }
                    }
                    DrawerItem(if (inFolio) "Add" else "Open") {
                        scope.launch { drawerState.close() }
                        onOpen()
                    }
                    DrawerItem("Share", enabled = hasDocument) {
                        scope.launch { drawerState.close() }
                        shareOpen = true
                    }
                    if (folioEnabled && !inFolio) {
                        DrawerItem("Add to Folio", enabled = hasDocument && !alreadyInFolio) {
                            scope.launch { drawerState.close() }
                            onAddToFolio()
                        }
                    }
                    if (!inFolio) {
                        DrawerItem("Combine") {
                            scope.launch { drawerState.close() }
                            onCombine()
                        }
                    }
                    DrawerItem("Menu") {
                        scope.launch { drawerState.close() }
                        chromeOpen = !chromeOpen
                    }
                    HorizontalDivider(Modifier.padding(vertical = 4.dp))
                    if (inFolio) {
                        DrawerItem("Manage Folio") {
                            scope.launch { drawerState.close() }
                            onManageFolio()
                        }
                    } else {
                        DrawerItem("Table of Contents", enabled = hasDocument && hasOutline) {
                            scope.launch { drawerState.close() }
                            tocOpen = true
                        }
                    }
                    HorizontalDivider(Modifier.padding(vertical = 4.dp))
                    DrawerItem("About") {
                        scope.launch { drawerState.close() }
                        onAbout()
                    }
                    DrawerItem("Settings") {
                        scope.launch { drawerState.close() }
                        onSettings()
                    }
                    HorizontalDivider(Modifier.padding(vertical = 4.dp))
                    DrawerItem("Quit", onClick = onQuit)
                }
            }
        },
    ) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            AndroidView(
                    factory = { ctx ->
                        pdfFactory(ctx).also { view ->
                            pdfStrip = view
                            view.folioPaging = inFolio
                            view.callbacks = wrapCallbacks(
                                view.callbacks,
                                onSel = { selection = it },
                                onPage = { index, count ->
                                    currentPage = if (count == 0) 1 else index + 1
                                    pageCount = count
                                },
                                onDoubleTap = {
                                    if (SystemClock.uptimeMillis() > suppressMenuUntil.longValue) {
                                        chromeOpen = true
                                    }
                                },
                                onPagePress = onTogglePage,
                            )
                        }
                    },
                    update = { view ->
                        view.folioPaging = inFolio
                        view.setPageSelect(pageSelectMode, selectedPages)
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            if (chromeOpen || forwarding) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            awaitEachGesture {
                                val view = pdfStrip ?: return@awaitEachGesture
                                val down = awaitFirstDown(requireUnconsumed = false)
                                chromeOpen = false
                                suppressMenuUntil.longValue = Long.MAX_VALUE
                                forwarding = true
                                down.consume()
                                val downTime = android.os.SystemClock.uptimeMillis()
                                dispatchToView(view, MotionEvent.ACTION_DOWN, down.position, downTime)
                                try {
                                    while (true) {
                                        val event = awaitPointerEvent()
                                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                        change.consume()
                                        if (change.changedToUpIgnoreConsumed()) {
                                            dispatchToView(view, MotionEvent.ACTION_UP, change.position, downTime)
                                            break
                                        }
                                        dispatchToView(view, MotionEvent.ACTION_MOVE, change.position, downTime)
                                    }
                                } finally {
                                    suppressMenuUntil.longValue = SystemClock.uptimeMillis() +
                                        ViewConfiguration.getDoubleTapTimeout()
                                    forwarding = false
                                }
                            }
                        },
                )
            }
            if (!hasDocument && !pageLoading && !inFolio) {
                Text(
                    "Papercut",
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                    fontSize = 28.sp,
                    modifier = Modifier.align(Alignment.Center),
                )
            }
            if (pageSelectMode) {
                ModeTopBar(
                    title = "${selectedPages.size} pages",
                    onBack = onExitPageSelect,
                    modifier = Modifier.align(Alignment.TopCenter),
                ) {
                    ChromeIcon(
                        Icons.Outlined.Reorder,
                        hint = "Rearrange",
                        enabled = selectedPages.isNotEmpty(),
                        onHint = ::hint,
                        onClick = { rearrangeOpen = true },
                    )
                    val allSelected = pages.isNotEmpty() && selectedPages.size == pages.size
                    ChromeIcon(
                        if (allSelected) Icons.Outlined.CheckBoxOutlineBlank else Icons.Outlined.SelectAll,
                        hint = if (allSelected) "Deselect all" else "Select all",
                        enabled = pages.isNotEmpty(),
                        onHint = ::hint,
                        onClick = {
                            onSetSelectedPages(
                                if (allSelected) emptyList() else pages.indices.toList(),
                            )
                        },
                    )
                    ChromeIcon(
                        Icons.Outlined.Share,
                        hint = "Share",
                        enabled = selectedPages.isNotEmpty(),
                        onHint = ::hint,
                        onClick = onShareSelectedPages,
                    )
                }
            } else if (!selection.isNullOrEmpty()) {
                ModeTopBar(
                    title = "Text Selection",
                    onBack = {
                        pdfStrip?.clearSelection()
                        selection = null
                    },
                    modifier = Modifier.align(Alignment.TopCenter),
                ) {
                    ChromeIcon(
                        Icons.Outlined.Share,
                        hint = "Share",
                        onHint = ::hint,
                        onClick = onShare,
                    )
                    ChromeIcon(
                        Icons.Outlined.ContentCopy,
                        hint = "Copy",
                        onHint = ::hint,
                        onClick = onCopy,
                    )
                }
            } else if (findOpen) {
                ModeTopBar(
                    title = if (searching) "Searching..." else "Search",
                    onBack = {
                        findOpen = false
                        hitsOpen = false
                        query = ""
                        onQuery("")
                    },
                    modifier = Modifier.align(Alignment.TopCenter),
                )
            } else if (chromeOpen) {
                Column(
                    Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.background)
                        .interceptBarGestures()
                        .windowInsetsPadding(WindowInsets.statusBarsIgnoringVisibility)
                        .padding(vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(Modifier.fillMaxWidth()) {
                        ChromeIcon(
                            Icons.Outlined.Menu,
                            hint = "Menu",
                            onHint = ::hint,
                            onClick = { scope.launch { drawerState.open() } },
                            modifier = Modifier.align(Alignment.CenterStart),
                        )
                        Row(
                            Modifier.align(Alignment.Center),
                            horizontalArrangement = Arrangement.Center,
                        ) {
                            ChromeIcon(
                                Icons.Outlined.Palette,
                                hint = if (settings.originalColors) "Themed colours" else "Original colours",
                                selected = !settings.originalColors,
                                onHint = ::hint,
                                onClick = { onOriginalColors(!settings.originalColors) },
                            )
                            Box(
                                Modifier
                                    .requiredSize(width = 64.dp, height = 48.dp)
                                    .combinedClickable(
                                        enabled = hasDocument,
                                        onClick = { zoomOpen = true },
                                        onLongClick = { hint("Zoom") },
                                    ),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    if (zoomMode == ZoomMode.FIT_WIDTH) {
                                        "Width"
                                    } else {
                                        "${currentZoomPercent.roundToInt()}%"
                                    },
                                    color = MaterialTheme.colorScheme.onBackground.copy(
                                        alpha = if (hasDocument) 0.85f else 0.35f,
                                    ),
                                    fontSize = 12.sp,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.fillMaxWidth(),
                                    maxLines = 1,
                                )
                            }
                            ChromeIcon(
                                Icons.Outlined.Search,
                                hint = "Find",
                                enabled = hasDocument,
                                onHint = ::hint,
                                onClick = {
                                    chromeOpen = false
                                    findOpen = true
                                },
                            )
                        }
                        if (inFolio) {
                            ChromeIcon(
                                Icons.Outlined.Save,
                                hint = "Save",
                                enabled = hasDocument,
                                onHint = ::hint,
                                onClick = onSaveFolio,
                                modifier = Modifier.align(Alignment.CenterEnd),
                            )
                        }
                    }
                }
            }
            if (chromeOpen && findOpen.not() && selection.isNullOrEmpty() && !pageSelectMode) {
                Column(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.background)
                        .interceptBarGestures()
                        .pointerInput(inFolio) {
                            if (!inFolio) return@pointerInput
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                val start = down.position
                                while (true) {
                                    val event = awaitPointerEvent(PointerEventPass.Initial)
                                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                    val dy = change.position.y - start.y
                                    val dx = change.position.x - start.x
                                    if (abs(dy) > 24f && abs(dy) > abs(dx)) {
                                        showPageScrubber = dy < 0f
                                    }
                                    if (change.changedToUpIgnoreConsumed()) break
                                }
                            }
                        }
                        .windowInsetsPadding(WindowInsets.navigationBarsIgnoringVisibility)
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Row(horizontalArrangement = Arrangement.Center) {
                        ChromeIcon(
                            Icons.Outlined.SwapHoriz,
                            hint = "Lock side scrolling",
                            selected = settings.lockSideScroll,
                            struck = settings.lockSideScroll,
                            onHint = ::hint,
                            onClick = { onLockSide(!settings.lockSideScroll) },
                        )
                        ChromeIcon(
                            Icons.Outlined.ZoomIn,
                            hint = "Lock zoom",
                            selected = settings.lockZoom,
                            struck = settings.lockZoom,
                            onHint = ::hint,
                            onClick = { onLockZoom(!settings.lockZoom) },
                        )
                        ChromeIcon(
                            Icons.Outlined.WidthFull,
                            hint = "Fit width",
                            enabled = hasDocument,
                            onHint = ::hint,
                            onClick = onFitWidth,
                        )
                        ChromeIcon(
                            Icons.Outlined.Bookmark,
                            hint = "Bookmarks",
                            enabled = hasDocument,
                            onHint = ::hint,
                            onClick = { bookmarksOpen = true },
                        )
                    }
                    if (inFolio) {
                        val count = folioItems.size
                        val idx = if (count == 0) 1 else folioIndex.coerceIn(0, count - 1) + 1
                        PageSlider(
                            currentPage = idx,
                            pageCount = count.coerceAtLeast(1),
                            enabled = count > 0,
                            labelEnabled = true,
                            show = true,
                            onChange = { page -> onFolioIndex(page - 1) },
                            onChangeFinished = {},
                            onLabelClick = { folioJumpOpen = true },
                        )
                    }
                    PageSlider(
                        currentPage = currentPage,
                        pageCount = pageCount,
                        enabled = hasDocument && pageCount > 0,
                        show = !inFolio || showPageScrubber,
                        onChange = { page ->
                            currentPage = page
                            onScrubbing(true)
                            onGoToPageIndex(page - 1, null)
                        },
                        onChangeFinished = { onScrubbing(false) },
                        onLabelClick = { gotoOpen = true },
                    )
                }
            }
            if (busy == null && (pageLoading || (inFolio && !hasDocument && folioItems.isNotEmpty()))) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background.copy(alpha = 0.92f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("Loading...", color = MaterialTheme.colorScheme.onBackground)
                }
            }
            if (busy != null) {
                Box(
                    Modifier
                        .align(Alignment.Center)
                        .background(MaterialTheme.colorScheme.background.copy(alpha = 0.92f))
                        .padding(24.dp),
                ) {
                    Text(busy, color = MaterialTheme.colorScheme.onBackground)
                }
            }
            if (findOpen) {
                Row(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.background)
                        .interceptBarGestures()
                        .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars))
                        .padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BasicTextField(
                        value = query,
                        onValueChange = { query = it },
                        singleLine = true,
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        textStyle = androidx.compose.ui.text.TextStyle(
                            color = MaterialTheme.colorScheme.onBackground,
                            fontSize = 16.sp,
                        ),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { onQuery(query) }),
                        modifier = Modifier
                            .weight(1f)
                            .focusRequester(findFocus)
                            .padding(start = 8.dp, top = 8.dp, bottom = 8.dp, end = 0.dp),
                        decorationBox = { inner ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.weight(1f)) {
                                    if (query.isEmpty()) {
                                        Text("Find", color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f))
                                    }
                                    inner()
                                }
                                IconButton(
                                    onClick = { onQuery(query) },
                                    modifier = Modifier.size(40.dp),
                                ) {
                                    Icon(
                                        Icons.Outlined.Search,
                                        contentDescription = "Search",
                                        tint = MaterialTheme.colorScheme.onBackground.copy(
                                            alpha = if (query.isBlank()) 0.4f else 0.9f
                                        ),
                                        modifier = Modifier.size(20.dp),
                                    )
                                }
                            }
                        }
                    )
                    val hitCount = searchHits.size
                    val label = if (hitCount == 0) "0 / 0" else "${hitIndex + 1} / $hitCount"
                    Text(
                        label,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = if (hitCount > 0) 1f else 0.4f),
                        modifier = Modifier
                            .clickable(enabled = hitCount > 0) { hitsOpen = true }
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
                        Row {
                            IconButton(
                                onClick = onPrevHit,
                                enabled = hitCount > 0,
                                modifier = Modifier.size(36.dp),
                            ) {
                                Icon(
                                    Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onBackground,
                                )
                            }
                            IconButton(
                                onClick = onNextHit,
                                enabled = hitCount > 0,
                                modifier = Modifier.size(36.dp),
                            ) {
                                Icon(
                                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onBackground,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (tocOpen) {
        PapercutOverlay(
            title = "Table of Contents",
            onDismiss = { tocOpen = false },
        ) {
            LazyColumn(Modifier.fillMaxSize()) {
                items(outline) { item ->
                    val enabled = item.pageIndex >= 0
                    Text(
                        item.title,
                        color = Color(settings.fgColor).copy(alpha = if (enabled) 0.7f else 0.4f),
                        fontSize = 16.sp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = enabled) {
                                onGoToPageIndex(item.pageIndex, item.y)
                                tocOpen = false
                            }
                            .padding(start = (16 + item.depth * 16).dp, top = 12.dp, bottom = 12.dp, end = 16.dp),
                    )
                }
            }
        }
    }

    if (hitsOpen) {
        PapercutOverlay(
            title = "Matches",
            onDismiss = { hitsOpen = false },
        ) {
            LazyColumn(Modifier.fillMaxSize()) {
                itemsIndexed(searchHits) { index, hit ->
                    val current = index == hitIndex
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                onSelectHit(index)
                                hitsOpen = false
                            }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                    ) {
                        Text(
                            hit.snippet.ifBlank { "…" },
                            color = MaterialTheme.colorScheme.onBackground.copy(
                                alpha = if (current) 1f else 0.7f
                            ),
                            fontSize = 16.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            "Page ${hit.startPage + 1}",
                            color = MaterialTheme.colorScheme.onBackground.copy(
                                alpha = if (current) 0.7f else 0.45f
                            ),
                            fontSize = 12.sp,
                        )
                    }
                }
            }
        }
    }

    if (rearrangeOpen && selectedPages.isNotEmpty()) {
        RearrangePagesOverlay(
            pages = selectedPages,
            onDismiss = { rearrangeOpen = false },
            onChange = onSetSelectedPages,
        )
    }

    if (folioJumpOpen) {
        FolioJumpOverlay(
            items = folioItems,
            currentIndex = folioIndex,
            onDismiss = { folioJumpOpen = false },
            onJump = onFolioIndex,
            onManage = {
                folioJumpOpen = false
                onManageFolio()
            },
            onAdd = {
                folioJumpOpen = false
                onOpen()
            },
        )
    }

    if (bookmarksOpen) {
        val pos = readingPosition()
        BookmarkOverlay(
            bookmarks = bookmarks,
            pages = pages,
            currentPageIndex = pos.first,
            currentScrollX = pos.second,
            currentScrollY = pos.third,
            onDismiss = { bookmarksOpen = false },
            onJump = onJumpBookmark,
            onAdd = onAddBookmark,
            onUpdate = onUpdateBookmark,
            onDelete = onDeleteBookmarks,
            onReorder = onReorderBookmarks,
        )
    }

    if (zoomOpen) {
        var text by remember { mutableStateOf(currentZoomPercent.roundToInt().toString()) }
        PapercutAlertDialog(
            onDismissRequest = { zoomOpen = false },
            title = { Text("Zoom") },
            text = {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.filter { ch -> ch.isDigit() }.take(4) },
                    label = { Text("%") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val percent = text.toFloatOrNull()?.coerceIn(25f, 400f)
                        if (percent != null) onZoomPercent(percent)
                        zoomOpen = false
                    },
                ) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { zoomOpen = false }) { Text("Cancel") }
            },
        )
    }

    if (gotoOpen) {
        var text by remember { mutableStateOf(currentPage.toString()) }
        PapercutAlertDialog(
            onDismissRequest = { gotoOpen = false },
            title = { Text("Go to page") },
            text = {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.filter { ch -> ch.isDigit() }.take(6) },
                    label = { Text("Page") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val page = text.toIntOrNull()
                        if (page != null && page in 1..pageCount) {
                            onGoToPageIndex(page - 1, null)
                        }
                        gotoOpen = false
                    },
                ) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { gotoOpen = false }) { Text("Cancel") }
            },
        )
    }

    if (shareOpen) {
        PapercutAlertDialog(
            onDismissRequest = { shareOpen = false },
            title = { Text("Share") },
            text = {
                Column {
                    TextButton(
                        onClick = {
                            shareOpen = false
                            onShareFull()
                        },
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(horizontal = 0.dp),
                    ) {
                        Text("Full", modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Start)
                    }
                    TextButton(
                        onClick = {
                            shareOpen = false
                            if (isPdf) onSharePartial() else hint("Partial share needs a PDF")
                        },
                        enabled = isPdf,
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(horizontal = 0.dp),
                    ) {
                        Text("Partial", modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Start)
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { shareOpen = false }) { Text("Cancel") }
            },
        )
    }

    if (passwordNeeded) {
        var password by remember { mutableStateOf("") }
        LaunchedEffect(passwordNeeded) { password = "" }
        PapercutAlertDialog(
            onDismissRequest = onCancelPassword,
            title = { Text("Password") },
            text = {
                Column {
                    Text("This PDF is password protected")
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text("Password") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Password,
                            imeAction = ImeAction.Done,
                        ),
                        keyboardActions = KeyboardActions(
                            onDone = { if (password.isNotEmpty()) onSubmitPassword(password) },
                        ),
                    )
                    if (passwordWrong) {
                        Text(
                            "Wrong password",
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.75f),
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { if (password.isNotEmpty()) onSubmitPassword(password) },
                ) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = onCancelPassword) { Text("Cancel") }
            },
        )
    }
}

private val DrawerInset = 16.dp

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ModeTopBar(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .interceptBarGestures()
            .windowInsetsPadding(WindowInsets.statusBarsIgnoringVisibility)
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Back",
                tint = MaterialTheme.colorScheme.onBackground,
            )
        }
        Text(
            title,
            color = MaterialTheme.colorScheme.onBackground,
            fontSize = 16.sp,
            fontWeight = FontWeight.Normal,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        actions()
    }
}

@Composable
private fun DrawerItem(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    Text(
        label,
        color = MaterialTheme.colorScheme.onBackground.copy(alpha = if (enabled) 1f else 0.35f),
        fontSize = 16.sp,
        fontWeight = FontWeight.Normal,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = DrawerInset, vertical = 14.dp),
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChromeIcon(
    icon: ImageVector,
    hint: String,
    enabled: Boolean = true,
    selected: Boolean = false,
    struck: Boolean = false,
    onHint: (String) -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tint = MaterialTheme.colorScheme.onBackground.copy(
        alpha = when {
            !enabled -> 0.35f
            selected -> 1f
            else -> 0.85f
        },
    )
    Box(
        modifier
            .requiredSize(48.dp)
            .combinedClickable(
                enabled = enabled,
                onClick = onClick,
                onLongClick = { onHint(hint) },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = hint, tint = tint)
        if (struck) {
            Canvas(Modifier.size(22.dp)) {
                val stroke = 2.dp.toPx()
                drawLine(
                    color = tint,
                    start = Offset(stroke, size.height - stroke),
                    end = Offset(size.width - stroke, stroke),
                    strokeWidth = stroke,
                    cap = StrokeCap.Round,
                )
            }
        }
    }
}

@Composable
private fun PageSlider(
    currentPage: Int,
    pageCount: Int,
    enabled: Boolean,
    show: Boolean = true,
    labelEnabled: Boolean = enabled,
    onChange: (Int) -> Unit,
    onChangeFinished: () -> Unit,
    onLabelClick: () -> Unit,
) {
    if (!show) return
    val count = pageCount.coerceAtLeast(1)
    val page = currentPage.coerceIn(1, count)
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "$page / $count",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = if (enabled) 0.7f else 0.35f),
            modifier = Modifier
                .clickable(enabled = labelEnabled, onClick = onLabelClick)
                .padding(horizontal = 8.dp, vertical = 4.dp),
        )
        ThinGreySlider(
            value = page.toFloat(),
            onValueChange = { onChange(it.roundToInt().coerceIn(1, count)) },
            onValueChangeFinished = onChangeFinished,
            valueRange = 1f..count.toFloat(),
            enabled = enabled && pageCount > 1,
        )
    }
}

private fun Modifier.interceptBarGestures(): Modifier = pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) {
            awaitPointerEvent(PointerEventPass.Final)
        }
    }
}

private fun dispatchToView(
    view: PdfStripView,
    action: Int,
    position: Offset,
    downTime: Long,
) {
    val event = MotionEvent.obtain(
        downTime,
        android.os.SystemClock.uptimeMillis(),
        action,
        position.x,
        position.y,
        0,
    )
    view.dispatchTouchEvent(event)
    event.recycle()
}

private fun wrapCallbacks(
    existing: PdfStripView.Callbacks?,
    onSel: (String?) -> Unit,
    onPage: (Int, Int) -> Unit,
    onDoubleTap: () -> Unit,
    onPagePress: (Int) -> Unit = {},
): PdfStripView.Callbacks {
    return object : PdfStripView.Callbacks {
        override fun requestPage(index: Int, width: Int, urgent: Boolean) {
            existing?.requestPage(index, width, urgent)
        }

        override fun cancelRendersExcept(keep: Set<Int>) {
            existing?.cancelRendersExcept(keep)
        }

        override fun loadText(index: Int, done: (List<CharBox>) -> Unit) {
            existing?.loadText(index, done)
        }

        override fun requestLinks(index: Int) {
            existing?.requestLinks(index)
        }

        override fun onPinchEnd(percent: Float) {
            existing?.onPinchEnd(percent)
        }

        override fun onSelectionText(text: String?) {
            existing?.onSelectionText(text)
            onSel(text)
        }

        override fun onVisiblePage(index: Int, count: Int) {
            existing?.onVisiblePage(index, count)
            onPage(index, count)
        }

        override fun onDoubleTap() {
            onDoubleTap()
        }

        override fun onTapZoom(mode: ZoomMode, percent: Float) {
            existing?.onTapZoom(mode, percent)
        }

        override fun onLockSideScroll(on: Boolean) {
            existing?.onLockSideScroll(on)
        }

        override fun onLinkTapped(uri: String, isExternal: Boolean) {
            existing?.onLinkTapped(uri, isExternal)
        }

        override fun onPageLongPressed(index: Int) {
            onPagePress(index)
        }

        override fun onPageTapped(index: Int) {
            onPagePress(index)
        }

        override fun onFolioSwipe(delta: Int) {
            existing?.onFolioSwipe(delta)
        }
    }
}
