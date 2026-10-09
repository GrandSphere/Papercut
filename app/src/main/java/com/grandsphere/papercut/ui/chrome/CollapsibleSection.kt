package com.grandsphere.papercut.ui.chrome

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CollapsibleSection(
    title: String,
    hint: String,
    startExpanded: Boolean = false,
    bubbleColor: Color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
    content: @Composable () -> Unit,
) {
    var expanded by rememberSaveable(title) { mutableStateOf(startExpanded) }
    var showHint by remember { mutableStateOf(false) }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = bubbleColor,
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .semantics {
                        stateDescription = if (expanded) "Expanded" else "Collapsed"
                        customActions = listOf(
                            CustomAccessibilityAction("Hint") {
                                showHint = true
                                true
                            },
                        )
                    }
                    .combinedClickable(
                        onClick = { expanded = !expanded },
                        onLongClick = { showHint = true },
                    )
                    .padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    title,
                    fontSize = 18.sp,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (expanded) "Collapse" else "Expand",
                    tint = MaterialTheme.colorScheme.onBackground,
                )
            }
            AnimatedVisibility(expanded) {
                Column(
                    Modifier.padding(bottom = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    content = { content() },
                )
            }
        }
    }
    if (showHint) {
        PapercutAlertDialog(
            onDismissRequest = { showHint = false },
            title = { Text(title) },
            text = { Text(hint) },
            confirmButton = {
                TextButton(onClick = { showHint = false }) { Text("OK") }
            },
        )
    }
}
