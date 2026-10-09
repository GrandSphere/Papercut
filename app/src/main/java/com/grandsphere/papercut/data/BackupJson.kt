package com.grandsphere.papercut.data

import org.json.JSONArray
import org.json.JSONObject

object BackupJson {
    const val FORMAT = "Papercut"
    const val SCHEMA_VERSION = 1

    enum class Kind { All, Settings, Bookmarks, Folio }

    fun export(kind: Kind, settings: ViewerSettings, documents: List<PdfDocument>, bookmarks: List<Bookmark>): String {
        val root = JSONObject()
            .put("format", FORMAT)
            .put("schemaVersion", SCHEMA_VERSION)
        if (kind == Kind.All || kind == Kind.Settings) {
            root.put("settings", settingsObject(settings))
        }
        if (kind == Kind.All || kind == Kind.Bookmarks) {
            root.put("documents", JSONArray().apply {
                documents.forEach { put(documentObject(it)) }
            })
            root.put("bookmarks", JSONArray().apply {
                bookmarks.forEach { put(bookmarkObject(it)) }
            })
        }
        return root.toString(2)
    }

    fun exportFolio(items: List<FolioItem>): String {
        val root = JSONObject()
            .put("format", FORMAT)
            .put("schemaVersion", SCHEMA_VERSION)
            .put("folio", JSONArray().apply {
                items.forEach { item ->
                    put(
                        JSONObject()
                            .put("uri", item.uri)
                            .put("displayName", item.displayName)
                            .put("displayPath", item.displayPath)
                            .put("sortOrder", item.sortOrder)
                            .put("hidden", item.hidden)
                            .put("isStartItem", item.isStartItem)
                            .put("zoomMode", item.zoomMode)
                            .put("customZoomPercent", item.customZoomPercent.toDouble())
                            .put("lockZoom", item.lockZoom)
                            .put("lockSideScroll", item.lockSideScroll)
                            .put("originalColors", item.originalColors)
                            .put("lastPageIndex", item.lastPageIndex)
                            .put("lastScrollXPdf", item.lastScrollXPdf.toDouble())
                            .put("lastScrollYPdf", item.lastScrollYPdf.toDouble()),
                    )
                }
            })
        return root.toString(2)
    }

    fun parse(text: String): Parsed {
        val root = JSONObject(text)
        val format = root.optString("format")
        if (format != FORMAT) {
            throw IllegalArgumentException("FORMAT")
        }
        return Parsed(
            settingsJson = if (root.has("settings") && !root.isNull("settings")) {
                root.getJSONObject("settings")
            } else {
                null
            },
            documents = if (root.has("documents") && !root.isNull("documents")) {
                jsonArray(root, "documents").mapNotNull { documentFrom(it) }
            } else {
                null
            },
            bookmarks = if (root.has("bookmarks") && !root.isNull("bookmarks")) {
                jsonArray(root, "bookmarks").mapNotNull { bookmarkFrom(it) }
            } else {
                null
            },
        )
    }

    data class Parsed(
        val settingsJson: JSONObject?,
        val documents: List<PdfDocument>?,
        val bookmarks: List<Bookmark>?,
    )

    fun mergeSettings(current: ViewerSettings, obj: JSONObject): ViewerSettings {
        return current.copy(
            fgColor = obj.intOr(current.fgColor, "fgColor"),
            bgColor = obj.intOr(current.bgColor, "bgColor"),
            imageColorBlend = obj.floatOr(current.imageColorBlend, "imageColorBlend"),
            rememberLastZoom = obj.boolOr(current.rememberLastZoom, "rememberLastZoom"),
            startupZoomMode = obj.stringOr(current.startupZoomMode, "startupZoomMode"),
            startupZoomPercent = obj.floatOr(current.startupZoomPercent, "startupZoomPercent"),
            restoreLastPdf = obj.boolOr(current.restoreLastPdf, "restoreLastPdf"),
            restoreLastPosition = obj.boolOr(current.restoreLastPosition, "restoreLastPosition"),
            fontScale = obj.floatOr(current.fontScale, "fontScale"),
            highlightColor = obj.intOr(current.highlightColor, "highlightColor"),
            pagePaddingDp = obj.intOr(current.pagePaddingDp, "pagePaddingDp"),
            horizontalPagePaddingDp = obj.intOr(current.horizontalPagePaddingDp, "horizontalPagePaddingDp"),
            pageBorderEnabled = obj.boolOr(current.pageBorderEnabled, "pageBorderEnabled"),
            pageBorderColor = obj.intOr(current.pageBorderColor, "pageBorderColor"),
            allowFollowLinks = obj.boolOr(current.allowFollowLinks, "allowFollowLinks"),
            doubleTapMenu = obj.boolOr(current.doubleTapMenu, "doubleTapMenu"),
            singleTapMenu = obj.boolOr(current.singleTapMenu, "singleTapMenu"),
            swipeFromBottomMenu = obj.boolOr(current.swipeFromBottomMenu, "swipeFromBottomMenu"),
            appFontColor = obj.intOr(current.appFontColor, "appFontColor"),
            actionColor = obj.intOr(current.actionColor, "actionColor"),
            twoFingerPan = obj.boolOr(current.twoFingerPan, "twoFingerPan"),
            autoDisablePan = obj.boolOr(current.autoDisablePan, "autoDisablePan"),
            allowTextSelection = obj.boolOr(current.allowTextSelection, "allowTextSelection"),
            alwaysDarkMode = obj.boolOr(current.alwaysDarkMode, "alwaysDarkMode"),
            imageContrast = obj.floatOr(current.imageContrast, "imageContrast"),
            imageAlpha = obj.floatOr(current.imageAlpha, "imageAlpha"),
            darkImageBlend = obj.floatOr(current.darkImageBlend, "darkImageBlend"),
            darkImageContrast = obj.floatOr(current.darkImageContrast, "darkImageContrast"),
            darkImageAlpha = obj.floatOr(current.darkImageAlpha, "darkImageAlpha"),
            openFolioOnStartup = obj.boolOr(current.openFolioOnStartup, "openFolioOnStartup"),
            folioOpenLastViewed = obj.boolOr(current.folioOpenLastViewed, "folioOpenLastViewed"),
            folioEnabled = obj.boolOr(current.folioEnabled, "folioEnabled"),
            folioStoreLastPosition = obj.boolOr(current.folioStoreLastPosition, "folioStoreLastPosition"),
            allowMultipleInstances = obj.boolOr(current.allowMultipleInstances, "allowMultipleInstances"),
        ).withExclusiveMenuTaps().withExclusiveStartup()
    }

    private fun settingsObject(s: ViewerSettings): JSONObject = JSONObject()
        .put("fgColor", s.fgColor)
        .put("bgColor", s.bgColor)
        .put("imageColorBlend", s.imageColorBlend.toDouble())
        .put("rememberLastZoom", s.rememberLastZoom)
        .put("startupZoomMode", s.startupZoomMode)
        .put("startupZoomPercent", s.startupZoomPercent.toDouble())
        .put("restoreLastPdf", s.restoreLastPdf)
        .put("restoreLastPosition", s.restoreLastPosition)
        .put("fontScale", s.fontScale.toDouble())
        .put("highlightColor", s.highlightColor)
        .put("pagePaddingDp", s.pagePaddingDp)
        .put("horizontalPagePaddingDp", s.horizontalPagePaddingDp)
        .put("pageBorderEnabled", s.pageBorderEnabled)
        .put("pageBorderColor", s.pageBorderColor)
        .put("allowFollowLinks", s.allowFollowLinks)
        .put("doubleTapMenu", s.doubleTapMenu)
        .put("singleTapMenu", s.singleTapMenu)
        .put("swipeFromBottomMenu", s.swipeFromBottomMenu)
        .put("appFontColor", s.appFontColor)
        .put("actionColor", s.actionColor)
        .put("twoFingerPan", s.twoFingerPan)
        .put("autoDisablePan", s.autoDisablePan)
        .put("allowTextSelection", s.allowTextSelection)
        .put("alwaysDarkMode", s.alwaysDarkMode)
        .put("imageContrast", s.imageContrast.toDouble())
        .put("imageAlpha", s.imageAlpha.toDouble())
        .put("darkImageBlend", s.darkImageBlend.toDouble())
        .put("darkImageContrast", s.darkImageContrast.toDouble())
        .put("darkImageAlpha", s.darkImageAlpha.toDouble())
        .put("openFolioOnStartup", s.openFolioOnStartup)
        .put("folioOpenLastViewed", s.folioOpenLastViewed)
        .put("folioEnabled", s.folioEnabled)
        .put("folioStoreLastPosition", s.folioStoreLastPosition)
        .put("allowMultipleInstances", s.allowMultipleInstances)

    private fun documentObject(d: PdfDocument): JSONObject = JSONObject()
        .put("uri", d.uri)
        .put("displayName", d.displayName)
        .put("displayPath", d.displayPath)
        .put("lastPageIndex", d.lastPageIndex)
        .put("lastScrollXPdf", d.lastScrollXPdf.toDouble())
        .put("lastScrollYPdf", d.lastScrollYPdf.toDouble())

    private fun documentFrom(obj: JSONObject): PdfDocument? {
        val uri = obj.stringOr("", "uri")
        if (uri.isBlank()) return null
        return PdfDocument(
            uri = uri,
            displayName = obj.stringOr("document.pdf", "displayName"),
            displayPath = obj.stringOr(uri, "displayPath"),
            lastPageIndex = obj.intOr(0, "lastPageIndex"),
            lastScrollXPdf = obj.floatOr(0f, "lastScrollXPdf"),
            lastScrollYPdf = obj.floatOr(0f, "lastScrollYPdf"),
        )
    }

    private fun bookmarkObject(b: Bookmark): JSONObject = JSONObject()
        .put("documentUri", b.documentUri)
        .put("name", b.name)
        .put("pageIndex", b.pageIndex)
        .put("scrollXPdf", b.scrollXPdf.toDouble())
        .put("scrollYPdf", b.scrollYPdf.toDouble())
        .put("sortOrder", b.sortOrder)

    private fun bookmarkFrom(obj: JSONObject): Bookmark? {
        val documentUri = obj.stringOr("", "documentUri")
        if (documentUri.isBlank()) return null
        return Bookmark(
            documentUri = documentUri,
            name = obj.stringOr("Bookmark", "name"),
            pageIndex = obj.intOr(0, "pageIndex"),
            scrollXPdf = obj.floatOr(0f, "scrollXPdf"),
            scrollYPdf = obj.floatOr(0f, "scrollYPdf"),
            sortOrder = obj.intOr(0, "sortOrder"),
        )
    }

    private fun jsonArray(root: JSONObject, key: String): List<JSONObject> {
        val arr = root.optJSONArray(key) ?: return emptyList()
        return buildList {
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                add(item)
            }
        }
    }

    private fun JSONObject.intOr(default: Int, vararg keys: String): Int {
        val key = keys.firstOrNull { has(it) && !isNull(it) } ?: return default
        return optInt(key, default)
    }

    private fun JSONObject.floatOr(default: Float, vararg keys: String): Float {
        val key = keys.firstOrNull { has(it) && !isNull(it) } ?: return default
        return optDouble(key, default.toDouble()).toFloat()
    }

    private fun JSONObject.boolOr(default: Boolean, vararg keys: String): Boolean {
        val key = keys.firstOrNull { has(it) && !isNull(it) } ?: return default
        return optBoolean(key, default)
    }

    private fun JSONObject.stringOr(default: String, vararg keys: String): String {
        val key = keys.firstOrNull { has(it) && !isNull(it) } ?: return default
        return optString(key, default).ifBlank { default }
    }
}
