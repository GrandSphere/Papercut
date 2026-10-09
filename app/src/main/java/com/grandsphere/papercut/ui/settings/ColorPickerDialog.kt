package com.grandsphere.papercut.ui.settings

import android.graphics.Color as AndroidColor
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import com.grandsphere.papercut.ui.chrome.PapercutAlertDialog
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp

@Composable
fun ColorPickerDialog(
    title: String,
    initialArgb: Int,
    onConfirm: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val hsv = remember(initialArgb) {
        val out = FloatArray(3)
        AndroidColor.colorToHSV(initialArgb, out)
        out
    }
    var hue by remember { mutableFloatStateOf(hsv[0]) }
    var sat by remember { mutableFloatStateOf(hsv[1]) }
    var value by remember { mutableFloatStateOf(hsv[2]) }
    var hexText by remember { mutableStateOf(formatHex(initialArgb)) }
    val current = Color(AndroidColor.HSVToColor(floatArrayOf(hue, sat, value)))

    fun applyFromPicker(h: Float, s: Float, v: Float) {
        hue = h
        sat = s
        value = v
        hexText = formatHex(AndroidColor.HSVToColor(floatArrayOf(h, s, v)))
    }

    PapercutAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                Box(
                    Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(current)
                        .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape),
                )
                Spacer(Modifier.height(12.dp))
                SvSquare(
                    hue = hue,
                    saturation = sat,
                    value = value,
                    onChange = { s, v -> applyFromPicker(hue, s, v) },
                )
                Spacer(Modifier.height(12.dp))
                HueBar(hue = hue, onHue = { applyFromPicker(it, sat, value) })
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = hexText,
                    onValueChange = { raw ->
                        hexText = raw
                        val parsed = parseHex(raw) ?: return@OutlinedTextField
                        val out = FloatArray(3)
                        AndroidColor.colorToHSV(parsed, out)
                        hue = out[0]
                        sat = out[1]
                        value = out[2]
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Characters,
                    ),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(current.toArgb()) }) { Text("OK") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

private fun Char.isHexChar(): Boolean =
    this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

private fun formatHex(argb: Int): String = "#%06X".format(argb and 0xFFFFFF)

private fun parseHex(raw: String): Int? {
    val s = raw.trim()
        .removePrefix("#")
        .removePrefix("0x")
        .removePrefix("0X")
    val rgb = when (s.length) {
        6 -> s
        8 -> s
        else -> return null
    }
    if (rgb.any { !it.isHexChar() }) return null
    return try {
        AndroidColor.parseColor("#$rgb")
    } catch (_: IllegalArgumentException) {
        null
    }
}

@Composable
private fun SvSquare(
    hue: Float,
    saturation: Float,
    value: Float,
    onChange: (Float, Float) -> Unit,
) {
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(160.dp)
            .pointerInput(hue) {
                fun at(pos: Offset) {
                    val s = (pos.x / size.width).coerceIn(0f, 1f)
                    val v = 1f - (pos.y / size.height).coerceIn(0f, 1f)
                    onChange(s, v)
                }
                detectTapGestures { at(it) }
            }
            .pointerInput(hue) {
                fun at(pos: Offset) {
                    val s = (pos.x / size.width).coerceIn(0f, 1f)
                    val v = 1f - (pos.y / size.height).coerceIn(0f, 1f)
                    onChange(s, v)
                }
                detectDragGestures { change, _ -> at(change.position) }
            },
    ) {
        val hueColor = Color(AndroidColor.HSVToColor(floatArrayOf(hue, 1f, 1f)))
        drawRect(Brush.horizontalGradient(listOf(Color.White, hueColor)))
        drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))
        val cx = saturation * size.width
        val cy = (1f - value) * size.height
        drawCircle(Color.White, radius = 8.dp.toPx(), center = Offset(cx, cy))
        drawCircle(Color.Black, radius = 8.dp.toPx(), center = Offset(cx, cy), style = Stroke(width = 2.dp.toPx()))
    }
}

@Composable
private fun HueBar(hue: Float, onHue: (Float) -> Unit) {
    val hues = listOf(
        Color.Red, Color.Yellow, Color.Green, Color.Cyan, Color.Blue, Color.Magenta, Color.Red,
    )
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(24.dp)
            .pointerInput(Unit) {
                detectTapGestures { onHue((it.x / size.width).coerceIn(0f, 1f) * 360f) }
            }
            .pointerInput(Unit) {
                detectDragGestures { change, _ ->
                    onHue((change.position.x / size.width).coerceIn(0f, 1f) * 360f)
                }
            },
    ) {
        drawRect(Brush.horizontalGradient(hues))
        val x = (hue / 360f) * size.width
        drawCircle(Color.White, radius = 10.dp.toPx(), center = Offset(x, size.height / 2f))
        drawCircle(
            Color.Black,
            radius = 10.dp.toPx(),
            center = Offset(x, size.height / 2f),
            style = Stroke(width = 2.dp.toPx()),
        )
    }
}
