package com.grandsphere.papercut.pdf

data class PageInfo(
    val width: Float,
    val height: Float,
    val x0: Float,
    val y0: Float
)

data class CharBox(
    val c: Char,
    val ulx: Float,
    val uly: Float,
    val urx: Float,
    val ury: Float,
    val llx: Float,
    val lly: Float,
    val lrx: Float,
    val lry: Float
) {
    fun contains(x: Float, y: Float): Boolean {
        val minX = minOf(ulx, urx, llx, lrx)
        val maxX = maxOf(ulx, urx, llx, lrx)
        val minY = minOf(uly, ury, lly, lry)
        val maxY = maxOf(uly, ury, lly, lry)
        return x in minX..maxX && y in minY..maxY
    }
}

data class PageLink(
    val bounds: android.graphics.RectF,
    val uri: String,
    val isExternal: Boolean
)

data class OutlineItem(
    val title: String,
    val pageIndex: Int,
    val y: Float?,
    val depth: Int
)

data class PageContent(
    val chars: List<CharBox>,
    val imageRects: List<android.graphics.RectF>,
    val links: List<PageLink> = emptyList()
)
