package com.grandsphere.papercut.pdf

import kotlin.math.roundToInt

object ColorRemapper {
    private val LUM_R = FloatArray(256) { 0.299f * it }
    private val LUM_G = FloatArray(256) { 0.587f * it }
    private val LUM_B = FloatArray(256) { 0.114f * it }

    /** Remaps opaque ARGB [pixels] (w*h) in place. [imageRects] are in pixel coordinates. */
    fun remap(
        pixels: IntArray,
        w: Int,
        h: Int,
        fg: Int,
        bg: Int,
        imageBlend: Float,
        imageRects: List<PdfRect>,
        imageAlpha: Float = 1f,
    ) {
        if (w <= 0 || h <= 0) return

        val fgR = (fg shr 16) and 0xFF
        val fgG = (fg shr 8) and 0xFF
        val fgB = fg and 0xFF
        val bgR = (bg shr 16) and 0xFF
        val bgG = (bg shr 8) and 0xFF
        val bgB = bg and 0xFF
        val dR = bgR - fgR
        val dG = bgG - fgG
        val dB = bgB - fgB

        val blend = imageBlend.coerceIn(0f, 1f)
        val alpha = imageAlpha.coerceIn(0f, 1f)
        val doBlend = blend > 0f
        val doAlpha = alpha < 0.999f
        val mask = if ((doBlend || doAlpha) && imageRects.isNotEmpty()) buildMask(imageRects, w, h) else null

        var lastRgb = -1
        var lastInside = false
        var lastOut = 0
        for (i in pixels.indices) {
            val rgb = pixels[i] and 0xFFFFFF
            val inside = mask != null && mask[i].toInt() != 0
            if (rgb == lastRgb && inside == lastInside) {
                pixels[i] = lastOut
                continue
            }
            val r = rgb shr 16
            val g = (rgb shr 8) and 0xFF
            val b = rgb and 0xFF

            val lum = (LUM_R[r] + LUM_G[g] + LUM_B[b]) / 255f
            var nr = (fgR + dR * lum).toInt()
            var ng = (fgG + dG * lum).toInt()
            var nb = (fgB + dB * lum).toInt()

            if (inside) {
                if (doBlend) {
                    nr = (nr + (r - nr) * blend).toInt()
                    ng = (ng + (g - ng) * blend).toInt()
                    nb = (nb + (b - nb) * blend).toInt()
                }
                if (doAlpha) {
                    nr = (bgR + (nr - bgR) * alpha).toInt()
                    ng = (bgG + (ng - bgG) * alpha).toInt()
                    nb = (bgB + (nb - bgB) * alpha).toInt()
                }
            }

            val out = (0xFF shl 24) or
                (nr.coerceIn(0, 255) shl 16) or
                (ng.coerceIn(0, 255) shl 8) or
                nb.coerceIn(0, 255)
            pixels[i] = out
            lastRgb = rgb
            lastInside = inside
            lastOut = out
        }
    }

    private fun buildMask(rects: List<PdfRect>, w: Int, h: Int): ByteArray {
        val mask = ByteArray(w * h)
        for (r in rects) {
            val left = r.left.roundToInt().coerceAtLeast(0)
            val top = r.top.roundToInt().coerceAtLeast(0)
            val right = r.right.roundToInt().coerceAtMost(w)
            val bottom = r.bottom.roundToInt().coerceAtMost(h)
            for (y in top until bottom) {
                val row = y * w
                for (x in left until right) mask[row + x] = 1
            }
        }
        return mask
    }
}
