package com.grandsphere.papercut.ui.viewer

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.core.content.FileProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.grandsphere.papercut.R
import com.grandsphere.papercut.pdf.DocumentMagic
import java.io.File

internal fun Context.toast(message: CharSequence) {
    Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
}

internal fun Context.toast(@StringRes message: Int) {
    Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
}

internal fun Context.errorMessage(code: String): String = when (code) {
    "FOLIO_ADDED" -> "Added to Folio"
    "FOLIO_COPY" -> "Could not add to Folio"
    "NOT_PDF" -> "PDF files only"
    "PASSWORD" -> getString(R.string.password_protected)
    "EXPORT" -> "Could not build PDF"
    else -> getString(R.string.open_failed)
}

internal fun Activity.hideSystemBars(chromeColor: Int) {
    val controller = WindowCompat.getInsetsController(window, window.decorView)
    controller.hide(WindowInsetsCompat.Type.systemBars())
    controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    window.statusBarColor = chromeColor
    window.navigationBarColor = chromeColor
}

internal fun Activity.shareUri(uri: Uri, mime: String) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = mime
        putExtra(Intent.EXTRA_STREAM, uri)
        clipData = ClipData.newRawUri("document", uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    startActivity(Intent.createChooser(send, getString(R.string.share)))
}

internal fun Activity.shareFile(file: File, mime: String = DocumentMagic.PDF) {
    shareUri(FileProvider.getUriForFile(this, "$packageName.fileprovider", file), mime)
}

internal fun Activity.shareText(text: String) {
    startActivity(
        Intent.createChooser(
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, text)
            },
            getString(R.string.share),
        ),
    )
}

internal fun Activity.copyText(text: String) {
    getSystemService(ClipboardManager::class.java)
        .setPrimaryClip(ClipData.newPlainText("pdf", text))
    toast(R.string.copied)
}

internal object IntentUris {
    fun streamUri(intent: Intent): Uri? =
        if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_STREAM)
        }

    private fun streamUris(intent: Intent): List<Uri> {
        val list = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)
        }
        return list.orEmpty()
    }

    fun documentUri(intent: Intent?): Uri? {
        if (intent == null) return null
        return when (intent.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> streamUri(intent)
            else -> null
        }
    }

    fun isJson(source: Intent): Boolean {
        val type = source.type?.lowercase()
        if (type == "application/json" || type == "text/json") return true
        val uri = documentUri(source) ?: return false
        return uri.lastPathSegment?.lowercase().orEmpty().endsWith(".json")
    }

    fun combineUris(intent: Intent?): List<Uri> {
        if (intent == null) return emptyList()
        val fromStream = when (intent.action) {
            Intent.ACTION_SEND_MULTIPLE -> streamUris(intent)
            Intent.ACTION_SEND -> listOfNotNull(streamUri(intent))
            else -> emptyList()
        }
        if (fromStream.isNotEmpty()) return fromStream
        val clip = intent.clipData ?: return emptyList()
        return (0 until clip.itemCount).mapNotNull { clip.getItemAt(it)?.uri }
    }
}
