package com.grandsphere.papercut.ui.viewer

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.grandsphere.papercut.R
import com.grandsphere.papercut.data.DocumentPaths
import com.grandsphere.papercut.data.ViewerSettings
import com.grandsphere.papercut.pdf.DocumentMagic
import com.grandsphere.papercut.ui.theme.PapercutTheme
import kotlinx.coroutines.launch
import java.io.File

class CombineActivity : AppCompatActivity() {
    private val vm: CombineViewModel by viewModels()
    private var entries by mutableStateOf(listOf<CombineEntry>())
    private var pendingExportSave = false
    private var pendingExportFile: File? = null

    private val openMultiple = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        if (uris.isNullOrEmpty()) return@registerForActivityResult
        appendUris(uris)
    }

    private val createExport = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/pdf"),
    ) { uri ->
        val src = pendingExportFile ?: return@registerForActivityResult
        if (uri == null) return@registerForActivityResult
        runCatching {
            contentResolver.openOutputStream(uri)?.use { out ->
                src.inputStream().use { it.copyTo(out) }
            }
        }
        toast(R.string.saved)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        hideSystemBars(ViewerSettings.DEFAULT_BG)
        if (!ingestIntent(intent, finishIfEmpty = true)) return
        setContent {
            val settings by vm.settings.collectAsStateWithLifecycle()
            val busy by vm.busy.collectAsStateWithLifecycle()
            PapercutTheme(
                fontScale = settings.fontScale,
                appFontColor = settings.appFontColor,
                backgroundColor = settings.bgColor,
                actionColor = settings.actionColor,
            ) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    CombineOverlay(
                        entries = entries,
                        onDismiss = { finish() },
                        onChange = { entries = it },
                        onShare = { export(save = false) },
                        onExport = { export(save = true) },
                        onAdd = { openMultiple.launch(DocumentMagic.OPEN_MIME_TYPES) },
                    )
                    val busyText = busy
                    if (busyText != null) {
                        Box(
                            Modifier
                                .align(Alignment.Center)
                                .background(MaterialTheme.colorScheme.background.copy(alpha = 0.92f))
                                .padding(24.dp),
                        ) {
                            Text(busyText, color = MaterialTheme.colorScheme.onBackground)
                        }
                    }
                }
            }
        }
        lifecycleScope.launch {
            vm.errors.collect { code -> toast(errorMessage(code)) }
        }
        lifecycleScope.launch {
            vm.exportReady.collect { file ->
                pendingExportFile = file
                if (pendingExportSave) {
                    createExport.launch("Papercut.pdf")
                } else {
                    shareFile(file)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        ingestIntent(intent, finishIfEmpty = false)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars(ViewerSettings.DEFAULT_BG)
    }

    private fun export(save: Boolean) {
        pendingExportSave = save
        vm.export(entries.map { it.uri })
    }

    private fun ingestIntent(intent: Intent?, finishIfEmpty: Boolean): Boolean {
        val incoming = IntentUris.combineUris(intent)
        if (incoming.isEmpty()) return true
        val added = appendUris(incoming)
        if (finishIfEmpty && added == 0 && entries.isEmpty()) {
            finish()
            return false
        }
        return true
    }

    private fun appendUris(uris: List<Uri>): Int {
        val seen = entries.mapTo(HashSet()) { it.uri.toString() }
        val added = mutableListOf<CombineEntry>()
        uris.forEach { uri ->
            DocumentMagic.persistRead(this, uri)
            if (!DocumentMagic.isReadable(this, uri)) return@forEach
            if (!seen.add(uri.toString())) return@forEach
            val label = runCatching { DocumentPaths.label(this, uri) }
                .getOrElse { DocumentPaths.fromUriString(uri.toString()) }
            added += CombineEntry(uri, label.displayName, label.displayPath)
        }
        if (added.isNotEmpty()) {
            entries = entries + added
        }
        toast("Added ${added.size}/${uris.size} files")
        return added.size
    }
}
