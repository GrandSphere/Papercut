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
import com.grandsphere.papercut.data.DocumentPaths
import com.grandsphere.papercut.data.PdfDocument
import com.grandsphere.papercut.ui.chrome.OverlaySelectActions
import com.grandsphere.papercut.ui.chrome.PapercutOverlay

@Composable
fun ManageDocumentsOverlay(
    documents: List<PdfDocument>,
    onDismiss: () -> Unit,
    onDelete: (List<String>) -> Unit,
) {
    var selecting by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(setOf<String>()) }

    fun back() {
        if (selecting) {
            selecting = false
            selected = emptySet()
        } else {
            onDismiss()
        }
    }

    PapercutOverlay(
        title = if (selecting) "${selected.size} selected" else "Manage bookmarks",
        onDismiss = { back() },
        actions = {
            OverlaySelectActions(
                selecting = selecting,
                allSelected = documents.isNotEmpty() && selected.size == documents.size,
                hasSelection = selected.isNotEmpty(),
                onEnterSelect = { selecting = true },
                onSelectAllOrNone = {
                    selected = if (selected.size == documents.size) emptySet() else documents.map { it.uri }.toSet()
                },
                onDeleteSelected = {
                    onDelete(selected.toList())
                    selected = emptySet()
                    selecting = false
                },
            )
        },
    ) {
        LazyColumn(Modifier.fillMaxSize()) {
            items(documents, key = { it.uri }) { doc ->
                val checked = doc.uri in selected
                val label = remember(doc.uri, doc.displayName, doc.displayPath) {
                    DocumentPaths.fromUriString(doc.uri, doc.displayName, doc.displayPath)
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable {
                            if (selecting) {
                                selected = if (checked) selected - doc.uri else selected + doc.uri
                            }
                        }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (selecting) {
                        Icon(
                            if (checked) Icons.Outlined.CheckBox else Icons.Outlined.CheckBoxOutlineBlank,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onBackground,
                            modifier = Modifier.padding(end = 8.dp),
                        )
                    }
                    Column(Modifier.weight(1f)) {
                        Text(
                            label.displayName,
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                            fontSize = 16.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (label.displayPath.isNotBlank()) {
                            Text(
                                label.displayPath,
                                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.45f),
                                fontSize = 12.sp,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    if (!selecting) {
                        IconButton(onClick = { onDelete(listOf(doc.uri)) }) {
                            Icon(
                                Icons.Outlined.Close,
                                contentDescription = "Delete",
                                tint = MaterialTheme.colorScheme.onBackground,
                            )
                        }
                    }
                }
            }
        }
    }
}
