package com.grandsphere.papercut.ui.viewer

import android.net.Uri
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Share
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.grandsphere.papercut.data.DocumentPaths
import com.grandsphere.papercut.ui.chrome.PapercutOverlay
import kotlin.math.abs

data class CombineEntry(
    val uri: Uri,
    val displayName: String,
    val displayPath: String,
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CombineOverlay(
    entries: List<CombineEntry>,
    onDismiss: () -> Unit,
    onChange: (List<CombineEntry>) -> Unit,
    onShare: () -> Unit,
    onExport: () -> Unit,
    onAdd: () -> Unit,
) {
    var local by remember { mutableStateOf(entries) }
    LaunchedEffect(entries) { local = entries }
    val listState = rememberLazyListState()
    var draggingUri by remember { mutableStateOf<String?>(null) }
    var fromIndex by remember { mutableIntStateOf(-1) }
    var insertIndex by remember { mutableIntStateOf(-1) }
    var dragOffsetY by remember { mutableFloatStateOf(0f) }
    var grabInItemY by remember { mutableFloatStateOf(0f) }
    var itemStartOffset by remember { mutableFloatStateOf(0f) }
    var rowHeightPx by remember { mutableFloatStateOf(0f) }

    fun publish(next: List<CombineEntry>) {
        local = next
        onChange(next)
    }

    fun finishDrag() {
        val from = fromIndex
        val to = insertIndex
        draggingUri = null
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
        title = "Combine",
        onDismiss = onDismiss,
        actions = {
            val on = MaterialTheme.colorScheme.onBackground
            IconButton(onClick = onAdd) {
                Icon(Icons.Outlined.Add, contentDescription = "Add", tint = on)
            }
            IconButton(onClick = onExport, enabled = local.isNotEmpty()) {
                Icon(
                    Icons.Outlined.Save,
                    contentDescription = "Export",
                    tint = on.copy(alpha = if (local.isNotEmpty()) 1f else 0.35f),
                )
            }
            IconButton(onClick = onShare, enabled = local.isNotEmpty()) {
                Icon(
                    Icons.Outlined.Share,
                    contentDescription = "Share",
                    tint = on.copy(alpha = if (local.isNotEmpty()) 1f else 0.35f),
                )
            }
        },
    ) {
        val on = MaterialTheme.colorScheme.onBackground
        Box(Modifier.fillMaxSize()) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                state = listState,
                userScrollEnabled = draggingUri == null,
            ) {
                itemsIndexed(local, key = { _, item -> item.uri.toString() }) { index, item ->
                    val key = item.uri.toString()
                    val hidden = draggingUri == key
                    val shift = when {
                        draggingUri == null || fromIndex < 0 -> 0f
                        fromIndex < insertIndex && index in (fromIndex + 1)..insertIndex -> -rowHeightPx
                        fromIndex > insertIndex && index in insertIndex until fromIndex -> rowHeightPx
                        else -> 0f
                    }
                    val label = remember(item.uri, item.displayName, item.displayPath) {
                        DocumentPaths.fromUriString(item.uri.toString(), item.displayName, item.displayPath)
                    }
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .graphicsLayer {
                                translationY = if (hidden) 0f else shift
                                alpha = if (hidden) 0f else 1f
                            }
                            .onSizeChanged { if (it.height > 0) rowHeightPx = it.height.toFloat() }
                            .pointerInput(item.uri) {
                                detectDragGestures(
                                    onDragStart = { offset ->
                                        val info = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == key }
                                        draggingUri = key
                                        fromIndex = local.indexOfFirst { it.uri.toString() == key }
                                        insertIndex = fromIndex
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
                        verticalAlignment = Alignment.Top,
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
                        Column(Modifier.weight(1f)) {
                            Text(
                                label.displayName,
                                color = on.copy(alpha = 0.7f),
                                fontSize = 16.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (label.displayPath.isNotBlank()) {
                                Text(
                                    label.displayPath,
                                    color = on.copy(alpha = 0.45f),
                                    fontSize = 12.sp,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        IconButton(
                            onClick = { publish(local.filter { it.uri != item.uri }) },
                            modifier = Modifier.align(Alignment.CenterVertically),
                        ) {
                            Icon(Icons.Outlined.Close, contentDescription = "Remove", tint = on)
                        }
                    }
                }
            }
            val dragged = local.find { it.uri.toString() == draggingUri }
            if (dragged != null && fromIndex >= 0) {
                val label = DocumentPaths.fromUriString(dragged.uri.toString(), dragged.displayName, dragged.displayPath)
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
                    verticalAlignment = Alignment.Top,
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
                    Column(Modifier.weight(1f)) {
                        Text(label.displayName, color = on.copy(alpha = 0.7f), fontSize = 16.sp, maxLines = 1)
                        if (label.displayPath.isNotBlank()) {
                            Text(label.displayPath, color = on.copy(alpha = 0.45f), fontSize = 12.sp, maxLines = 2)
                        }
                    }
                }
            }
        }
    }
}
