package com.grandsphere.papercut.ui.viewer

import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.grandsphere.papercut.PapercutApp
import com.grandsphere.papercut.R
import com.grandsphere.papercut.data.FolioItem
import com.grandsphere.papercut.data.ViewerSettings
import com.grandsphere.papercut.pdf.DocumentMagic

class FolioActivity : PdfHostActivity() {
    override val vm: FolioViewModel by viewModels()
    override val isFolio = true
    override val clearViewWhenNoDocument = false
    private var manageFolio by mutableStateOf(false)

    private val addDocuments = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        if (uris.isNullOrEmpty()) return@registerForActivityResult
        val total = uris.size
        uris.forEach { DocumentMagic.persistRead(this, it) }
        val valid = uris.filter { DocumentMagic.isReadable(this, it) }
        vm.addFromUris(valid) { added -> toast("Added $added/$total files") }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setHostContent()
    }

    @Composable
    override fun hostFolio(settings: ViewerSettings): HostFolio {
        val items by vm.visibleFolioItems.collectAsStateWithLifecycle()
        val index by vm.folioIndex.collectAsStateWithLifecycle()
        return HostFolio(enabled = true, items = items, index = index, alreadyIn = true)
    }

    @Composable
    override fun HostOverlays() {
        if (!manageFolio) return
        val allFolio by vm.folioItems.collectAsStateWithLifecycle()
        ManageFolioOverlay(
            items = allFolio,
            onDismiss = { manageFolio = false },
            onDelete = vm::deleteFolioItems,
            onReorder = vm::reorderFolio,
            onToggleHidden = vm::setFolioHidden,
            onSetStart = vm::setFolioStart,
        )
    }

    override fun onStart() {
        super.onStart()
        (application as PapercutApp).registerFolio(this)
    }

    override fun onPause() {
        rememberFolioPosition()
        super.onPause()
    }

    override fun onDestroy() {
        (application as PapercutApp).unregisterFolio(this)
        super.onDestroy()
    }

    override fun onOpenRequested() {
        addDocuments.launch(DocumentMagic.OPEN_MIME_TYPES)
    }

    override fun onLeaveFolio() {
        finish()
    }

    override fun onManageFolio() {
        manageFolio = true
    }

    override fun onFolioIndex(index: Int) {
        rememberFolioPosition()
        vm.showFolioIndex(index)
    }

    override fun onSaveFolio() {
        saveCurrentFolioPosition()
        toast(R.string.saved)
    }

    override fun onFolioSwipe(delta: Int) {
        val items = vm.visibleFolioItems.value
        if (items.isEmpty()) return
        val next = (vm.folioIndex.value + delta).coerceIn(0, items.lastIndex)
        if (next == vm.folioIndex.value) return
        vm.disconnectJump()
        onFolioIndex(next)
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
        val file = vm.currentCopy()
        if (file == null) {
            toast(R.string.no_document)
            return
        }
        shareFile(file, DocumentMagic.shareType(vm.document.value?.mime ?: DocumentMagic.PDF))
    }

    private fun rememberFolioPosition() {
        if (vm.storesLastPosition()) saveCurrentFolioPosition()
    }

    private fun saveCurrentFolioPosition() {
        val view = pdfView ?: return
        if (vm.document.value == null) return
        vm.saveFolioSnapshot(
            view.currentPageIndex(),
            view.currentScrollXPdf(),
            view.currentOffsetOnPage(),
        )
    }
}
