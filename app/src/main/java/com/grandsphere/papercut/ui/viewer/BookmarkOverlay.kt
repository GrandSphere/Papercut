package com.grandsphere.papercut.ui.viewer

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CheckBox
import androidx.compose.material.icons.outlined.CheckBoxOutlineBlank
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.grandsphere.papercut.data.Bookmark
import com.grandsphere.papercut.pdf.PageInfo
import com.grandsphere.papercut.ui.chrome.OverlaySelectActions
import com.grandsphere.papercut.ui.chrome.PapercutAlertDialog
import com.grandsphere.papercut.ui.chrome.PapercutOverlay
import kotlin.math.abs
import kotlin.math.roundToInt

private const val QuickBookmarkName = "Quick Bookmark"
private const val MutedAlpha = 0.45f
private val LabelSize = 16.sp
private val PageSize = 12.sp

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BookmarkOverlay(
    bookmarks: List<Bookmark>,
    pages: List<PageInfo>,
    currentPageIndex: Int,
    currentScrollX: Float,
    currentScrollY: Float,
    onDismiss: () -> Unit,
    onJump: (Bookmark) -> Unit,
    onAdd: (String, Int, Float, Float) -> Unit,
    onUpdate: (Bookmark) -> Unit,
    onDelete: (List<Long>) -> Unit,
    onReorder: (List<Long>) -> Unit,
) {
    var managing by remember { mutableStateOf(false) }
    var selecting by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(setOf<Long>()) }
    var nameOpen by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Bookmark?>(null) }
    var local by remember { mutableStateOf(bookmarks) }
    val listState = rememberLazyListState()

    var draggingId by remember { mutableStateOf<Long?>(null) }
    var fromIndex by remember { mutableIntStateOf(-1) }
    var insertIndex by remember { mutableIntStateOf(-1) }
    var dragOffsetY by remember { mutableFloatStateOf(0f) }
    var grabInItemY by remember { mutableFloatStateOf(0f) }
    var itemStartOffset by remember { mutableFloatStateOf(0f) }
    var rowHeightPx by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(bookmarks) { local = bookmarks }
    var previousCount by remember { mutableStateOf(bookmarks.size) }
    LaunchedEffect(bookmarks.size) {
        if (bookmarks.size > previousCount && bookmarks.isNotEmpty()) {
            listState.animateScrollToItem(bookmarks.lastIndex)
        }
        previousCount = bookmarks.size
    }

    fun finishDrag() {
        if (draggingId != null && fromIndex in local.indices) {
            val next = local.toMutableList()
            val moved = next.removeAt(fromIndex)
            val to = insertIndex.coerceIn(0, next.size)
            next.add(to, moved)
            if (next.map { it.id } != local.map { it.id }) {
                local = next
                onReorder(next.map { it.id })
            }
        }
        draggingId = null
        fromIndex = -1
        insertIndex = -1
        dragOffsetY = 0f
    }

    fun exitModes() {
        if (selecting) {
            selecting = false
            selected = emptySet()
            return
        }
        if (managing) {
            managing = false
            selecting = false
            selected = emptySet()
            return
        }
        onDismiss()
    }

    PapercutOverlay(
        title = when {
            selecting -> "${selected.size} selected"
            managing -> "Manage bookmarks"
            else -> "Bookmarks"
        },
        onDismiss = { exitModes() },
        actions = {
            if (managing || selecting) {
                OverlaySelectActions(
                    selecting = selecting,
                    allSelected = local.isNotEmpty() && selected.size == local.size,
                    hasSelection = selected.isNotEmpty(),
                    onEnterSelect = { selecting = true },
                    onSelectAllOrNone = {
                        selected = if (selected.size == local.size) emptySet() else local.map { it.id }.toSet()
                    },
                    onDeleteSelected = {
                        onDelete(selected.toList())
                        selected = emptySet()
                        selecting = false
                    },
                )
            }
        },
        bottomBar = if (managing || selecting) {
            null
        } else {
            {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(
                        onClick = { managing = true },
                    ) {
                        Icon(
                            Icons.Outlined.Edit,
                            contentDescription = "Manage",
                            tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.75f),
                        )
                    }
                    Box(
                        Modifier
                            .size(48.dp)
                            .combinedClickable(
                                onClick = {
                                    onAdd(
                                        QuickBookmarkName,
                                        currentPageIndex,
                                        currentScrollX,
                                        currentScrollY,
                                    )
                                },
                                onLongClick = { nameOpen = true },
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Outlined.Add,
                            contentDescription = "Add bookmark",
                            tint = MaterialTheme.colorScheme.onBackground,
                        )
                    }
                }
            }
        },
    ) {
        val on = MaterialTheme.colorScheme.onBackground
        Box(Modifier.fillMaxSize()) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                state = listState,
                userScrollEnabled = draggingId == null,
            ) {
                itemsIndexed(local, key = { _, item -> item.id }) { index, item ->
                    val hidden = item.id == draggingId
                    val shift = when {
                        draggingId == null || fromIndex < 0 -> 0f
                        fromIndex < insertIndex && index in (fromIndex + 1)..insertIndex -> -rowHeightPx
                        fromIndex > insertIndex && index in insertIndex until fromIndex -> rowHeightPx
                        else -> 0f
                    }
                    BookmarkRow(
                        index = index,
                        item = item,
                        selecting = selecting,
                        managing = managing,
                        checked = item.id in selected,
                        hidden = hidden,
                        shiftY = shift,
                        onToggle = {
                            selected = if (item.id in selected) selected - item.id else selected + item.id
                        },
                        onOpen = {
                            when {
                                selecting -> {
                                    selected = if (item.id in selected) selected - item.id else selected + item.id
                                }
                                managing -> editing = item
                                else -> {
                                    onJump(item)
                                    onDismiss()
                                }
                            }
                        },
                        onDelete = { onDelete(listOf(item.id)) },
                        onHeight = { height ->
                            if (height > 0f && draggingId == null) rowHeightPx = height
                        },
                        onDragStart = { innerY ->
                            if (!managing) return@BookmarkRow
                            val info = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == item.id }
                            draggingId = item.id
                            fromIndex = local.indexOfFirst { it.id == item.id }
                            insertIndex = fromIndex
                            grabInItemY = innerY
                            itemStartOffset = info?.offset?.toFloat() ?: 0f
                            if (info != null) rowHeightPx = info.size.toFloat()
                            dragOffsetY = 0f
                        },
                        onDrag = { dy ->
                            dragOffsetY += dy
                            val fingerY = itemStartOffset + grabInItemY + dragOffsetY
                            val over = listState.layoutInfo.visibleItemsInfo.minByOrNull { vis ->
                                abs((vis.offset + vis.size / 2f) - fingerY)
                            }?.index
                            if (over != null) insertIndex = over
                        },
                        onDragEnd = { finishDrag() },
                        clickEnabled = draggingId == null,
                    )
                }
            }
            val dragged = local.find { it.id == draggingId }
            if (dragged != null && fromIndex >= 0) {
                BookmarkRowContent(
                    index = fromIndex,
                    item = dragged,
                    selecting = false,
                    managing = false,
                    checked = false,
                    onToggle = {},
                    onDelete = {},
                    modifier = Modifier
                        .zIndex(2f)
                        .graphicsLayer {
                            translationY = itemStartOffset + dragOffsetY
                            scaleX = 1.03f
                            scaleY = 1.03f
                            shadowElevation = 18.dp.toPx()
                            ambientShadowColor = on.copy(alpha = 0.35f)
                            spotShadowColor = on.copy(alpha = 0.45f)
                        }
                        .background(MaterialTheme.colorScheme.background),
                )
            }
        }
    }

    if (nameOpen) {
        var text by remember { mutableStateOf("") }
        PapercutAlertDialog(
            onDismissRequest = { nameOpen = false },
            title = { Text("Bookmark") },
            text = {
                Column {
                    Text(
                        "Page ${currentPageIndex + 1}",
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = MutedAlpha),
                    )
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        label = { Text("Name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val name = text.trim().ifBlank { QuickBookmarkName }
                        onAdd(name, currentPageIndex, currentScrollX, currentScrollY)
                        nameOpen = false
                    },
                ) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { nameOpen = false }) { Text("Cancel") }
            },
        )
    }

    val editTarget = editing
    if (editTarget != null) {
        BookmarkEditDialog(
            bookmark = editTarget,
            pages = pages,
            onDismiss = { editing = null },
            onSave = {
                onUpdate(it)
                editing = null
            },
        )
    }
}

@Composable
private fun BookmarkRow(
    index: Int,
    item: Bookmark,
    selecting: Boolean,
    managing: Boolean,
    checked: Boolean,
    hidden: Boolean,
    shiftY: Float,
    onToggle: () -> Unit,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
    onHeight: (Float) -> Unit,
    onDragStart: (Float) -> Unit,
    onDrag: (Float) -> Unit,
    onDragEnd: () -> Unit,
    clickEnabled: Boolean,
) {
    BookmarkRowContent(
        index = index,
        item = item,
        selecting = selecting,
        managing = managing,
        checked = checked,
        onToggle = onToggle,
        onDelete = onDelete,
        modifier = Modifier
            .onSizeChanged { onHeight(it.height.toFloat()) }
            .clickable(enabled = clickEnabled, onClick = onOpen)
            .pointerInput(item.id, managing, selecting) {
                if (!managing || selecting) return@pointerInput
                detectDragGesturesAfterLongPress(
                    onDragStart = { offset -> onDragStart(offset.y) },
                    onDragEnd = onDragEnd,
                    onDragCancel = onDragEnd,
                    onDrag = { change, amount ->
                        change.consume()
                        onDrag(amount.y)
                    },
                )
            }
            .graphicsLayer {
                translationY = shiftY
                alpha = if (hidden) 0f else 1f
            },
    )
}

@Composable
private fun BookmarkRowContent(
    index: Int,
    item: Bookmark,
    selecting: Boolean,
    managing: Boolean,
    checked: Boolean,
    onToggle: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val on = MaterialTheme.colorScheme.onBackground
    val muted = on.copy(alpha = MutedAlpha)
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        if (selecting) {
            Icon(
                if (checked) Icons.Outlined.CheckBox else Icons.Outlined.CheckBoxOutlineBlank,
                contentDescription = null,
                tint = on,
                modifier = Modifier
                    .padding(end = 8.dp)
                    .align(Alignment.CenterVertically)
                    .clickable(onClick = onToggle),
            )
        }
        Text(
            "${index + 1}",
            color = muted,
            fontSize = LabelSize,
            style = LocalTextStyle.current.copy(fontFeatureSettings = "tnum"),
            modifier = Modifier
                .widthIn(min = 22.dp)
                .padding(end = 20.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(
                item.name,
                color = on.copy(alpha = 0.7f),
                fontSize = LabelSize,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "Page ${item.pageIndex + 1}",
                color = muted,
                fontSize = PageSize,
                maxLines = 1,
            )
        }
        if (managing && !selecting) {
            IconButton(
                onClick = onDelete,
                modifier = Modifier
                    .size(40.dp)
                    .align(Alignment.CenterVertically),
            ) {
                Icon(Icons.Outlined.Close, contentDescription = "Delete", tint = on)
            }
        }
    }
}

@Composable
private fun BookmarkEditDialog(
    bookmark: Bookmark,
    pages: List<PageInfo>,
    onDismiss: () -> Unit,
    onSave: (Bookmark) -> Unit,
) {
    val page = pages.getOrNull(bookmark.pageIndex)
    val width = page?.width?.takeIf { it > 0f } ?: 1f
    val height = page?.height?.takeIf { it > 0f } ?: 1f
    val top = pageTop(pages, bookmark.pageIndex)
    var name by remember { mutableStateOf(bookmark.name) }
    var pageText by remember { mutableStateOf((bookmark.pageIndex + 1).toString()) }
    var hText by remember { mutableStateOf(formatPercent(100f * bookmark.scrollXPdf / width)) }
    var vText by remember { mutableStateOf(formatPercent(100f * (bookmark.scrollYPdf - top) / height)) }

    PapercutAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit bookmark") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = pageText,
                    onValueChange = { pageText = it.filter { ch -> ch.isDigit() }.take(6) },
                    label = { Text("Page") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                OutlinedTextField(
                    value = hText,
                    onValueChange = { hText = it.filter { ch -> ch.isDigit() || ch == '.' || ch == '-' }.take(8) },
                    label = { Text("Horizontal %") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                OutlinedTextField(
                    value = vText,
                    onValueChange = { vText = it.filter { ch -> ch.isDigit() || ch == '.' || ch == '-' }.take(8) },
                    label = { Text("Vertical %") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val pageIndex = ((pageText.toIntOrNull() ?: (bookmark.pageIndex + 1)) - 1)
                        .coerceIn(0, (pages.size - 1).coerceAtLeast(0))
                    val next = pages.getOrNull(pageIndex)
                    val w = next?.width?.takeIf { it > 0f } ?: 1f
                    val h = next?.height?.takeIf { it > 0f } ?: 1f
                    val t = pageTop(pages, pageIndex)
                    val hx = (hText.toFloatOrNull() ?: 0f) / 100f
                    val vy = (vText.toFloatOrNull() ?: 0f) / 100f
                    onSave(
                        bookmark.copy(
                            name = name.trim().ifBlank { QuickBookmarkName },
                            pageIndex = pageIndex,
                            scrollXPdf = hx * w,
                            scrollYPdf = t + vy * h,
                        ),
                    )
                },
            ) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

private fun pageTop(pages: List<PageInfo>, index: Int): Float {
    var y = 0f
    val last = index.coerceIn(0, pages.size)
    for (i in 0 until last) y += pages[i].height
    return y
}

private fun formatPercent(value: Float): String {
    val rounded = (value * 10f).roundToInt() / 10f
    return if (rounded == rounded.toInt().toFloat()) rounded.toInt().toString() else rounded.toString()
}
