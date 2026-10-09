package com.grandsphere.papercut.ui.viewer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.grandsphere.papercut.pdf.PageInfo
import com.grandsphere.papercut.ui.chrome.PapercutScreenBar
import com.grandsphere.papercut.ui.theme.PapercutTheme

@Composable
fun PreviewScreen(
    pages: List<PageInfo>,
    background: Int,
    onBack: () -> Unit,
    onView: (PdfStripView) -> Unit,
) {
    PapercutTheme(backgroundColor = background) {
        Column(
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
        ) {
            PapercutScreenBar(
                title = "Preview",
                onBack = onBack,
            )
            Box(Modifier.fillMaxSize()) {
                AndroidView(
                    factory = { ctx ->
                        PdfStripView(ctx).also(onView)
                    },
                    update = { view ->
                        if (pages.isEmpty()) {
                            if (view.hasDocument()) view.setDocument(emptyList())
                        } else if (!view.hasDocument()) {
                            view.setDocument(pages)
                        } else {
                            view.replacePageSizes(pages)
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}
