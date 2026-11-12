package com.grandsphere.papercut.ui.viewer

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import com.grandsphere.papercut.PapercutApp
import kotlinx.coroutines.runBlocking

class OpenActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val source = intent ?: Intent()
        val app = application as PapercutApp
        val multiple = runBlocking { app.settingsRepository.get().allowMultipleInstances } &&
            !IntentUris.isJson(source)
        val target = if (multiple) MultiViewerActivity::class.java else ViewerActivity::class.java
        val next = Intent(source).apply {
            setClass(this@OpenActivity, target)
            flags = flags and Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS.inv()
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (multiple) {
                addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
            }
            if (source.clipData != null) {
                clipData = source.clipData
            }
        }
        startActivity(next)
        finish()
    }
}
