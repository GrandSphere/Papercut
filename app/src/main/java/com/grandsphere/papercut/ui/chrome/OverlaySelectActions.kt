package com.grandsphere.papercut.ui.chrome

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
            SymbolIcon("check_box_outline_blank", contentDescription = "Select", tint = tint)
        }
        return
    }
    IconButton(onClick = onSelectAllOrNone) {
        SymbolIcon(
            if (allSelected) "check_box_outline_blank" else "select_all",
            contentDescription = if (allSelected) "Select none" else "Select all",
            tint = tint,
        )
    }
    IconButton(onClick = onDeleteSelected, enabled = hasSelection) {
        SymbolIcon(
            "delete",
            contentDescription = "Delete",
            tint = tint.copy(alpha = if (hasSelection) 1f else 0.35f),
        )
    }
}
