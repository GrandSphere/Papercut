package com.grandsphere.papercut.ui.viewer

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.grandsphere.papercut.data.DocumentPaths
import com.grandsphere.papercut.data.FolioItem
import com.grandsphere.papercut.ui.chrome.PapercutOverlay

@Composable
fun FolioJumpOverlay(
    items: List<FolioItem>,
    currentIndex: Int,
    onDismiss: () -> Unit,
    onJump: (Int) -> Unit,
    onManage: () -> Unit,
    onAdd: () -> Unit,
) {
    PapercutOverlay(
        title = "Folio",
        onDismiss = onDismiss,
        bottomBar = {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onManage) {
                    Icon(
                        Icons.Outlined.Edit,
                        contentDescription = "Manage Folio",
                        tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.75f),
                    )
                }
                IconButton(onClick = onAdd) {
                    Icon(
                        Icons.Outlined.Add,
                        contentDescription = "Add",
                        tint = MaterialTheme.colorScheme.onBackground,
                    )
                }
            }
        },
    ) {
        val on = MaterialTheme.colorScheme.onBackground
        LazyColumn(Modifier.fillMaxSize()) {
            itemsIndexed(items, key = { _, item -> item.id }) { index, item ->
                val current = index == currentIndex
                val label = DocumentPaths.fromUriString(item.uri, item.displayName, item.displayPath)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable {
                            onJump(index)
                            onDismiss()
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
                            color = on.copy(alpha = if (current) 1f else 0.7f),
                            fontSize = 16.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (label.displayPath.isNotBlank()) {
                            Text(
                                label.displayPath,
                                color = on.copy(alpha = if (current) 0.7f else 0.45f),
                                fontSize = 12.sp,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}
