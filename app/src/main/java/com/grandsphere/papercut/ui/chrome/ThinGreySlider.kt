package com.grandsphere.papercut.ui.chrome

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

private val TrackGrey = Color(0xFF5C5C5C)

@Composable
fun ThinGreySlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    enabled: Boolean = true,
    onValueChangeFinished: (() -> Unit)? = null,
) {
    val span = (valueRange.endInclusive - valueRange.start).coerceAtLeast(0.0001f)
    val fraction = ((value - valueRange.start) / span).coerceIn(0f, 1f)
    val color = TrackGrey.copy(alpha = if (enabled) 1f else 0.38f)
    val density = LocalDensity.current
    val thumbPx = with(density) { 12.dp.toPx() }

    fun valueAt(x: Float, width: Float): Float {
        val travel = (width - thumbPx).coerceAtLeast(1f)
        val f = ((x - thumbPx / 2f) / travel).coerceIn(0f, 1f)
        return valueRange.start + f * span
    }

    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .height(24.dp)
            .pointerInput(enabled, valueRange) {
                if (!enabled) return@pointerInput
                detectTapGestures { offset ->
                    onValueChange(valueAt(offset.x, size.width.toFloat()))
                    onValueChangeFinished?.invoke()
                }
            }
            .pointerInput(enabled, valueRange) {
                if (!enabled) return@pointerInput
                detectHorizontalDragGestures(
                    onDragEnd = { onValueChangeFinished?.invoke() },
                    onDragCancel = { onValueChangeFinished?.invoke() },
                    onHorizontalDrag = { change, _ ->
                        change.consume()
                        onValueChange(valueAt(change.position.x, size.width.toFloat()))
                    },
                )
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(2.dp)
                .background(color, RoundedCornerShape(1.dp)),
        )
        val travel = (maxWidth - 12.dp).coerceAtLeast(0.dp)
        Box(
            Modifier
                .offset(x = travel * fraction)
                .size(12.dp)
                .background(color, CircleShape),
        )
    }
}
