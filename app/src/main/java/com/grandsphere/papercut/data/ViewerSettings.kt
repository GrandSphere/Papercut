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
    val allowFollowLinks: Boolean = false,
    val doubleTapMenu: Boolean = false,
    val singleTapMenu: Boolean = true,
    val swipeFromBottomMenu: Boolean = false,
    val appFontColor: Int = DEFAULT_APP_FONT,
    val actionColor: Int = DEFAULT_ACTION,
    val originalColors: Boolean = false,
    val twoFingerPan: Boolean = false,
    val autoDisablePan: Boolean = false,
    val allowTextSelection: Boolean = false,
    val restoreLastPosition: Boolean = false,
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
    val folioOpenLastViewed: Boolean = false,
    val folioStoreLastPosition: Boolean = false,
    val allowMultipleInstances: Boolean = false,
    val lastFolioItemId: Long = 0L,
    val penModeEnabled: Boolean = false,
    val penIgnoresScrollLock: Boolean = false,
    val penOpenMenuOnTap: Boolean = false,
    val penButtonPress: String = PenButtonAction.OPEN_BOOKMARKS.name,
    val penButtonHold: String = PenButtonAction.ADD_QUICK_BOOKMARK.name,
    val airActionButtonPress: String = PenButtonAction.OPEN_BOOKMARKS.name,
    val airActionButtonHold: String = PenButtonAction.ADD_QUICK_BOOKMARK.name,
    val penDoubleTapEnabled: Boolean = false,
    val penButtonDouble: String = PenButtonAction.OPEN_BOOKMARKS.name,
    val airActionButtonDouble: String = PenButtonAction.OPEN_BOOKMARKS.name,
    val penAllowTextSelection: Boolean = false,
    val penFollowLinks: Boolean = false,
    val penScreenTap: String = PenButtonAction.DO_NOTHING.name,
    val penScreenDouble: String = PenButtonAction.DO_NOTHING.name,
    val penScreenHold: String = PenButtonAction.DO_NOTHING.name,
    val showJumpOnMenubar: Boolean = false,
    val quickJumpEnabled: Boolean = false,
    val penDoubleTapPeriodMs: Int = DEFAULT_PEN_DOUBLE_TAP_MS,
    val penHoldPeriodMs: Int = DEFAULT_PEN_HOLD_MS,
) {
    fun penPressAction(): PenButtonAction =
        PenButtonAction.fromStorage(penButtonPress, PenButtonAction.OPEN_BOOKMARKS)

    fun penHoldAction(): PenButtonAction =
        PenButtonAction.fromStorage(penButtonHold, PenButtonAction.ADD_QUICK_BOOKMARK)

    fun airPressAction(): PenButtonAction =
        PenButtonAction.fromStorage(airActionButtonPress, PenButtonAction.OPEN_BOOKMARKS)

    fun airHoldAction(): PenButtonAction =
        PenButtonAction.fromStorage(airActionButtonHold, PenButtonAction.ADD_QUICK_BOOKMARK)

    fun penDoubleAction(): PenButtonAction =
        PenButtonAction.fromStorage(penButtonDouble, PenButtonAction.OPEN_BOOKMARKS)

    fun airDoubleAction(): PenButtonAction =
        PenButtonAction.fromStorage(airActionButtonDouble, PenButtonAction.OPEN_BOOKMARKS)

    fun penTapAction(): PenButtonAction =
        PenButtonAction.fromStorage(penScreenTap, PenButtonAction.DO_NOTHING)

    fun penScreenDoubleAction(): PenButtonAction =
        PenButtonAction.fromStorage(penScreenDouble, PenButtonAction.DO_NOTHING)

    fun penScreenHoldAction(): PenButtonAction =
        PenButtonAction.fromStorage(penScreenHold, PenButtonAction.DO_NOTHING)
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
        const val DEFAULT_PEN_DOUBLE_TAP_MS: Int = 300
        const val DEFAULT_PEN_HOLD_MS: Int = 400
        const val MIN_PEN_TIMING_MS: Int = 50
        const val MAX_PEN_TIMING_MS: Int = 2000

        fun clampPenTimingMs(value: Int): Int =
            value.coerceIn(MIN_PEN_TIMING_MS, MAX_PEN_TIMING_MS)

        fun parsePenTimingMs(text: String, default: Int): Int {
            val value = text.toIntOrNull() ?: return default
            return clampPenTimingMs(value)
        }
    }
}
