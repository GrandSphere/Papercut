package com.grandsphere.papercut.ui.theme

import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density

private val Bubble = Color(0xFF2A2A2E)

@Composable
fun PapercutTheme(
    fontScale: Float = 1f,
    appFontColor: Int = 0xFFE8EAED.toInt(),
    backgroundColor: Int = 0xFF000000.toInt(),
    actionColor: Int = 0xFFE6D6F5.toInt(),
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val onDark = Color(appFontColor)
    val bg = Color(backgroundColor)
    val action = Color(actionColor)
    val scheme = darkColorScheme(
        primary = onDark,
        onPrimary = bg,
        background = bg,
        onBackground = onDark,
        surface = bg,
        onSurface = onDark,
        surfaceVariant = Bubble,
        outline = Color(0xFF5F6368),
        secondaryContainer = action,
        onSecondaryContainer = bg,
    )
    CompositionLocalProvider(
        LocalDensity provides Density(
            density = density.density,
            fontScale = density.fontScale * fontScale.coerceIn(0.7f, 1.4f),
        ),
    ) {
        MaterialTheme(colorScheme = scheme) {
            CompositionLocalProvider(LocalContentColor provides onDark, content = content)
        }
    }
}
