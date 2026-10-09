package com.grandsphere.papercut.ui.chrome

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckBoxOutlineBlank
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable

@Composable
fun OverlaySelectActions(
    selecting: Boolean,
    allSelected: Boolean,
    hasSelection: Boolean,
    onEnterSelect: () -> Unit,
    onSelectAllOrNone: () -> Unit,
    onDeleteSelected: () -> Unit,
) {
    val tint = MaterialTheme.colorScheme.onBackground
    if (!selecting) {
        IconButton(onClick = onEnterSelect) {
            Icon(Icons.Outlined.CheckBoxOutlineBlank, contentDescription = "Select", tint = tint)
        }
        return
    }
    IconButton(onClick = onSelectAllOrNone) {
        Icon(
            if (allSelected) Icons.Outlined.CheckBoxOutlineBlank else Icons.Outlined.SelectAll,
            contentDescription = if (allSelected) "Select none" else "Select all",
            tint = tint,
        )
    }
    IconButton(onClick = onDeleteSelected, enabled = hasSelection) {
        Icon(
            Icons.Outlined.Delete,
            contentDescription = "Delete",
            tint = tint.copy(alpha = if (hasSelection) 1f else 0.35f),
        )
    }
}
