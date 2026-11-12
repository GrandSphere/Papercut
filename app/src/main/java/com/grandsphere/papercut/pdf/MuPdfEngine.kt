package com.grandsphere.papercut.pdf

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.artifex.mupdf.fitz.ColorSpace
import com.artifex.mupdf.fitz.Document
import com.artifex.mupdf.fitz.Font
import com.artifex.mupdf.fitz.Image
import com.artifex.mupdf.fitz.Link
import com.artifex.mupdf.fitz.Matrix
import com.artifex.mupdf.fitz.Outline
import com.artifex.mupdf.fitz.Pixmap
import com.artifex.mupdf.fitz.Point
import com.artifex.mupdf.fitz.Quad
import com.artifex.mupdf.fitz.Rect
import com.artifex.mupdf.fitz.StructuredTextWalker
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Collections
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MuPdfEngine(
    sharedExecutor: ExecutorService? = null,
) {
    private val ownsExecutor = sharedExecutor == null
    private val executor = sharedExecutor ?: Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "mupdf").apply { isDaemon = true }
    }
    private val dispatcher = executor.asCoroutineDispatcher()

    private var document: Document? = null
    private var pfd: ParcelFileDescriptor? = null
    private var awaitingPassword = false
    private var openStartPage = 0
    private var layoutEm = 12f
    private var openedMime: String = DocumentMagic.PDF
    private val pageInfos = mutableListOf<PageInfo>()
    private var measured = BooleanArray(0)
    private var measuredCount = 0
    private val contentCache = HashMap<Int, PageContent>()
    private var outlineCache: List<OutlineItem>? = null

    val pages: List<PageInfo>
        get() = pageInfos

    val mime: String
        get() = openedMime

    fun isOpened(): Boolean = document != null

    suspend fun open(context: Context, uri: Uri, fontScale: Float, startPage: Int = 0) =
        withContext(dispatcher) {
            beginOpenLocked(fontScale, startPage)
            val app = context.applicationContext
            val opened = DocumentMagic.openDescriptor(app, uri)
            val magic = try {
                DocumentMagic.resolve(app, uri, opened)
            } catch (t: Throwable) {
                runCatching { opened.close() }
                throw t
            }
            openPfdLocked(opened, magic)
        }

    suspend fun openFile(file: File, fontScale: Float, startPage: Int = 0) =
        withContext(dispatcher) {
            beginOpenLocked(fontScale, startPage)
            val opened = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            val magic = try {
                DocumentMagic.resolve(file, opened)
            } catch (t: Throwable) {
                runCatching { opened.close() }
                throw t
            }
            openPfdLocked(opened, magic)
        }

    private fun beginOpenLocked(fontScale: Float, startPage: Int) {
        closeLocked()
        layoutEm = layoutEm(fontScale)
        openStartPage = startPage
    }

    private fun openPfdLocked(opened: ParcelFileDescriptor, magic: String) {
        pfd = opened
        openedMime = magic
        val doc = Document.openDocument(PfdSeekableStream(opened), magic)
        document = doc
        if (doc.needsPassword()) {
            awaitingPassword = true
            error("PASSWORD")
        }
        awaitingPassword = false
        finishOpenLocked(doc)
    }

    suspend fun authenticatePassword(password: String): Boolean = withContext(dispatcher) {
        val doc = document ?: return@withContext false
        val ok = runCatching { doc.authenticatePassword(password) }.getOrDefault(false)
        if (!ok) return@withContext false
        awaitingPassword = false
        finishOpenLocked(doc)
        true
    }

    suspend fun cancelPassword() = withContext(dispatcher) {
        if (awaitingPassword) closeLocked()
    }

    private fun finishOpenLocked(doc: Document) {
        if (doc.isReflowable) {
            doc.layout(REFLOW_WIDTH, REFLOW_HEIGHT, layoutEm)
        }
        loadStartPageLocked(doc, openStartPage)
    }

    private fun loadStartPageLocked(doc: Document, startPage: Int) {
        val count = doc.countPages().coerceAtLeast(0)
        pageInfos.clear()
        contentCache.clear()
        outlineCache = null
        measured = BooleanArray(count)
        measuredCount = 0
        if (count <= 0) return
        val start = startPage.coerceIn(0, count - 1)
        val seed = measurePageLocked(doc, start)
        pageInfos.addAll(Collections.nCopies(count, seed))
        markMeasured(start)
    }

    private fun markMeasured(index: Int) {
        if (!measured[index]) {
            measured[index] = true
            measuredCount++
        }
    }

    private fun ensureMeasuredLocked(doc: Document, index: Int) {
        if (index in measured.indices && !measured[index]) {
            pageInfos[index] = measurePageLocked(doc, index)
            markMeasured(index)
        }
    }

    private fun measurePageLocked(doc: Document, index: Int): PageInfo {
        val page = doc.loadPage(index)
        try {
            val b = page.bounds
            return PageInfo(
                width = b.x1 - b.x0,
                height = b.y1 - b.y0,
                x0 = b.x0,
                y0 = b.y0,
            )
        } finally {
            page.destroy()
        }
    }

    suspend fun ensurePageSize(index: Int): PageInfo? = withContext(dispatcher) {
        val doc = document ?: return@withContext null
        if (index !in pageInfos.indices) return@withContext null
        ensureMeasuredLocked(doc, index)
        pageInfos[index]
    }

    suspend fun measureRemainingPages(): List<PageInfo> = withContext(dispatcher) {
        val doc = document ?: return@withContext pageInfos.toList()
        if (measuredCount < pageInfos.size) {
            for (i in pageInfos.indices) ensureMeasuredLocked(doc, i)
        }
        pageInfos.toList()
    }

    suspend fun render(
        index: Int,
        targetWidth: Int,
        fg: Int,
        bg: Int,
        imageBlend: Float,
        originalColors: Boolean = false,
        imageAlpha: Float = 1f,
        urgent: Boolean = false,
        rotateCw: Int = 0,
    ): Bitmap = withContext(dispatcher) {
        val doc = document ?: error("No document")
        ensureMeasuredLocked(doc, index)
        val info = pageInfos.getOrNull(index) ?: error("No document")
        val extra = PageRotation.norm(rotateCw)
        val display = PageRotation.display(info, extra)
        var usedScale = targetWidth.coerceIn(1, 4096) / display.width.coerceAtLeast(1f)
        val width = (info.width * usedScale).toInt().coerceAtLeast(1)
        val height = (info.height * usedScale).toInt().coerceAtLeast(1)
        if (width.toLong() * height > MAX_RENDER_PIXELS) {
            usedScale *= kotlin.math.sqrt(MAX_RENDER_PIXELS.toDouble() / (width.toDouble() * height)).toFloat()
        }
        val bmp = renderLocked(doc, index, info, usedScale, fg, bg, imageBlend, originalColors, imageAlpha)
        rotateBitmap(bmp, extra)
    }

    private fun rotateBitmap(src: Bitmap, rotateCw: Int): Bitmap {
        val extra = PageRotation.norm(rotateCw)
        if (extra == 0) return src
        val matrix = android.graphics.Matrix()
        matrix.postRotate(extra.toFloat())
        val out = Bitmap.createBitmap(src, 0, 0, src.width, src.height, matrix, true)
        if (out !== src && !src.isRecycled) src.recycle()
        return out
    }

    private fun renderLocked(
        doc: Document,
        index: Int,
        info: PageInfo,
        usedScale: Float,
        fg: Int,
        bg: Int,
        imageBlend: Float,
        originalColors: Boolean,
        imageAlpha: Float,
    ): Bitmap {
        val ctm = Matrix(usedScale, usedScale)
        ctm.e = -info.x0 * usedScale
        ctm.f = -info.y0 * usedScale
        val page = doc.loadPage(index)
        val pixmap = try {
            page.toPixmap(ctm, ColorSpace.DeviceRGB, true, true)
        } finally {
            page.destroy()
        }
        try {
            val w = pixmap.width.coerceAtLeast(1)
            val h = pixmap.height.coerceAtLeast(1)
            val pixels = pixmapToPixels(pixmap, w, h)
            if (!originalColors) {
                val needImageRects = imageBlend > 0.001f || imageAlpha < 0.999f
                val imageRects = if (needImageRects) {
                    contentLocked(index).imageRects.map { src ->
                        PdfRect(
                            (src.left - info.x0) * usedScale,
                            (src.top - info.y0) * usedScale,
                            (src.right - info.x0) * usedScale,
                            (src.bottom - info.y0) * usedScale,
                        )
                    }
                } else {
                    emptyList()
                }
                ColorRemapper.remap(pixels, w, h, fg, bg, imageBlend, imageRects, imageAlpha)
            }
            val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
            return bitmap
        } finally {
            pixmap.destroy()
        }
    }

    suspend fun outline(): List<OutlineItem> = withContext(dispatcher) {
        val doc = document ?: return@withContext emptyList()
        outlineCache ?: flattenOutlineLocked(doc).also { outlineCache = it }
    }

    suspend fun resolveUri(uri: String): Pair<Int, Float?>? = withContext(dispatcher) {
        val doc = document ?: return@withContext null
        pageFromUri(uri)?.let { page ->
            if (page in pageInfos.indices) return@withContext page to null
        }
        if (Link.isExternal(uri)) return@withContext null
        val loc = runCatching { doc.resolveLink(uri) }.getOrNull() ?: return@withContext null
        val page = doc.pageNumberFromLocation(loc)
        if (page < 0) return@withContext null
        val y = runCatching { doc.resolveLinkDestination(uri).y }.getOrNull()
        page to y
    }

    suspend fun pageContent(index: Int): PageContent = withContext(dispatcher) {
        contentLocked(index)
    }

    private fun flattenOutlineLocked(doc: Document): List<OutlineItem> {
        val root = runCatching { doc.loadOutline() }.getOrNull() ?: return emptyList()
        val out = ArrayList<OutlineItem>()
        fun walk(items: Array<Outline>?, depth: Int) {
            if (items == null) return
            for (item in items) {
                val loc = runCatching { doc.resolveLink(item) }.getOrNull()
                var page = if (loc != null) doc.pageNumberFromLocation(loc) else -1
                if (page < 0) page = pageFromUri(item.uri) ?: -1
                val y = runCatching { doc.resolveLinkDestination(item).y }.getOrNull()
                out += OutlineItem(
                    title = item.title.orEmpty().ifBlank { "Untitled" },
                    pageIndex = page,
                    y = y,
                    depth = depth,
                )
                walk(item.down, depth + 1)
            }
        }
        walk(root, 0)
        return out
    }

    private fun pageFromUri(uri: String?): Int? {
        if (uri.isNullOrBlank()) return null
        val match = PAGE_PARAM.find(uri) ?: PAGE_FRAGMENT.find(uri)
        val n = match?.groupValues?.get(1)?.toIntOrNull() ?: return null
        return (n - 1).takeIf { it >= 0 }
    }

    private fun contentLocked(index: Int): PageContent {
        contentCache[index]?.let { return it }
        val doc = document ?: return PageContent(emptyList(), emptyList())
        val page = doc.loadPage(index)
        val chars = ArrayList<CharBox>()
        val images = ArrayList<PdfRect>()
        val links = ArrayList<PageLink>()
        try {
            page.getLinks()?.forEach { link ->
                val b = link.bounds ?: return@forEach
                val uri = link.uri ?: return@forEach
                links += PageLink(
                    bounds = PdfRect(
                        minOf(b.x0, b.x1),
                        minOf(b.y0, b.y1),
                        maxOf(b.x0, b.x1),
                        maxOf(b.y0, b.y1),
                    ),
                    uri = uri,
                    isExternal = Link.isExternal(uri),
                )
            }
        } catch (_: Throwable) {
        }
        val st = try {
            page.toStructuredText("preserve-images,preserve-whitespace")
        } finally {
            page.destroy()
        }
        try {
            st.walk(object : StructuredTextWalker {
                override fun onImageBlock(bbox: Rect?, transform: Matrix?, image: Image?) {
                    if (bbox != null) {
                        images += PdfRect(bbox.x0, bbox.y0, bbox.x1, bbox.y1)
                    }
                }

                override fun beginTextBlock(bbox: Rect?, flags: Int) {}
                override fun endTextBlock() {}
                override fun beginLine(bbox: Rect?, wmode: Int, dir: Point?) {}
                override fun endLine() {}

                override fun onChar(
                    c: Int,
                    origin: Point?,
                    font: Font?,
                    size: Float,
                    q: Quad?,
                    argb: Int,
                    flags: Int,
                    bidi: Int
                ) {
                    if (q == null) return
                    chars += CharBox(
                        c = c.toChar(),
                        ulx = q.ul_x,
                        uly = q.ul_y,
                        urx = q.ur_x,
                        ury = q.ur_y,
                        llx = q.ll_x,
                        lly = q.ll_y,
                        lrx = q.lr_x,
                        lry = q.lr_y
                    )
                }

                override fun beginStruct(standard: String?, raw: String?, index: Int) {}
                override fun endStruct() {}
                override fun onVector(bbox: Rect?, info: StructuredTextWalker.VectorInfo?, argb: Int) {}
            })
        } finally {
            st.destroy()
        }
        val content = PageContent(chars, images, links)
        contentCache[index] = content
        return content
    }

    suspend fun close() = withContext(dispatcher) {
        closeLocked()
    }

    fun shutdown() {
        executor.execute { closeLocked() }
        if (ownsExecutor) executor.shutdown()
    }

    private fun closeLocked() {
        contentCache.clear()
        outlineCache = null
        pageInfos.clear()
        measured = BooleanArray(0)
        measuredCount = 0
        document?.destroy()
        document = null
        awaitingPassword = false
        try {
            pfd?.close()
        } catch (_: Exception) {
        }
        pfd = null
    }

    private fun pixmapToPixels(pixmap: Pixmap, w: Int, h: Int): IntArray {
        val pixels = IntArray(w * h)
        val samples = pixmap.samples
        if (samples == null || samples.isEmpty()) {
            pixels.fill(OPAQUE_WHITE)
            return pixels
        }
        val n = pixmap.numberOfComponents
        val stride = pixmap.stride
        when {
            n >= 4 && pixmap.alpha -> for (y in 0 until h) {
                var src = y * stride
                val row = y * w
                for (x in 0 until w) {
                    var r = samples[src].toInt() and 0xFF
                    var g = samples[src + 1].toInt() and 0xFF
                    var b = samples[src + 2].toInt() and 0xFF
                    val a = samples[src + 3].toInt() and 0xFF
                    src += n
                    if (a < 255) {
                        r = overWhite(r, a)
                        g = overWhite(g, a)
                        b = overWhite(b, a)
                    }
                    pixels[row + x] = OPAQUE or (r shl 16) or (g shl 8) or b
                }
            }
            n >= 3 -> for (y in 0 until h) {
                var src = y * stride
                val row = y * w
                for (x in 0 until w) {
                    val r = samples[src].toInt() and 0xFF
                    val g = samples[src + 1].toInt() and 0xFF
                    val b = samples[src + 2].toInt() and 0xFF
                    src += n
                    pixels[row + x] = OPAQUE or (r shl 16) or (g shl 8) or b
                }
            }
            n == 2 && pixmap.alpha -> for (y in 0 until h) {
                var src = y * stride
                val row = y * w
                for (x in 0 until w) {
                    var v = samples[src].toInt() and 0xFF
                    val a = samples[src + 1].toInt() and 0xFF
                    src += 2
                    if (a < 255) v = overWhite(v, a)
                    pixels[row + x] = OPAQUE or (v shl 16) or (v shl 8) or v
                }
            }
            else -> {
                val step = n.coerceAtLeast(1)
                for (y in 0 until h) {
                    var src = y * stride
                    val row = y * w
                    for (x in 0 until w) {
                        val v = samples[src].toInt() and 0xFF
                        src += step
                        pixels[row + x] = OPAQUE or (v shl 16) or (v shl 8) or v
                    }
                }
            }
        }
        return pixels
    }

    private fun overWhite(c: Int, a: Int): Int = (c * a + 255 * (255 - a)) / 255

    companion object {
        private const val REFLOW_WIDTH = 450f
        private const val REFLOW_HEIGHT = 600f
        private const val MAX_RENDER_PIXELS = 8_000_000L
        private const val OPAQUE = 0xFF shl 24
        private val OPAQUE_WHITE = 0xFFFFFFFF.toInt()
        private val PAGE_PARAM = Regex("(?:[#?&])page=(\\d+)", RegexOption.IGNORE_CASE)
        private val PAGE_FRAGMENT = Regex("^#(\\d+)$")

        fun layoutEm(fontScale: Float): Float = 12f * fontScale.coerceIn(0.7f, 1.4f)

        internal fun layoutReflowable(doc: Document, fontScale: Float) {
            if (doc.isReflowable) doc.layout(REFLOW_WIDTH, REFLOW_HEIGHT, layoutEm(fontScale))
        }
    }
}
