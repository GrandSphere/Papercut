package com.grandsphere.papercut.pdf

import org.json.JSONArray
import kotlin.math.max
import kotlin.math.min

object PageRotation {
    fun norm(degrees: Int): Int {
        var d = degrees % 360
        if (d < 0) d += 360
        return d
    }

    fun display(info: PageInfo, degrees: Int): PageInfo {
        val r = norm(degrees)
        return if (r == 90 || r == 270) {
            info.copy(width = info.height, height = info.width)
        } else {
            info
        }
    }

    fun apply(pages: List<PageInfo>, rotations: IntArray): List<PageInfo> {
        if (pages.isEmpty()) return pages
        return pages.mapIndexed { i, page ->
            display(page, rotations.getOrElse(i) { 0 })
        }
    }

    fun mapPoint(x: Float, y: Float, orig: PageInfo, degrees: Int): Pair<Float, Float> {
        val relX = x - orig.x0
        val relY = y - orig.y0
        val (nx, ny) = when (norm(degrees)) {
            90 -> orig.height - relY to relX
            180 -> orig.width - relX to orig.height - relY
            270 -> relY to orig.width - relX
            else -> relX to relY
        }
        return orig.x0 + nx to orig.y0 + ny
    }

    fun rotateChar(box: CharBox, orig: PageInfo, degrees: Int): CharBox {
        val r = norm(degrees)
        if (r == 0) return box
        val ul = mapPoint(box.ulx, box.uly, orig, r)
        val ur = mapPoint(box.urx, box.ury, orig, r)
        val ll = mapPoint(box.llx, box.lly, orig, r)
        val lr = mapPoint(box.lrx, box.lry, orig, r)
        return box.copy(
            ulx = ul.first, uly = ul.second,
            urx = ur.first, ury = ur.second,
            llx = ll.first, lly = ll.second,
            lrx = lr.first, lry = lr.second,
        )
    }

    fun rotateRect(rect: PdfRect, orig: PageInfo, degrees: Int): PdfRect {
        val r = norm(degrees)
        if (r == 0) return rect
        val a = mapPoint(rect.left, rect.top, orig, r)
        val b = mapPoint(rect.right, rect.bottom, orig, r)
        val c = mapPoint(rect.left, rect.bottom, orig, r)
        val d = mapPoint(rect.right, rect.top, orig, r)
        return PdfRect(
            min(min(a.first, b.first), min(c.first, d.first)),
            min(min(a.second, b.second), min(c.second, d.second)),
            max(max(a.first, b.first), max(c.first, d.first)),
            max(max(a.second, b.second), max(c.second, d.second)),
        )
    }

    fun rotateContent(content: PageContent, orig: PageInfo, degrees: Int): PageContent {
        val r = norm(degrees)
        if (r == 0) return content
        return PageContent(
            chars = content.chars.map { rotateChar(it, orig, r) },
            imageRects = content.imageRects.map { rotateRect(it, orig, r) },
            links = content.links.map { it.copy(bounds = rotateRect(it.bounds, orig, r)) },
        )
    }

    fun encode(rotations: IntArray): String {
        val arr = JSONArray()
        rotations.forEach { arr.put(norm(it)) }
        return arr.toString()
    }

    fun decode(json: String?, count: Int): IntArray {
        val out = IntArray(count)
        if (json.isNullOrBlank() || count <= 0) return out
        val arr = runCatching { JSONArray(json) }.getOrNull() ?: return out
        for (i in 0 until min(count, arr.length())) {
            out[i] = norm(arr.optInt(i, 0))
        }
        return out
    }
}
