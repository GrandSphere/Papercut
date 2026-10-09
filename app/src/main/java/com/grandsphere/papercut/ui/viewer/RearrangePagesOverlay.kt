package com.grandsphere.papercut.ui.viewer

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.grandsphere.papercut.ui.chrome.PapercutOverlay
import kotlin.math.abs

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun RearrangePagesOverlay(
    pages: List<Int>,
    onDismiss: () -> Unit,
    onChange: (List<Int>) -> Unit,
) {
    var local by remember { mutableStateOf(pages) }
    LaunchedEffect(pages) { local = pages }
    val listState = rememberLazyListState()
    var draggingPage by remember { mutableStateOf<Int?>(null) }
    var fromIndex by remember { mutableIntStateOf(-1) }
    var insertIndex by remember { mutableIntStateOf(-1) }
    var dragOffsetY by remember { mutableFloatStateOf(0f) }
    var grabInItemY by remember { mutableFloatStateOf(0f) }
    var itemStartOffset by remember { mutableFloatStateOf(0f) }
    var rowHeightPx by remember { mutableFloatStateOf(0f) }

    fun publish(next: List<Int>) {
        local = next
        onChange(next)
    }

    fun finishDrag() {
        val from = fromIndex
        val to = insertIndex
        draggingPage = null
        fromIndex = -1
        insertIndex = -1
        dragOffsetY = 0f
        if (from < 0 || to < 0 || from == to) return
        val next = local.toMutableList()
        val moved = next.removeAt(from)
        next.add(to.coerceIn(0, next.size), moved)
        publish(next)
    }

    PapercutOverlay(
        title = "Rearrange pages",
        onDismiss = onDismiss,
    ) {
        val on = MaterialTheme.colorScheme.onBackground
        Box(Modifier.fillMaxSize()) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                state = listState,
                userScrollEnabled = draggingPage == null,
            ) {
                itemsIndexed(local, key = { _, page -> page }) { index, page ->
                    val hidden = fromIndex == index && draggingPage != null
                    val shift = when {
                        draggingPage == null || fromIndex < 0 -> 0f
                        fromIndex < insertIndex && index in (fromIndex + 1)..insertIndex -> -rowHeightPx
                        fromIndex > insertIndex && index in insertIndex until fromIndex -> rowHeightPx
                        else -> 0f
                    }
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .graphicsLayer {
                                translationY = if (hidden) 0f else shift
                                alpha = if (hidden) 0f else 1f
                            }
                            .onSizeChanged { if (it.height > 0) rowHeightPx = it.height.toFloat() }
                            .pointerInput(page, index) {
                                detectDragGestures(
                                    onDragStart = { offset ->
                                        val info = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }
                                        draggingPage = page
                                        fromIndex = index
                                        insertIndex = index
                                        grabInItemY = offset.y
                                        itemStartOffset = info?.offset?.toFloat() ?: 0f
                                        if (info != null) rowHeightPx = info.size.toFloat()
                                        dragOffsetY = 0f
                                    },
                                    onDragEnd = { finishDrag() },
                                    onDragCancel = { finishDrag() },
                                    onDrag = { change, dragAmount ->
                                        change.consume()
                                        dragOffsetY += dragAmount.y
                                        val fingerY = itemStartOffset + grabInItemY + dragOffsetY
                                        val over = listState.layoutInfo.visibleItemsInfo.minByOrNull { vis ->
                                            abs((vis.offset + vis.size / 2f) - fingerY)
                                        }?.index
                                        if (over != null) insertIndex = over
                                    },
                                )
                            }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "${index + 1}",
                            color = on.copy(alpha = 0.45f),
                            fontSize = 16.sp,
                            style = LocalTextStyle.current.copy(fontFeatureSettings = "tnum"),
                            modifier = Modifier
                                .widthIn(min = 22.dp)
                                .padding(end = 20.dp),
                        )
                        Text(
                            "page ${page + 1}",
                            color = on.copy(alpha = 0.7f),
                            fontSize = 16.sp,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = { publish(local.filterIndexed { i, _ -> i != index }) }) {
                            Icon(Icons.Outlined.Close, contentDescription = "Remove", tint = on)
                        }
                    }
                }
            }
            val dragged = local.getOrNull(fromIndex)
            if (dragged != null && draggingPage != null && fromIndex >= 0) {
                Row(
                    Modifier
                        .zIndex(2f)
                        .graphicsLayer {
                            translationY = itemStartOffset + dragOffsetY
                            scaleX = 1.03f
                            scaleY = 1.03f
                            shadowElevation = 18.dp.toPx()
                        }
                        .background(MaterialTheme.colorScheme.background)
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "${fromIndex + 1}",
                        color = on.copy(alpha = 0.45f),
                        fontSize = 16.sp,
                        style = LocalTextStyle.current.copy(fontFeatureSettings = "tnum"),
                        modifier = Modifier
                            .widthIn(min = 22.dp)
                            .padding(end = 20.dp),
                    )
                    Text("page ${dragged + 1}", color = on.copy(alpha = 0.7f), fontSize = 16.sp)
                }
            }
        }
    }
}
