package com.grandsphere.papercut.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckBox
import androidx.compose.material.icons.outlined.CheckBoxOutlineBlank
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.grandsphere.papercut.data.StoredEntry
import com.grandsphere.papercut.ui.chrome.OverlaySelectActions
import com.grandsphere.papercut.ui.chrome.PapercutOverlay

@Composable
fun StoredFilesOverlay(
    entries: List<StoredEntry>,
    onDismiss: () -> Unit,
    onOpen: (StoredEntry) -> Unit,
    onShare: (StoredEntry) -> Unit,
    onDelete: (List<StoredEntry>) -> Unit,
) {
    var selecting by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(setOf<String>()) }

    fun exitModes() {
        if (selecting) {
            selecting = false
            selected = emptySet()
        } else {
            onDismiss()
        }
    }

    PapercutOverlay(
        title = if (selecting) "${selected.size} selected" else "Stored files",
        onDismiss = { exitModes() },
        actions = {
            OverlaySelectActions(
                selecting = selecting,
                allSelected = entries.isNotEmpty() && selected.size == entries.size,
                hasSelection = selected.isNotEmpty(),
                onEnterSelect = { selecting = true },
                onSelectAllOrNone = {
                    selected = if (selected.size == entries.size) {
                        emptySet()
                    } else {
                        entries.map { it.path }.toSet()
                    }
                },
                onDeleteSelected = {
                    onDelete(entries.filter { it.path in selected })
                    selected = emptySet()
                    selecting = false
                },
            )
        },
    ) {
        val on = MaterialTheme.colorScheme.onBackground
        if (entries.isEmpty()) {
            Text(
                "No stored files",
                color = on.copy(alpha = 0.7f),
                modifier = Modifier.padding(16.dp),
            )
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(entries, key = { it.path }) { entry ->
                    val checked = entry.path in selected
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                if (selecting) {
                                    selected = if (checked) selected - entry.path else selected + entry.path
                                } else {
                                    onOpen(entry)
                                }
                            }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                entry.title,
                                color = on.copy(alpha = 0.7f),
                                fontSize = 16.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                entry.status,
                                color = on.copy(alpha = 0.45f),
                                fontSize = 12.sp,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        if (!selecting) {
                            IconButton(onClick = { onShare(entry) }) {
                                Icon(Icons.Outlined.Share, contentDescription = "Share", tint = on)
                            }
                            IconButton(onClick = { onDelete(listOf(entry)) }) {
                                Icon(Icons.Outlined.Close, contentDescription = "Delete", tint = on)
                            }
                        } else {
                            Icon(
                                if (checked) Icons.Outlined.CheckBox else Icons.Outlined.CheckBoxOutlineBlank,
                                contentDescription = null,
                                tint = on,
                                modifier = Modifier.padding(horizontal = 12.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}
