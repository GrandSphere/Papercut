package com.grandsphere.papercut.ui.chrome

import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.view.Gravity
import android.view.Window
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsIgnoringVisibility
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

fun Window.applyPapercutImmersive(bgArgb: Int) {
    WindowCompat.setDecorFitsSystemWindows(this, false)
    addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
    clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS)
    clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION)
    setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
    statusBarColor = bgArgb
    navigationBarColor = bgArgb
    setBackgroundDrawable(ColorDrawable(bgArgb))
    setDimAmount(0f)
    attributes = attributes.apply {
        width = WindowManager.LayoutParams.MATCH_PARENT
        height = WindowManager.LayoutParams.MATCH_PARENT
        gravity = Gravity.FILL
        if (Build.VERSION.SDK_INT >= 28) {
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
    }
    if (Build.VERSION.SDK_INT >= 29) {
        isStatusBarContrastEnforced = false
        isNavigationBarContrastEnforced = false
    }
    hidePapercutSystemBars()
}

fun Window.hidePapercutSystemBars() {
    val controller = WindowCompat.getInsetsController(this, decorView)
    controller.hide(WindowInsetsCompat.Type.systemBars())
    controller.systemBarsBehavior =
        WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PapercutScreenBar(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val on = MaterialTheme.colorScheme.onBackground
    Row(
        modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.statusBarsIgnoringVisibility)
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Back",
                tint = on,
            )
        }
        Text(
            title,
            color = on,
            fontSize = 16.sp,
            fontWeight = FontWeight.Normal,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        CompositionLocalProvider(LocalContentColor provides on) {
            actions()
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PapercutOverlay(
    title: String,
    onDismiss: () -> Unit,
    actions: @Composable RowScope.() -> Unit = {},
    bottomBar: @Composable (ColumnScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    BackHandler(onBack = onDismiss)
    val bg = MaterialTheme.colorScheme.background
    Column(
        Modifier
            .fillMaxSize()
            .background(bg)
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent()
                    }
                }
            },
    ) {
        PapercutScreenBar(
            title = title,
            onBack = onDismiss,
            actions = actions,
        )
        Box(Modifier.weight(1f).fillMaxWidth()) {
            Column(Modifier.fillMaxSize(), content = content)
        }
        if (bottomBar != null) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(bg)
                    .windowInsetsPadding(WindowInsets.navigationBarsIgnoringVisibility)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                content = bottomBar,
            )
        }
    }
}
