package com.grandsphere.papercut.ui.viewer

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.grandsphere.papercut.PapercutApp
import com.grandsphere.papercut.data.AppFiles
import com.grandsphere.papercut.data.ViewerSettings
import com.grandsphere.papercut.pdf.PdfComposer
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

class CombineViewModel(application: Application) : AndroidViewModel(application) {
    private val repo = (application as PapercutApp).settingsRepository

    val settings: StateFlow<ViewerSettings> = repo.observe().stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        repo.snapshot,
    )

    private val _busy = MutableStateFlow<String?>(null)
    val busy: StateFlow<String?> = _busy.asStateFlow()

    private val _exportReady = MutableSharedFlow<File>(extraBufferCapacity = 1)
    val exportReady: SharedFlow<File> = _exportReady.asSharedFlow()

    private val _errors = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val errors: SharedFlow<String> = _errors.asSharedFlow()

    fun export(uris: List<Uri>) {
        viewModelScope.launch {
            _busy.value = "Building PDF"
            runCatching {
                val dest = AppFiles.exportPdf(getApplication())
                PdfComposer.combine(getApplication(), uris, dest, repo.get().fontScale)
                dest
            }.onSuccess {
                _busy.value = null
                _exportReady.emit(it)
            }.onFailure {
                _busy.value = null
                _errors.emit(it.message ?: "EXPORT")
            }
        }
    }
}
