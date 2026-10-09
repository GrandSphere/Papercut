package com.grandsphere.papercut.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import com.artifex.mupdf.fitz.ColorSpace
import com.artifex.mupdf.fitz.Document
import com.artifex.mupdf.fitz.Font
import com.artifex.mupdf.fitz.Image
import com.artifex.mupdf.fitz.Matrix
import com.artifex.mupdf.fitz.Link
import com.artifex.mupdf.fitz.Outline
import com.artifex.mupdf.fitz.Point
import com.artifex.mupdf.fitz.Quad
import com.artifex.mupdf.fitz.Rect
import com.artifex.mupdf.fitz.SeekableInputStream
import com.artifex.mupdf.fitz.SeekableStream
import com.artifex.mupdf.fitz.StructuredTextWalker
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
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
    private val measured = ArrayList<Boolean>()
    private val contentCache = HashMap<Int, PageContent>()
    private val outlineCache = ArrayList<OutlineItem>()

    val pages: List<PageInfo>
        get() = pageInfos

    val pageCount: Int
        get() = pageInfos.size

    val mime: String
        get() = openedMime

    fun isOpened(): Boolean = document != null

    suspend fun open(context: Context, uri: Uri, fontScale: Float, startPage: Int = 0) =
        withContext(dispatcher) {
            closeLocked()
            layoutEm = 12f * fontScale.coerceIn(0.7f, 1.4f)
            openStartPage = startPage
            val app = context.applicationContext
            val opened = openDescriptor(app, uri)
            openPfdLocked(opened, DocumentMagic.resolve(app, uri, opened))
        }

    suspend fun openFile(file: File, fontScale: Float, startPage: Int = 0) =
        withContext(dispatcher) {
            closeLocked()
            layoutEm = 12f * fontScale.coerceIn(0.7f, 1.4f)
            openStartPage = startPage
            val opened = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            openPfdLocked(opened, DocumentMagic.resolve(file, opened))
        }

    private fun openDescriptor(context: Context, uri: Uri): ParcelFileDescriptor {
        if (uri.scheme == "file") {
            val path = uri.path ?: error("Unable to open $uri")
            return ParcelFileDescriptor.open(File(path), ParcelFileDescriptor.MODE_READ_ONLY)
        }
        return context.contentResolver.openFileDescriptor(uri, "r")
            ?: error("Unable to open $uri")
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
            doc.layout(450f, 600f, layoutEm)
        }
        loadStartPageLocked(doc, openStartPage)
    }

    private fun loadStartPageLocked(doc: Document, startPage: Int) {
        val count = doc.countPages().coerceAtLeast(0)
        pageInfos.clear()
        measured.clear()
        contentCache.clear()
        outlineCache.clear()
        if (count <= 0) return
        val start = startPage.coerceIn(0, count - 1)
        val seed = measurePageLocked(doc, start)
        repeat(count) {
            pageInfos += seed
            measured += false
        }
        pageInfos[start] = seed
        measured[start] = true
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
        if (!measured[index]) {
            pageInfos[index] = measurePageLocked(doc, index)
            measured[index] = true
        }
        pageInfos[index]
    }

    suspend fun measureRemainingPages(): List<PageInfo> = withContext(dispatcher) {
        val doc = document ?: return@withContext pageInfos.toList()
        for (i in pageInfos.indices) {
            if (measured[i]) continue
            pageInfos[i] = measurePageLocked(doc, i)
            measured[i] = true
        }
        pageInfos.toList()
    }

    fun allPagesMeasured(): Boolean = measured.isNotEmpty() && measured.all { it }

    suspend fun render(
        index: Int,
        targetWidth: Int,
        fg: Int,
        bg: Int,
        imageBlend: Float,
        originalColors: Boolean = false,
        imageAlpha: Float = 1f,
        urgent: Boolean = false,
    ): Bitmap = withContext(dispatcher) {
        val doc = document ?: error("No document")
        if (index in measured.indices && !measured[index]) {
            pageInfos[index] = measurePageLocked(doc, index)
            measured[index] = true
        }
        val info = pageInfos.getOrNull(index) ?: error("No document")
        var usedScale = targetWidth.coerceIn(1, 4096) / info.width
        var width = (info.width * usedScale).toInt().coerceAtLeast(1)
        var height = (info.height * usedScale).toInt().coerceAtLeast(1)
        if (width.toLong() * height > 8_000_000L) {
            usedScale *= kotlin.math.sqrt(8_000_000.0 / (width.toDouble() * height)).toFloat()
            width = (info.width * usedScale).toInt().coerceAtLeast(1)
            height = (info.height * usedScale).toInt().coerceAtLeast(1)
        }
        renderLocked(
            doc, index, info, usedScale, width, height,
            fg, bg, imageBlend, originalColors, imageAlpha,
        )
    }

    private fun renderLocked(
        doc: Document,
        index: Int,
        info: PageInfo,
        usedScale: Float,
        width: Int,
        height: Int,
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
            val bitmap = pixmapToBitmap(pixmap, width, height)
            if (!originalColors) {
                val needImageRects = imageBlend > 0.001f || imageAlpha < 0.999f
                val imageRects = if (!needImageRects) {
                    emptyList()
                } else {
                    contentLocked(index).imageRects.map { src ->
                        RectF(
                            (src.left - info.x0) * usedScale,
                            (src.top - info.y0) * usedScale,
                            (src.right - info.x0) * usedScale,
                            (src.bottom - info.y0) * usedScale
                        )
                    }
                }
                ColorRemapper.remapInPlace(
                    bitmap,
                    fg,
                    bg,
                    imageBlend,
                    imageRects,
                    imageAlpha,
                )
            }
            return bitmap
        } finally {
            pixmap.destroy()
        }
    }

    suspend fun outline(): List<OutlineItem> = withContext(dispatcher) {
        val doc = document ?: return@withContext emptyList()
        if (outlineCache.isEmpty()) {
            outlineCache += flattenOutlineLocked(doc)
        }
        outlineCache.toList()
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
        val match = Regex("(?:[#?&])page=(\\d+)", RegexOption.IGNORE_CASE).find(uri)
            ?: Regex("^#(\\d+)$").find(uri)
        val n = match?.groupValues?.get(1)?.toIntOrNull() ?: return null
        return (n - 1).takeIf { it >= 0 }
    }

    private fun contentLocked(index: Int): PageContent {
        contentCache[index]?.let { return it }
        val doc = document ?: return PageContent(emptyList(), emptyList())
        val page = doc.loadPage(index)
        val chars = ArrayList<CharBox>()
        val images = ArrayList<RectF>()
        val links = ArrayList<PageLink>()
        try {
            page.getLinks()?.forEach { link ->
                val b = link.bounds ?: return@forEach
                val uri = link.uri ?: return@forEach
                links += PageLink(
                    bounds = RectF(
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
                        images += RectF(bbox.x0, bbox.y0, bbox.x1, bbox.y1)
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
        outlineCache.clear()
        pageInfos.clear()
        measured.clear()
        document?.destroy()
        document = null
        awaitingPassword = false
        try {
            pfd?.close()
        } catch (_: Exception) {
        }
        pfd = null
    }

    private fun pixmapToBitmap(
        pixmap: com.artifex.mupdf.fitz.Pixmap,
        fallbackW: Int,
        fallbackH: Int
    ): Bitmap {
        val w = pixmap.width.coerceAtLeast(1)
        val h = pixmap.height.coerceAtLeast(1)
        val n = pixmap.numberOfComponents
        val alpha = pixmap.alpha
        val stride = pixmap.stride
        val samples = pixmap.samples
        val pixels = IntArray(w * h)
        if (samples != null && samples.isNotEmpty()) {
            for (y in 0 until h) {
                var src = y * stride
                val row = y * w
                for (x in 0 until w) {
                    var r: Int
                    var g: Int
                    var b: Int
                    var a = 255
                    when {
                        n >= 4 && alpha -> {
                            r = samples[src].toInt() and 0xFF
                            g = samples[src + 1].toInt() and 0xFF
                            b = samples[src + 2].toInt() and 0xFF
                            a = samples[src + 3].toInt() and 0xFF
                            src += n
                        }
                        n >= 3 -> {
                            r = samples[src].toInt() and 0xFF
                            g = samples[src + 1].toInt() and 0xFF
                            b = samples[src + 2].toInt() and 0xFF
                            src += n
                        }
                        n == 2 && alpha -> {
                            r = samples[src].toInt() and 0xFF
                            g = r
                            b = r
                            a = samples[src + 1].toInt() and 0xFF
                            src += 2
                        }
                        else -> {
                            r = samples[src].toInt() and 0xFF
                            g = r
                            b = r
                            src += n.coerceAtLeast(1)
                        }
                    }
                    if (a < 255) {
                        val ia = 255 - a
                        r = (r * a + 255 * ia) / 255
                        g = (g * a + 255 * ia) / 255
                        b = (b * a + 255 * ia) / 255
                    }
                    pixels[row + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                }
            }
        }
        val bitmap = Bitmap.createBitmap(
            if (w > 0) w else fallbackW,
            if (h > 0) h else fallbackH,
            Bitmap.Config.ARGB_8888
        )
        bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
        return bitmap
    }

    private class PfdSeekableStream(
        private val pfd: ParcelFileDescriptor
    ) : SeekableInputStream {
        override fun read(b: ByteArray): Int {
            val n = Os.read(pfd.fileDescriptor, b, 0, b.size)
            return if (n == 0) -1 else n
        }

        override fun position(): Long =
            Os.lseek(pfd.fileDescriptor, 0, OsConstants.SEEK_CUR)

        override fun seek(offset: Long, whence: Int): Long {
            val osWhence = when (whence) {
                SeekableStream.SEEK_SET -> OsConstants.SEEK_SET
                SeekableStream.SEEK_CUR -> OsConstants.SEEK_CUR
                SeekableStream.SEEK_END -> OsConstants.SEEK_END
                else -> OsConstants.SEEK_SET
            }
            return Os.lseek(pfd.fileDescriptor, offset, osWhence)
        }
    }
}
