package com.grandsphere.papercut.ui.viewer

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import com.grandsphere.papercut.PapercutApp
import kotlinx.coroutines.runBlocking

class OpenActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val source = intent ?: Intent()
        val app = application as PapercutApp
        val multiple = runBlocking { app.settingsRepository.get().allowMultipleInstances }
        val json = isJsonIntent(source)
        val target = if (multiple && !json) {
            MultiViewerActivity::class.java
        } else {
            ViewerActivity::class.java
        }
        val next = Intent(source).apply {
            setClass(this@OpenActivity, target)
            flags = flags and Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS.inv()
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (multiple && !json) {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_DOCUMENT or
                        Intent.FLAG_ACTIVITY_MULTIPLE_TASK,
                )
            }
            if (source.clipData != null) {
                clipData = source.clipData
            }
        }
        startActivity(next)
        finish()
    }

    private fun isJsonIntent(source: Intent): Boolean {
        val type = source.type?.lowercase()
        if (type == "application/json" || type == "text/json") return true
        val uri = intentUri(source) ?: return false
        val name = uri.lastPathSegment?.lowercase().orEmpty()
        return name.endsWith(".json")
    }

    private fun intentUri(intent: Intent?): Uri? {
        if (intent == null) return null
        return when (intent.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> {
                if (android.os.Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_STREAM)
                }
            }
            else -> null
        }
    }
}
