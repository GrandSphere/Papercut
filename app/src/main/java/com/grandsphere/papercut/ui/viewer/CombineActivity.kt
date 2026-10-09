package com.grandsphere.papercut.ui.viewer

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
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
import androidx.core.content.FileProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.grandsphere.papercut.R
import com.grandsphere.papercut.data.DocumentPaths
import com.grandsphere.papercut.pdf.DocumentMagic
import com.grandsphere.papercut.ui.theme.PapercutTheme
import kotlinx.coroutines.launch

class CombineActivity : AppCompatActivity() {
    private val vm: CombineViewModel by viewModels()
    private var entries by mutableStateOf(listOf<CombineEntry>())
    private var pendingExportSave = false
    private var pendingExportFile: java.io.File? = null
    private var chromeBg: Int = com.grandsphere.papercut.data.ViewerSettings.DEFAULT_BG

    private val openMultiple = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        if (uris.isNullOrEmpty()) return@registerForActivityResult
        appendUris(uris, toast = true)
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
        Toast.makeText(this, R.string.saved, Toast.LENGTH_SHORT).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        hideSystemBars()
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
                        onShare = {
                            pendingExportSave = false
                            vm.export(entries.map { it.uri })
                        },
                        onExport = {
                            pendingExportSave = true
                            vm.export(entries.map { it.uri })
                        },
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
            vm.errors.collect { code ->
                val msg = when (code) {
                    "NOT_PDF" -> "PDF files only"
                    "PASSWORD" -> getString(R.string.password_protected)
                    "EXPORT" -> "Could not build PDF"
                    else -> getString(R.string.open_failed)
                }
                Toast.makeText(this@CombineActivity, msg, Toast.LENGTH_SHORT).show()
            }
        }
        lifecycleScope.launch {
            vm.exportReady.collect { file ->
                pendingExportFile = file
                if (pendingExportSave) {
                    createExport.launch("Papercut.pdf")
                } else {
                    shareExportFile(file)
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
        if (hasFocus) hideSystemBars()
    }

    private fun ingestIntent(intent: Intent?, finishIfEmpty: Boolean): Boolean {
        val incoming = intentUris(intent)
        if (incoming.isEmpty()) return true
        val added = appendUris(incoming, toast = true)
        if (finishIfEmpty && added == 0 && entries.isEmpty()) {
            finish()
            return false
        }
        return true
    }

    private fun appendUris(uris: List<Uri>, toast: Boolean): Int {
        val total = uris.size
        val seen = entries.map { it.uri.toString() }.toMutableSet()
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
        if (toast) {
            Toast.makeText(this, "Added ${added.size}/$total files", Toast.LENGTH_SHORT).show()
        }
        return added.size
    }

    private fun intentUris(intent: Intent?): List<Uri> {
        if (intent == null) return emptyList()
        val fromStream = when (intent.action) {
            Intent.ACTION_SEND_MULTIPLE -> parcelableUris(intent)
            Intent.ACTION_SEND -> listOfNotNull(parcelableUri(intent))
            else -> emptyList()
        }
        if (fromStream.isNotEmpty()) return fromStream
        val clip = intent.clipData ?: return emptyList()
        return (0 until clip.itemCount).mapNotNull { clip.getItemAt(it)?.uri }
    }

    private fun parcelableUri(intent: Intent): Uri? {
        return if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_STREAM)
        }
    }

    private fun parcelableUris(intent: Intent): List<Uri> {
        val list = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)
        }
        return list.orEmpty()
    }

    private fun shareExportFile(file: java.io.File) {
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = DocumentMagic.PDF
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri("document", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(send, getString(R.string.share)))
    }

    private fun hideSystemBars() {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        window.statusBarColor = chromeBg
        window.navigationBarColor = chromeBg
    }
}
