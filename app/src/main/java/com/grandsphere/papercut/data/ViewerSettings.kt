package com.grandsphere.papercut.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "settings")
data class ViewerSettings(
    @PrimaryKey val id: Int = 1,
    val fgColor: Int = DEFAULT_FG,
    val bgColor: Int = DEFAULT_BG,
    val imageColorBlend: Float = 0f,
    val zoomMode: String = ZoomMode.FIT_WIDTH.name,
    val customZoomPercent: Float = 100f,
    val restoreLastPdf: Boolean = false,
    val lastPdfUri: String? = null,
    val rememberLastZoom: Boolean = false,
    val startupZoomMode: String = ZoomMode.FIT_WIDTH.name,
    val startupZoomPercent: Float = 100f,
    val lockZoom: Boolean = false,
    val lockSideScroll: Boolean = false,
    val fontScale: Float = 0.85f,
    val highlightColor: Int = DEFAULT_HIGHLIGHT,
    val pagePaddingDp: Int = 0,
    val horizontalPagePaddingDp: Int = 0,
    val pageBorderEnabled: Boolean = false,
    val pageBorderColor: Int = DEFAULT_PAGE_BORDER,
    val allowFollowLinks: Boolean = true,
    val doubleTapMenu: Boolean = false,
    val singleTapMenu: Boolean = true,
    val swipeFromBottomMenu: Boolean = true,
    val appFontColor: Int = DEFAULT_APP_FONT,
    val actionColor: Int = DEFAULT_ACTION,
    val originalColors: Boolean = false,
    val twoFingerPan: Boolean = false,
    val autoDisablePan: Boolean = true,
    val allowTextSelection: Boolean = true,
    val restoreLastPosition: Boolean = true,
    val alwaysDarkMode: Boolean = false,
    val imageContrast: Float = 1f,
    val imageAlpha: Float = 1f,
    val darkImageBlend: Float = 0f,
    val darkImageContrast: Float = 0.20f,
    val darkImageAlpha: Float = 0.20f,
    val lastPageIndex: Int = 0,
    val lastScrollXPdf: Float = 0f,
    val lastScrollYPdf: Float = 0f,
    val folioEnabled: Boolean = false,
    val openFolioOnStartup: Boolean = false,
    val folioOpenLastViewed: Boolean = true,
    val folioStoreLastPosition: Boolean = true,
    val allowMultipleInstances: Boolean = false,
    val lastFolioItemId: Long = 0L,
) {
    fun lastMode(): ZoomMode = ZoomMode.fromStorage(zoomMode)
    fun startupMode(): ZoomMode = ZoomMode.fromStorage(startupZoomMode)

    fun effectiveMode(): ZoomMode =
        if (rememberLastZoom) lastMode() else startupMode()

    fun effectivePercent(): Float =
        if (rememberLastZoom) customZoomPercent else startupZoomPercent

    fun withExclusiveMenuTaps(): ViewerSettings =
        if (doubleTapMenu && singleTapMenu) copy(singleTapMenu = false) else this

    fun withExclusiveStartup(): ViewerSettings {
        var next = this
        if (!next.folioEnabled) next = next.copy(openFolioOnStartup = false)
        if (next.openFolioOnStartup && next.restoreLastPdf) {
            next = next.copy(restoreLastPdf = false)
        }
        return next
    }

    companion object {
        const val DEFAULT_FG: Int = 0xFFB3B3B3.toInt()
        const val DEFAULT_BG: Int = 0xFF000000.toInt()
        const val DEFAULT_HIGHLIGHT: Int = 0xFFB39DDB.toInt()
        const val DEFAULT_PAGE_BORDER: Int = 0xFFB39DDB.toInt()
        const val DEFAULT_APP_FONT: Int = 0xFFE8EAED.toInt()
        const val DEFAULT_ACTION: Int = 0xFFE6D6F5.toInt()
    }
}
