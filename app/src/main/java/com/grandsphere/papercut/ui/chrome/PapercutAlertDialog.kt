package com.grandsphere.papercut.ui.chrome

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val DialogShape = RoundedCornerShape(12.dp)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PapercutAlertDialog(
    onDismissRequest: () -> Unit,
    title: @Composable (() -> Unit)? = null,
    text: @Composable (() -> Unit)? = null,
    confirmButton: @Composable () -> Unit,
    dismissButton: @Composable (() -> Unit)? = null,
) {
    val glow = MaterialTheme.colorScheme.onBackground
    BasicAlertDialog(onDismissRequest = onDismissRequest) {
        Box(
            Modifier
                .padding(8.dp)
                .drawBehind {
                    val extra = 1.5.dp.toPx()
                    drawRoundRect(
                        color = glow.copy(alpha = 0.005f),
                        topLeft = Offset(-extra, -extra),
                        size = Size(size.width + extra * 2f, size.height + extra * 2f),
                        cornerRadius = CornerRadius(12.dp.toPx() + extra * 0.3f),
                    )
                }
                .shadow(
                    elevation = 6.dp,
                    shape = DialogShape,
                    clip = false,
                    ambientColor = glow.copy(alpha = 0.03f),
                    spotColor = glow.copy(alpha = 0.018f),
                )
                .clip(DialogShape)
                .background(MaterialTheme.colorScheme.background),
        ) {
            Column(
                Modifier.padding(start = 24.dp, end = 24.dp, top = 20.dp, bottom = 10.dp),
            ) {
                if (title != null) {
                    CompositionLocalProvider(LocalContentColor provides glow) {
                        ProvideTextStyle(
                            TextStyle(
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Normal,
                                color = glow,
                            ),
                            title,
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                }
                if (text != null) {
                    CompositionLocalProvider(LocalContentColor provides glow) {
                        ProvideTextStyle(MaterialTheme.typography.bodyMedium, text)
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    dismissButton?.invoke()
                    confirmButton()
                }
            }
        }
    }
}
