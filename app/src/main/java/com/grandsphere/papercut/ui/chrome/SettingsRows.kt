package com.grandsphere.papercut.ui.chrome

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ColourSettingRow(
    label: String,
    argb: Int,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = { onLongClick?.invoke() })
            .padding(vertical = 4.dp),
    ) {
        Box(
            Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(Color(argb))
                .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape),
        )
        Text(
            label,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
            fontSize = 16.sp,
            modifier = Modifier.padding(start = 12.dp),
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SettingsSwitchRow(
    label: String,
    checked: Boolean,
    onChecked: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onLongClick: (() -> Unit)? = null,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.fillMaxWidth(),
    ) {
        Text(
            label,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = if (enabled) 0.7f else 0.35f),
            fontSize = 16.sp,
            modifier = Modifier
                .weight(1f)
                .then(
                    if (onLongClick != null) {
                        Modifier.combinedClickable(onClick = {}, onLongClick = onLongClick)
                    } else {
                        Modifier
                    },
                ),
        )
        PapercutSwitch(checked, onChecked, enabled)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ColourSwitchRow(
    label: String,
    argb: Int,
    checked: Boolean,
    onChecked: (Boolean) -> Unit,
    onColourClick: () -> Unit,
    onColourLongClick: (() -> Unit)? = null,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Box(
            Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(Color(argb))
                .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
                .combinedClickable(onClick = onColourClick, onLongClick = { onColourLongClick?.invoke() }),
        )
        Text(
            label,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
            fontSize = 16.sp,
            modifier = Modifier
                .weight(1f)
                .padding(start = 12.dp),
        )
        PapercutSwitch(checked, onChecked)
    }
}

@Composable
fun SettingsTapRow(
    label: String,
    onClick: () -> Unit,
    value: String = "",
    onShare: (() -> Unit)? = null,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
    ) {
        Text(
            label,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
            fontSize = 16.sp,
            modifier = Modifier
                .weight(1f)
                .clickable(onClick = onClick),
        )
        if (value.isNotEmpty()) {
            Text(
                value,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.clickable(onClick = onClick),
            )
        }
        if (onShare != null) {
            val iconSize = with(LocalDensity.current) { (16.sp * 0.75f).toDp() }
            CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
                Icon(
                    Icons.Outlined.Share,
                    contentDescription = "Share",
                    tint = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier
                        .height(0.dp)
                        .wrapContentHeight(unbounded = true, align = Alignment.CenterVertically)
                        .padding(start = 8.dp)
                        .size(iconSize)
                        .clickable(onClick = onShare),
                )
            }
        }
    }
}

@Composable
private fun PapercutSwitch(checked: Boolean, onChecked: (Boolean) -> Unit, enabled: Boolean = true) {
    val action = MaterialTheme.colorScheme.secondaryContainer
    val on = MaterialTheme.colorScheme.onBackground
    Switch(
        checked = checked,
        onCheckedChange = onChecked,
        enabled = enabled,
        colors = SwitchDefaults.colors(
            checkedTrackColor = action,
            checkedThumbColor = on,
            checkedBorderColor = Color.Transparent,
            uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant,
            uncheckedThumbColor = on.copy(alpha = 0.7f),
            uncheckedBorderColor = MaterialTheme.colorScheme.outline,
        ),
    )
}
