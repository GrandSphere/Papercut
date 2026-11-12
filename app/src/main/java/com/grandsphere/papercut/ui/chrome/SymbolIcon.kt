package com.grandsphere.papercut.ui.chrome

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.grandsphere.papercut.R

/** Desktop Material Symbols ligature aliases from `qml/Icon.qml`. */
fun papercutSymbolLiga(name: String): String = when (name) {
    "bookmark_border" -> "bookmark"
    "expand_less" -> "keyboard_arrow_up"
    "expand_more" -> "keyboard_arrow_down"
    "width_full" -> "fit_width"
    "save" -> "check"
    "edit" -> "edit_note"
    else -> name
}

@OptIn(ExperimentalTextApi::class)
@Composable
fun SymbolIcon(
    name: String,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
    size: Dp = 24.dp,
    struck: Boolean = false,
) {
    val family = remember {
        FontFamily(
            Font(
                R.font.material_symbols_outlined,
                weight = FontWeight.W300,
                variationSettings = FontVariation.Settings(
                    FontVariation.weight(300),
                    FontVariation.Setting("FILL", 0f),
                    FontVariation.Setting("GRAD", 0f),
                    FontVariation.Setting("opsz", 24f),
                ),
            ),
        )
    }
    val liga = papercutSymbolLiga(name)
    val described = if (contentDescription != null) {
        modifier.semantics {
            this.contentDescription = contentDescription
            role = Role.Image
        }
    } else {
        modifier
    }
    Box(described.size(size), contentAlignment = Alignment.Center) {
        Text(
            text = liga,
            color = tint,
            fontSize = with(LocalDensity.current) { size.toSp() * 0.92f },
            fontFamily = family,
            fontWeight = FontWeight.W300,
            textAlign = TextAlign.Center,
            maxLines = 1,
            softWrap = false,
            lineHeight = size.value.sp,
            style = TextStyle(fontFeatureSettings = "liga, rlig"),
        )
        if (struck) {
            Canvas(Modifier.size(size * 0.92f)) {
                val stroke = 1.5.dp.toPx()
                drawLine(
                    color = tint,
                    start = Offset(stroke, stroke),
                    end = Offset(this.size.width - stroke, this.size.height - stroke),
                    strokeWidth = stroke,
                    cap = StrokeCap.Round,
                )
            }
        }
    }
}
