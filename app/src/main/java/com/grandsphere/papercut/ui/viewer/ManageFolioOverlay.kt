package com.grandsphere.papercut.ui.viewer

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckBox
import androidx.compose.material.icons.outlined.CheckBoxOutlineBlank
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.grandsphere.papercut.data.DocumentPaths
import com.grandsphere.papercut.data.FolioItem
import com.grandsphere.papercut.ui.chrome.OverlaySelectActions
import com.grandsphere.papercut.ui.chrome.PapercutOverlay
import kotlin.math.abs

private const val MutedAlpha = 0.45f
private val LabelSize = 16.sp
private val PathSize = 12.sp

@Composable
fun ManageFolioOverlay(
    items: List<FolioItem>,
    onDismiss: () -> Unit,
    onDelete: (List<Long>) -> Unit,
    onReorder: (List<Long>) -> Unit,
    onToggleHidden: (List<Long>, Boolean) -> Unit,
    onSetStart: (Long) -> Unit,
) {
    var selecting by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(setOf<Long>()) }
    var local by remember { mutableStateOf(items) }
    val listState = rememberLazyListState()
    var draggingId by remember { mutableStateOf<Long?>(null) }
    var fromIndex by remember { mutableIntStateOf(-1) }
    var insertIndex by remember { mutableIntStateOf(-1) }
    var dragOffsetY by remember { mutableFloatStateOf(0f) }
    var dragOffsetX by remember { mutableFloatStateOf(0f) }
    var grabInItemY by remember { mutableFloatStateOf(0f) }
    var itemStartOffset by remember { mutableFloatStateOf(0f) }
    var rowHeightPx by remember { mutableFloatStateOf(0f) }
    var swipeId by remember { mutableStateOf<Long?>(null) }
    val startSlop = with(LocalDensity.current) { 48.dp.toPx() }

    LaunchedEffect(items) { local = items }

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
        dragOffsetX = 0f
        swipeId = null
    }

    fun exitModes() {
        if (selecting) {
            selecting = false
            selected = emptySet()
            return
        }
        onDismiss()
    }

    PapercutOverlay(
        title = if (selecting) "${selected.size} selected" else "Manage Folio",
        onDismiss = { exitModes() },
        actions = {
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
        },
    ) {
        val on = MaterialTheme.colorScheme.onBackground
        Box(Modifier.fillMaxSize()) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                state = listState,
                userScrollEnabled = draggingId == null && swipeId == null,
            ) {
                itemsIndexed(local, key = { _, item -> item.id }) { index, item ->
                    val hiddenRow = item.id == draggingId || item.id == swipeId
                    val shift = when {
                        draggingId == null || fromIndex < 0 -> 0f
                        fromIndex < insertIndex && index in (fromIndex + 1)..insertIndex -> -rowHeightPx
                        fromIndex > insertIndex && index in insertIndex until fromIndex -> rowHeightPx
                        else -> 0f
                    }
                    FolioRow(
                        index = index,
                        item = item,
                        selecting = selecting,
                        checked = item.id in selected,
                        hiddenRow = hiddenRow,
                        shiftY = shift,
                        onToggle = {
                            selected = if (item.id in selected) selected - item.id else selected + item.id
                        },
                        onHide = { onToggleHidden(listOf(item.id), !item.hidden) },
                        onDelete = { onDelete(listOf(item.id)) },
                        onHeight = { height ->
                            if (height > 0f && draggingId == null) rowHeightPx = height
                        },
                        onDragStart = { innerY ->
                            if (selecting) return@FolioRow
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
                        onSwipeLift = { innerY ->
                            if (selecting) return@FolioRow
                            val info = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == item.id }
                            swipeId = item.id
                            itemStartOffset = info?.offset?.toFloat() ?: 0f
                            grabInItemY = innerY
                            dragOffsetX = 0f
                            if (info != null) rowHeightPx = info.size.toFloat()
                        },
                        onSwipe = { dx ->
                            dragOffsetX = (dragOffsetX + dx).coerceAtLeast(0f)
                        },
                        onSwipeEnd = { committed ->
                            if (committed) onSetStart(item.id)
                            swipeId = null
                            dragOffsetX = 0f
                        },
                        startSlop = startSlop,
                        clickEnabled = draggingId == null && swipeId == null,
                    )
                }
            }
            val dragged = local.find { it.id == draggingId }
            if (dragged != null && fromIndex >= 0) {
                FolioRowContent(
                    index = fromIndex,
                    item = dragged,
                    selecting = false,
                    checked = false,
                    onToggle = {},
                    onHide = {},
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
            val swiped = local.find { it.id == swipeId }
            if (swiped != null) {
                val swipeIndex = local.indexOfFirst { it.id == swiped.id }
                FolioRowContent(
                    index = swipeIndex.coerceAtLeast(0),
                    item = swiped,
                    selecting = false,
                    checked = false,
                    onToggle = {},
                    onHide = {},
                    onDelete = {},
                    modifier = Modifier
                        .zIndex(2f)
                        .graphicsLayer {
                            translationX = dragOffsetX
                            translationY = itemStartOffset
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
}

@Composable
private fun FolioRow(
    index: Int,
    item: FolioItem,
    selecting: Boolean,
    checked: Boolean,
    hiddenRow: Boolean,
    shiftY: Float,
    onToggle: () -> Unit,
    onHide: () -> Unit,
    onDelete: () -> Unit,
    onHeight: (Float) -> Unit,
    onDragStart: (Float) -> Unit,
    onDrag: (Float) -> Unit,
    onDragEnd: () -> Unit,
    onSwipeLift: (Float) -> Unit,
    onSwipe: (Float) -> Unit,
    onSwipeEnd: (Boolean) -> Unit,
    startSlop: Float,
    clickEnabled: Boolean,
) {
    var axis by remember(item.id) { mutableIntStateOf(0) }
    var totalX by remember(item.id) { mutableFloatStateOf(0f) }
    var grabY by remember(item.id) { mutableFloatStateOf(0f) }
    FolioRowContent(
        index = index,
        item = item,
        selecting = selecting,
        checked = checked,
        onToggle = onToggle,
        onHide = onHide,
        onDelete = onDelete,
        modifier = Modifier
            .onSizeChanged { onHeight(it.height.toFloat()) }
            .clickable(enabled = clickEnabled && selecting, onClick = onToggle)
            .pointerInput(item.id, selecting) {
                if (selecting) return@pointerInput
                detectDragGestures(
                    onDragStart = { offset ->
                        axis = 0
                        totalX = 0f
                        grabY = offset.y
                    },
                    onDragEnd = {
                        if (axis == 2) onSwipeEnd(totalX > startSlop)
                        if (axis == 1) onDragEnd()
                        axis = 0
                        totalX = 0f
                    },
                    onDragCancel = {
                        if (axis == 2) onSwipeEnd(false)
                        if (axis == 1) onDragEnd()
                        axis = 0
                        totalX = 0f
                    },
                    onDrag = { change, amount ->
                        change.consume()
                        if (axis == 0) {
                            axis = if (abs(amount.x) > abs(amount.y)) 2 else 1
                            if (axis == 1) onDragStart(grabY)
                            if (axis == 2) onSwipeLift(grabY)
                        }
                        if (axis == 1) {
                            onDrag(amount.y)
                        } else {
                            totalX += amount.x
                            onSwipe(amount.x)
                        }
                    },
                )
            }
            .graphicsLayer {
                translationY = shiftY
                alpha = if (hiddenRow) 0f else 1f
            },
    )
}

@Composable
private fun FolioRowContent(
    index: Int,
    item: FolioItem,
    selecting: Boolean,
    checked: Boolean,
    onToggle: () -> Unit,
    onHide: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val on = MaterialTheme.colorScheme.onBackground
    val muted = on.copy(alpha = MutedAlpha)
    val label = DocumentPaths.fromUriString(item.uri, item.displayName, item.displayPath)
    val titleAlpha = if (item.hidden) 0.4f else 0.7f
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
        Column(
            Modifier
                .widthIn(min = 22.dp)
                .padding(end = 20.dp),
        ) {
            Text(
                "${index + 1}",
                color = muted,
                fontSize = LabelSize,
                style = LocalTextStyle.current.copy(fontFeatureSettings = "tnum"),
            )
            Text(
                if (item.isStartItem) "*" else " ",
                color = on.copy(alpha = if (item.isStartItem) 0.7f else 0f),
                fontSize = PathSize,
            )
        }
        Column(Modifier.weight(1f)) {
            Text(
                label.displayName,
                color = on.copy(alpha = titleAlpha),
                fontSize = LabelSize,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                label.displayPath.ifBlank { item.uri },
                color = muted,
                fontSize = PathSize,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (!selecting) {
            IconButton(
                onClick = onHide,
                modifier = Modifier
                    .size(40.dp)
                    .align(Alignment.CenterVertically),
            ) {
                Icon(
                    Icons.Outlined.VisibilityOff,
                    contentDescription = if (item.hidden) "Show" else "Hide",
                    tint = on.copy(alpha = if (item.hidden) 0.35f else 0.85f),
                )
            }
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
