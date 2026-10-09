package com.grandsphere.papercut.ui.settings

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBarsIgnoringVisibility
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.grandsphere.papercut.data.AppFiles
import com.grandsphere.papercut.data.BackupJson
import com.grandsphere.papercut.data.PdfDocument
import com.grandsphere.papercut.data.ViewerSettings
import com.grandsphere.papercut.data.ZoomMode
import com.grandsphere.papercut.ui.chrome.CollapsibleSection
import com.grandsphere.papercut.ui.chrome.ColourSettingRow
import com.grandsphere.papercut.ui.chrome.ColourSwitchRow
import com.grandsphere.papercut.ui.chrome.PapercutAlertDialog
import com.grandsphere.papercut.ui.chrome.PapercutScreenBar
import com.grandsphere.papercut.ui.chrome.applyPapercutImmersive
import com.grandsphere.papercut.ui.chrome.hidePapercutSystemBars
import com.grandsphere.papercut.ui.chrome.SettingsSwitchRow
import com.grandsphere.papercut.ui.chrome.SettingsTapRow
import com.grandsphere.papercut.ui.chrome.ThinGreySlider
import com.grandsphere.papercut.ui.theme.PapercutTheme
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

class SettingsActivity : ComponentActivity() {
    private val vm: SettingsViewModel by viewModels()
    private var exportKind: BackupJson.Kind = BackupJson.Kind.All

    private val createExport = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            runCatching {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }
            vm.exportTo(uri, exportKind)
        }
    }

    private val openImport = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) vm.importFrom(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.applyPapercutImmersive(0xFF000000.toInt())
        setContent {
            val settings by vm.settings.collectAsStateWithLifecycle()
            val documents by vm.documents.collectAsStateWithLifecycle()
            SettingsScreen(
                settings = settings,
                documents = documents,
                onBack = { finish() },
                onSave = { draft ->
                    vm.save(draft)
                    Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show()
                },
                onExport = { kind ->
                    exportKind = kind
                    createExport.launch(exportFileName(kind))
                },
                onImport = { openImport.launch(arrayOf("application/json", "text/plain", "*/*")) },
                onShareExport = { kind -> shareExport(kind) },
                onDeleteDocuments = vm::deleteDocuments,
                onClearBookmarks = vm::clearBookmarks,
                stored = vm.stored.collectAsStateWithLifecycle().value,
                onRefreshStored = vm::refreshStored,
                onDeleteStored = vm::deleteStored,
                onShareStored = { entry -> shareStored(entry.path) },
                onPreviewStored = { entry ->
                    startActivity(
                        Intent(this, com.grandsphere.papercut.ui.viewer.MultiViewerActivity::class.java).apply {
                            putExtra(
                                com.grandsphere.papercut.ui.viewer.ViewerActivity.EXTRA_PREVIEW,
                                entry.path,
                            )
                        },
                    )
                },
            )
        }
        lifecycleScope.launch {
            vm.messages.collect { Toast.makeText(this@SettingsActivity, it, Toast.LENGTH_SHORT).show() }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) window.hidePapercutSystemBars()
    }

    private fun exportFileName(kind: BackupJson.Kind): String = when (kind) {
        BackupJson.Kind.All -> "Papercut-all.json"
        BackupJson.Kind.Settings -> "Papercut-settings.json"
        BackupJson.Kind.Bookmarks -> "Papercut-bookmarks.json"
        BackupJson.Kind.Folio -> "Papercut-folio.json"
    }

    private fun shareExport(kind: BackupJson.Kind) {
        lifecycleScope.launch {
            val json = runCatching { vm.exportText(kind) }.getOrNull() ?: return@launch
            val file = AppFiles.jsonShare(this@SettingsActivity, exportFileName(kind))
            file.writeText(json)
            shareStored(file.absolutePath, "application/json")
        }
    }

    private fun shareStored(path: String, mime: String = "application/pdf") {
        val file = java.io.File(path)
        if (!file.exists()) return
        val type = when {
            mime != "application/pdf" -> mime
            file.extension.equals("json", ignoreCase = true) -> "application/json"
            else -> "application/pdf"
        }
        val uri = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            this.type = type
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = android.content.ClipData.newRawUri("file", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(send, "Share"))
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    settings: ViewerSettings,
    documents: List<PdfDocument>,
    stored: List<com.grandsphere.papercut.data.StoredEntry>,
    onBack: () -> Unit,
    onSave: (ViewerSettings) -> Unit,
    onExport: (BackupJson.Kind) -> Unit,
    onShareExport: (BackupJson.Kind) -> Unit,
    onImport: () -> Unit,
    onDeleteDocuments: (List<String>) -> Unit,
    onClearBookmarks: () -> Unit,
    onRefreshStored: () -> Unit,
    onDeleteStored: (List<com.grandsphere.papercut.data.StoredEntry>) -> Unit,
    onShareStored: (com.grandsphere.papercut.data.StoredEntry) -> Unit,
    onPreviewStored: (com.grandsphere.papercut.data.StoredEntry) -> Unit,
) {
    var draft by remember { mutableStateOf<ViewerSettings?>(null) }
    val current = draft ?: settings
    var colourPicker by remember { mutableStateOf<String?>(null) }
    var paddingKind by remember { mutableStateOf<String?>(null) }
    var manageOpen by remember { mutableStateOf(false) }
    var clearOpen by remember { mutableStateOf(false) }
    var copyWarn by remember { mutableStateOf(false) }
    var storedOpen by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<List<com.grandsphere.papercut.data.StoredEntry>?>(null) }
    val view = LocalView.current
    val context = LocalContext.current
    SideEffect {
        val window = (view.context as android.app.Activity).window
        window.applyPapercutImmersive(current.bgColor)
    }

    PapercutTheme(
        fontScale = current.fontScale,
        appFontColor = current.appFontColor,
        backgroundColor = current.bgColor,
        actionColor = current.actionColor,
    ) {
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
    Column(Modifier.fillMaxSize()) {
        PapercutScreenBar(
            title = "Settings",
            onBack = onBack,
            actions = {
                IconButton(onClick = { onSave(current) }) {
                    Icon(
                        Icons.Outlined.Save,
                        contentDescription = "Save",
                        tint = MaterialTheme.colorScheme.onBackground,
                    )
                }
            },
        )
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBarsIgnoringVisibility))
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CollapsibleSection(
                "On start",
                hint = "Reopen the last document, open Folio, and optionally always load themed dark colours",
            ) {
                SettingsSwitchRow(
                    label = "Open last PDF",
                    checked = current.restoreLastPdf,
                    onChecked = { on ->
                        if (on) {
                            copyWarn = true
                        } else {
                            draft = current.copy(restoreLastPdf = false)
                        }
                    },
                )
                SettingsSwitchRow(
                    label = "Open Folio",
                    checked = current.openFolioOnStartup,
                    enabled = current.folioEnabled,
                    onChecked = { on ->
                        if (!current.folioEnabled) return@SettingsSwitchRow
                        draft = if (on) {
                            current.copy(openFolioOnStartup = true, restoreLastPdf = false)
                        } else {
                            current.copy(openFolioOnStartup = false)
                        }
                    },
                )
                SettingsSwitchRow(
                    label = "Always dark mode",
                    checked = current.alwaysDarkMode,
                    onChecked = { on -> draft = current.copy(alwaysDarkMode = on) },
                )
            }

            CollapsibleSection(
                "Folio",
                hint = "Turn the document wallet on, and which item to show first",
            ) {
                SettingsSwitchRow(
                    label = "Enable Folio",
                    checked = current.folioEnabled,
                    onChecked = { on ->
                        draft = if (on) {
                            current.copy(folioEnabled = true)
                        } else {
                            current.copy(folioEnabled = false, openFolioOnStartup = false)
                        }
                    },
                )
                SettingsSwitchRow(
                    label = "Open last viewed folio item",
                    checked = current.folioOpenLastViewed,
                    enabled = current.folioEnabled,
                    onChecked = { on -> draft = current.copy(folioOpenLastViewed = on) },
                )
                SettingsSwitchRow(
                    label = "Store last position",
                    checked = current.folioStoreLastPosition,
                    enabled = current.folioEnabled,
                    onChecked = { on -> draft = current.copy(folioStoreLastPosition = on) },
                )
            }

            CollapsibleSection(
                "Appearance",
                hint = "Document colours, UI font, action accent, font size, and page padding",
            ) {
                ColourSettingRow(
                    label = "Foreground",
                    argb = current.fgColor,
                    onClick = { colourPicker = "fg" },
                    onLongClick = { draft = current.copy(fgColor = ViewerSettings.DEFAULT_FG) },
                )
                ColourSettingRow(
                    label = "Background",
                    argb = current.bgColor,
                    onClick = { colourPicker = "bg" },
                    onLongClick = { draft = current.copy(bgColor = ViewerSettings.DEFAULT_BG) },
                )
                ColourSettingRow(
                    label = "Highlight",
                    argb = current.highlightColor,
                    onClick = { colourPicker = "hl" },
                    onLongClick = { draft = current.copy(highlightColor = ViewerSettings.DEFAULT_HIGHLIGHT) },
                )
                ColourSwitchRow(
                    label = "Active page border",
                    argb = current.pageBorderColor,
                    checked = current.pageBorderEnabled,
                    onChecked = { on -> draft = current.copy(pageBorderEnabled = on) },
                    onColourClick = { colourPicker = "border" },
                    onColourLongClick = {
                        draft = current.copy(pageBorderColor = ViewerSettings.DEFAULT_PAGE_BORDER)
                    },
                )
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                ColourSettingRow(
                    label = "Font",
                    argb = current.appFontColor,
                    onClick = { colourPicker = "appfont" },
                    onLongClick = { draft = current.copy(appFontColor = ViewerSettings.DEFAULT_APP_FONT) },
                )
                ColourSettingRow(
                    label = "Action",
                    argb = current.actionColor,
                    onClick = { colourPicker = "action" },
                    onLongClick = { draft = current.copy(actionColor = ViewerSettings.DEFAULT_ACTION) },
                )
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                Text("Font size", color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(0.85f to "Smaller", 1f to "Default", 1.15f to "Larger").forEach { (scale, label) ->
                        FilterChip(
                            selected = abs(current.fontScale - scale) < 0.01f,
                            onClick = { draft = current.copy(fontScale = scale) },
                            label = { Text(label) },
                            colors = papercutFilterChipColors(),
                        )
                    }
                }
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                SettingsTapRow(
                    label = "Vertical page padding",
                    value = "${current.pagePaddingDp}",
                    onClick = { paddingKind = "vertical" },
                )
                SettingsTapRow(
                    label = "Horizontal page padding",
                    value = "${current.horizontalPagePaddingDp}",
                    onClick = { paddingKind = "horizontal" },
                )
            }

            CollapsibleSection(
                "Graphics",
                hint = "Whether pictures use themed colours or their original colours, and picture alpha",
            ) {
                PictureProfileSliders(
                    blend = current.imageColorBlend,
                    alpha = current.imageAlpha,
                    onBlend = { v -> draft = current.copy(imageColorBlend = v) },
                    onAlpha = { v -> draft = current.copy(imageAlpha = v) },
                )
            }

            CollapsibleSection(
                "Dark defaults",
                hint = "Picture theming and alpha applied only when Always dark mode is on at startup",
            ) {
                PictureProfileSliders(
                    blend = current.darkImageBlend,
                    alpha = current.darkImageAlpha,
                    onBlend = { v -> draft = current.copy(darkImageBlend = v) },
                    onAlpha = { v -> draft = current.copy(darkImageAlpha = v) },
                )
            }

            CollapsibleSection(
                "Zoom",
                hint = "Remember last zoom, or set a startup zoom (fit, fit width, or a percentage)",
            ) {
                SettingsSwitchRow(
                    label = "Remember last zoom",
                    checked = current.rememberLastZoom,
                    onChecked = { on -> draft = current.copy(rememberLastZoom = on) },
                )
                Text(
                    "Zoom on startup:",
                    color = MaterialTheme.colorScheme.onBackground.copy(
                        alpha = if (current.rememberLastZoom) 0.35f else 0.7f,
                    ),
                )
                val enabled = !current.rememberLastZoom
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                        ZoomMode.FIT_PAGE to "Fit",
                        ZoomMode.FIT_WIDTH to "Fit width",
                        ZoomMode.CUSTOM to "Zoom",
                    ).forEach { (mode, label) ->
                        FilterChip(
                            selected = current.startupMode() == mode,
                            enabled = enabled,
                            onClick = { draft = current.copy(startupZoomMode = mode.name) },
                            label = { Text(label) },
                            colors = papercutFilterChipColors(),
                        )
                    }
                }
                if (current.startupMode() == ZoomMode.CUSTOM) {
                    val percent = current.startupZoomPercent.coerceIn(50f, 400f)
                    Text("${percent.roundToInt()}%")
                    ThinGreySlider(
                        value = percent,
                        onValueChange = { v ->
                            draft = current.copy(startupZoomPercent = v, startupZoomMode = ZoomMode.CUSTOM.name)
                        },
                        valueRange = 50f..400f,
                        enabled = enabled,
                    )
                }
            }

            CollapsibleSection(
                "Interaction",
                hint = "Links, text selection, side pan, and how to open the menu",
            ) {
                SettingsSwitchRow(
                    label = "Enable following links",
                    checked = current.allowFollowLinks,
                    onChecked = { on -> draft = current.copy(allowFollowLinks = on) },
                )
                SettingsSwitchRow(
                    label = "Enable text selection",
                    checked = current.allowTextSelection,
                    onChecked = { on -> draft = current.copy(allowTextSelection = on) },
                )
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                SettingsSwitchRow(
                    label = "Auto-enable pan",
                    checked = current.autoDisablePan,
                    onChecked = { on -> draft = current.copy(autoDisablePan = on) },
                    onLongClick = {
                        Toast.makeText(context, "Zooming unlocks panning", Toast.LENGTH_SHORT).show()
                    },
                )
                SettingsSwitchRow(
                    label = "2 Finger panning",
                    checked = current.twoFingerPan,
                    onChecked = { on -> draft = current.copy(twoFingerPan = on) },
                )
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                SettingsSwitchRow(
                    label = "Double tap to open menu",
                    checked = current.doubleTapMenu,
                    onChecked = { on ->
                        draft = current.copy(
                            doubleTapMenu = on,
                            singleTapMenu = if (on) false else current.singleTapMenu,
                        )
                    },
                )
                SettingsSwitchRow(
                    label = "Single tap to open menu",
                    checked = current.singleTapMenu,
                    onChecked = { on ->
                        draft = current.copy(
                            singleTapMenu = on,
                            doubleTapMenu = if (on) false else current.doubleTapMenu,
                        )
                    },
                )
                SettingsSwitchRow(
                    label = "Swipe from bottom to open menu",
                    checked = current.swipeFromBottomMenu,
                    onChecked = { on -> draft = current.copy(swipeFromBottomMenu = on) },
                )
            }

            CollapsibleSection(
                "Bookmarks",
                hint = "Last reading place, manage stored bookmarks per document, or clear every bookmark",
            ) {
                SettingsSwitchRow(
                    label = "Open last position",
                    checked = current.restoreLastPosition,
                    onChecked = { on -> draft = current.copy(restoreLastPosition = on) },
                )
                SettingsTapRow(label = "Manage bookmarks", onClick = { manageOpen = true })
                SettingsTapRow(label = "Clear bookmarks", onClick = { clearOpen = true })
            }

            CollapsibleSection(
                "Manage",
                hint = "Open more than one viewer, and inspect copies Papercut keeps",
            ) {
                SettingsSwitchRow(
                    label = "Allow multiple instances",
                    checked = current.allowMultipleInstances,
                    onChecked = { on -> draft = current.copy(allowMultipleInstances = on) },
                )
                SettingsTapRow(
                    label = "View stored files",
                    onClick = {
                        onRefreshStored()
                        storedOpen = true
                    },
                )
            }

            CollapsibleSection(
                "Export",
                hint = "Save or load settings and bookmarks as a Papercut JSON file",
            ) {
                SettingsTapRow(
                    label = "Export All",
                    onClick = { onExport(BackupJson.Kind.All) },
                    onShare = { onShareExport(BackupJson.Kind.All) },
                )
                SettingsTapRow(
                    label = "Export Settings",
                    onClick = { onExport(BackupJson.Kind.Settings) },
                    onShare = { onShareExport(BackupJson.Kind.Settings) },
                )
                SettingsTapRow(
                    label = "Export Bookmarks",
                    onClick = { onExport(BackupJson.Kind.Bookmarks) },
                    onShare = { onShareExport(BackupJson.Kind.Bookmarks) },
                )
                SettingsTapRow(
                    label = "Export Folio",
                    onClick = { onExport(BackupJson.Kind.Folio) },
                    onShare = { onShareExport(BackupJson.Kind.Folio) },
                )
                SettingsTapRow(label = "Import", onClick = onImport)
            }

            Spacer(Modifier.height(48.dp))
        }
    }

    if (colourPicker != null) {
        val title = when (colourPicker) {
            "bg" -> "Background"
            "hl" -> "Highlight"
            "border" -> "Active page border"
            "appfont" -> "Font"
            "action" -> "Action"
            else -> "Foreground"
        }
        val initial = when (colourPicker) {
            "bg" -> current.bgColor
            "hl" -> current.highlightColor
            "border" -> current.pageBorderColor
            "appfont" -> current.appFontColor
            "action" -> current.actionColor
            else -> current.fgColor
        }
        ColorPickerDialog(
            title = title,
            initialArgb = initial,
            onConfirm = { argb ->
                draft = when (colourPicker) {
                    "bg" -> current.copy(bgColor = argb)
                    "hl" -> current.copy(highlightColor = argb)
                    "border" -> current.copy(pageBorderColor = argb)
                    "appfont" -> current.copy(appFontColor = argb)
                    "action" -> current.copy(actionColor = argb)
                    else -> current.copy(fgColor = argb)
                }
                colourPicker = null
            },
            onDismiss = { colourPicker = null },
        )
    }

    val kind = paddingKind
    if (kind != null) {
        val initial = if (kind == "horizontal") current.horizontalPagePaddingDp else current.pagePaddingDp
        var text by remember(kind) { mutableStateOf(initial.toString()) }
        PapercutAlertDialog(
            onDismissRequest = { paddingKind = null },
            title = {
                Text(if (kind == "horizontal") "Horizontal page padding" else "Vertical page padding")
            },
            text = {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.filter { ch -> ch.isDigit() }.take(3) },
                    label = { Text("dp") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val value = text.toIntOrNull()?.coerceIn(0, 64) ?: 0
                        draft = if (kind == "horizontal") {
                            current.copy(horizontalPagePaddingDp = value)
                        } else {
                            current.copy(pagePaddingDp = value)
                        }
                        paddingKind = null
                    },
                ) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { paddingKind = null }) { Text("Cancel") }
            },
        )
    }

    if (manageOpen) {
        ManageDocumentsOverlay(
            documents = documents,
            onDismiss = { manageOpen = false },
            onDelete = onDeleteDocuments,
        )
    }
    }

    if (clearOpen) {
        PapercutAlertDialog(
            onDismissRequest = { clearOpen = false },
            title = { Text("Clear bookmarks") },
            text = { Text("Delete every bookmark for every document? Last reading places are kept.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        onClearBookmarks()
                        clearOpen = false
                    },
                ) { Text("Clear") }
            },
            dismissButton = {
                TextButton(onClick = { clearOpen = false }) { Text("Cancel") }
            },
        )
    }

    if (copyWarn) {
        PapercutAlertDialog(
            onDismissRequest = { copyWarn = false },
            text = {
                Text(
                    "This feature requires copying every opened PDF to application directory and overwriting the previous stored copy. This can cause write fatigue and also slows down the application slightly. It is advised to leave this option off",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        draft = current.copy(restoreLastPdf = true, openFolioOnStartup = false)
                        copyWarn = false
                    },
                ) { Text("Accept") }
            },
            dismissButton = {
                TextButton(onClick = { copyWarn = false }) { Text("Cancel") }
            },
        )
    }

    if (storedOpen) {
        StoredFilesOverlay(
            entries = stored,
            onDismiss = { storedOpen = false },
            onOpen = onPreviewStored,
            onShare = onShareStored,
            onDelete = { entries ->
                if (entries.any { it.inDatabase }) {
                    pendingDelete = entries
                } else {
                    onDeleteStored(entries)
                }
            },
        )
    }

    val deleting = pendingDelete
    if (deleting != null) {
        PapercutAlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete stored file") },
            text = {
                Text(
                    if (deleting.size == 1) {
                        "This file is in use. Delete it and remove that entry?"
                    } else {
                        "Some selected files are in use. Delete them and remove those entries?"
                    },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDeleteStored(deleting)
                        pendingDelete = null
                    },
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("Cancel") }
            },
        )
    }
    }
}

@Composable
private fun PictureProfileSliders(
    blend: Float,
    alpha: Float,
    onBlend: (Float) -> Unit,
    onAlpha: (Float) -> Unit,
) {
    val muted = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SliderLabel("Pictures", percent(blend))
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            ThinGreySlider(
                value = blend.coerceIn(0f, 1f),
                onValueChange = onBlend,
                valueRange = 0f..1f,
            )
            Row(Modifier.fillMaxWidth()) {
                Text("Themed", color = muted, fontSize = 16.sp, modifier = Modifier.weight(1f))
                Text("Original", color = muted, fontSize = 16.sp)
            }
        }
        SliderLabel("Alpha", percent(alpha))
        ThinGreySlider(
            value = alpha.coerceIn(0f, 1f),
            onValueChange = onAlpha,
            valueRange = 0f..1f,
        )
    }
}

@Composable
private fun SliderLabel(title: String, percent: String) {
    val on = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = on, fontSize = 16.sp, modifier = Modifier.weight(1f))
        Text(percent, color = on, fontSize = 16.sp)
    }
}

private fun percent(value: Float): String = "${(value.coerceIn(0f, 1f) * 100f).roundToInt()}%"

@Composable
private fun papercutFilterChipColors() = FilterChipDefaults.filterChipColors(
    labelColor = MaterialTheme.colorScheme.onBackground,
    selectedLabelColor = MaterialTheme.colorScheme.onBackground,
    selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
    disabledLabelColor = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.35f),
    disabledSelectedContainerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.4f),
)
