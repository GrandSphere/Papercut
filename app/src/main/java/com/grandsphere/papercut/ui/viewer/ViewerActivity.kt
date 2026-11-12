package com.grandsphere.papercut.ui.viewer

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.core.content.FileProvider
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.grandsphere.papercut.PapercutApp
import com.grandsphere.papercut.R
import com.grandsphere.papercut.data.BackupJson
import com.grandsphere.papercut.data.ViewerSettings
import com.grandsphere.papercut.pdf.DocumentMagic
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File

open class ViewerActivity : PdfHostActivity() {
    override val vm: ViewerViewModel by viewModels()
    private var previewMode = false

    private val openDocument = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) persistAndOpen(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val previewPath = intent.getStringExtra(EXTRA_PREVIEW)
        if (previewPath != null) {
            previewMode = true
            vm.openPreview(File(previewPath))
            setContent {
                val document by vm.document.collectAsStateWithLifecycle()
                val settings by vm.viewingSettings.collectAsStateWithLifecycle()
                PreviewScreen(
                    pages = document?.pages.orEmpty(),
                    background = settings.bgColor,
                    onBack = { finish() },
                    onView = { view ->
                        pdfView = view
                        bindPdf(view)
                        view.setSettings(settings)
                    },
                )
            }
            return
        }
        val hasDocIntent = IntentUris.documentUri(intent) != null && !IntentUris.isJson(intent)
        handleIntent(intent)
        vm.maybeRestoreLast(hasDocIntent)
        setHostContent()
        lifecycleScope.launch {
            vm.launchFolio.collect { onEnterFolio() }
        }
    }

    @Composable
    override fun hostFolio(settings: ViewerSettings): HostFolio =
        HostFolio(enabled = settings.folioEnabled, alreadyIn = vm.alreadyInFolio())

    override fun onOpenRequested() {
        openDocument.launch(DocumentMagic.OPEN_MIME_TYPES)
    }

    override fun onEnterFolio() {
        startActivity(Intent(this, FolioActivity::class.java))
    }

    override fun onAddToFolio() {
        vm.addCurrentToFolio()
    }

    override fun onCombine() {
        startActivity(Intent(this, CombineActivity::class.java))
    }

    override fun shareDocument(keepRotation: Boolean) {
        if (vm.document.value == null) {
            toast(R.string.no_document)
            return
        }
        if (keepRotation && vm.hasRotation()) {
            vm.exportFull(keepRotation = true)
            return
        }
        val uri = vm.sourceContentUri()
            ?: vm.lastPdfCopy().takeIf { it.exists() }?.let {
                FileProvider.getUriForFile(this, "$packageName.fileprovider", it)
            }
        if (uri == null) {
            toast(R.string.no_document)
            return
        }
        shareUri(uri, DocumentMagic.shareType(vm.document.value?.mime ?: DocumentMagic.PDF))
    }

    override fun onStart() {
        super.onStart()
        if (!previewMode) (application as PapercutApp).registerViewer(this)
    }

    override fun onDestroy() {
        if (!previewMode) (application as PapercutApp).unregisterViewer(this)
        super.onDestroy()
    }

    override fun onPause() {
        if (!previewMode) saveCurrentReadingPosition()
        super.onPause()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (previewMode) return
        setIntent(intent)
        handleIntent(intent)
    }

    private fun persistAndOpen(uri: Uri) {
        overlayReset += 1
        pageSelect = false
        selectedPages = emptyList()
        DocumentMagic.persistRead(this, uri)
        val view = pdfView
        vm.open(
            uri,
            previousPage = view?.currentPageIndex(),
            previousScrollX = view?.currentScrollXPdf(),
            previousScrollY = view?.currentScrollYPdf(),
        )
    }

    private fun saveCurrentReadingPosition() {
        val view = pdfView ?: return
        if (vm.document.value == null) return
        vm.saveReadingPosition(
            view.currentPageIndex(),
            view.currentScrollXPdf(),
            view.currentOffsetOnPage(),
        )
    }

    private fun importJson(source: Intent): Boolean {
        val uri = IntentUris.documentUri(source) ?: return false
        val text = runCatching {
            contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
        }.getOrNull() ?: return false
        val parsed = runCatching { BackupJson.parse(text) }.getOrNull()
        if (parsed == null) {
            toast("Not a Papercut file")
            return true
        }
        val app = application as PapercutApp
        runBlocking {
            parsed.settingsJson?.let { obj ->
                app.settingsRepository.update { current -> BackupJson.mergeSettings(current, obj) }
            }
            app.bookmarkRepository.importParsed(parsed)
        }
        toast("Imported")
        return true
    }

    private fun handleIntent(intent: Intent?) {
        if (intent != null && IntentUris.isJson(intent)) {
            importJson(intent)
            return
        }
        persistAndOpen(IntentUris.documentUri(intent) ?: return)
    }

    companion object {
        const val EXTRA_PREVIEW = "com.grandsphere.papercut.PREVIEW"
    }
}
