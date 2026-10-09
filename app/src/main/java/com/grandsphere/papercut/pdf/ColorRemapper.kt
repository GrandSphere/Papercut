package com.grandsphere.papercut.pdf

import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.RectF
import kotlin.math.roundToInt

object ColorRemapper {
    fun remapInPlace(
        bitmap: Bitmap,
        fg: Int,
        bg: Int,
        imageBlend: Float,
        imageRects: List<RectF>,
        imageAlpha: Float = 1f,
    ) {
        val w = bitmap.width
        val h = bitmap.height
        if (w <= 0 || h <= 0) return

        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)

        val fgR = (fg shr 16) and 0xFF
        val fgG = (fg shr 8) and 0xFF
        val fgB = fg and 0xFF
        val bgR = (bg shr 16) and 0xFF
        val bgG = (bg shr 8) and 0xFF
        val bgB = bg and 0xFF

        val blend = imageBlend.coerceIn(0f, 1f)
        val alpha = imageAlpha.coerceIn(0f, 1f)
        val needImages = (blend > 0f || alpha < 0.999f) && imageRects.isNotEmpty()
        val rects: List<Rect> = if (needImages) {
            imageRects.map { r ->
                Rect(
                    r.left.roundToInt().coerceAtLeast(0),
                    r.top.roundToInt().coerceAtLeast(0),
                    r.right.roundToInt().coerceAtMost(w),
                    r.bottom.roundToInt().coerceAtMost(h)
                )
            }
        } else {
            emptyList()
        }

        for (i in pixels.indices) {
            val p = pixels[i]
            val a = (p ushr 24) and 0xFF
            var r = (p shr 16) and 0xFF
            var g = (p shr 8) and 0xFF
            var b = p and 0xFF

            if (a < 255) {
                val ia = 255 - a
                r = (r * a + 255 * ia) / 255
                g = (g * a + 255 * ia) / 255
                b = (b * a + 255 * ia) / 255
            }

            val lum = (0.299f * r + 0.587f * g + 0.114f * b) / 255f
            var nr = (fgR + (bgR - fgR) * lum).toInt()
            var ng = (fgG + (bgG - fgG) * lum).toInt()
            var nb = (fgB + (bgB - fgB) * lum).toInt()

            if (rects.isNotEmpty()) {
                val x = i % w
                val y = i / w
                if (rects.any { it.contains(x, y) }) {
                    if (blend > 0f) {
                        nr = (nr + (r - nr) * blend).toInt()
                        ng = (ng + (g - ng) * blend).toInt()
                        nb = (nb + (b - nb) * blend).toInt()
                    }
                    if (alpha < 0.999f) {
                        nr = (bgR + (nr - bgR) * alpha).toInt()
                        ng = (bgG + (ng - bgG) * alpha).toInt()
                        nb = (bgB + (nb - bgB) * alpha).toInt()
                    }
                }
            }

            pixels[i] = (0xFF shl 24) or
                (nr.coerceIn(0, 255) shl 16) or
                (ng.coerceIn(0, 255) shl 8) or
                nb.coerceIn(0, 255)
        }

        bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
    }
}
