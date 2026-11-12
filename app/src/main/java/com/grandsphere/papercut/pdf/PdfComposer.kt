package com.grandsphere.papercut.pdf

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.artifex.mupdf.fitz.Document
import com.artifex.mupdf.fitz.DocumentWriter
import com.artifex.mupdf.fitz.Matrix
import com.artifex.mupdf.fitz.PDFDocument
import com.artifex.mupdf.fitz.PDFObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

object PdfComposer {
    suspend fun extractPages(
        context: Context,
        uri: Uri,
        pages: List<Int>,
        dest: File,
        rotates: List<Int> = emptyList(),
    ) {
        withContext(Dispatchers.IO) {
            if (pages.isEmpty()) error("No pages")
            val dst = PDFDocument()
            try {
                openPdf(context, uri).use { src ->
                    pages.forEachIndexed { i, index ->
                        dst.graftPage(dst.countPages(), src.doc, index)
                        applyRotate(dst, dst.countPages() - 1, rotates.getOrElse(i) { 0 })
                    }
                }
                savePdf(dst, dest)
            } finally {
                dst.destroy()
            }
        }
    }

    private fun applyRotate(doc: PDFDocument, pageIndex: Int, extraCw: Int) {
        val extra = PageRotation.norm(extraCw)
        if (extra == 0) return
        val page = runCatching { doc.findPage(pageIndex) }.getOrNull() ?: return
        var current = 0
        val existing = runCatching { page.getInheritable("Rotate") }.getOrNull()
        if (existing != null && !existing.isNull) {
            current = runCatching { existing.asInteger() }.getOrDefault(0)
        }
        runCatching { page.put("Rotate", PageRotation.norm(current + extra)) }
    }

    suspend fun combine(context: Context, uris: List<Uri>, dest: File, fontScale: Float = 1f) {
        withContext(Dispatchers.IO) {
            if (uris.isEmpty()) error("No documents")
            val dst = PDFDocument()
            try {
                uris.forEach { uri ->
                    appendUri(dst, context, uri, dest.parentFile, fontScale)
                }
                savePdf(dst, dest)
            } finally {
                dst.destroy()
            }
        }
    }

    private fun appendUri(dst: PDFDocument, context: Context, uri: Uri, tmpDir: File?, fontScale: Float) {
        openAny(context, uri, fontScale).use { opened ->
            val src = opened.doc
            if (src is PDFDocument) {
                graftAll(dst, src)
                return
            }
            val tmp = File.createTempFile("papercut-conv", ".pdf", tmpDir)
            try {
                writeAsPdf(src, tmp)
                openPdf(context, Uri.fromFile(tmp)).use { converted ->
                    graftAll(dst, converted.doc)
                }
            } finally {
                tmp.delete()
            }
        }
    }

    private fun graftAll(dst: PDFDocument, src: PDFDocument) {
        val count = src.countPages()
        if (count <= 0) error("EXPORT")
        for (i in 0 until count) {
            dst.graftPage(dst.countPages(), src, i)
        }
    }

    private fun writeAsPdf(src: Document, dest: File) {
        if (dest.exists()) dest.delete()
        val writer = DocumentWriter(dest.absolutePath, "pdf", "compress")
        try {
            val count = src.countPages()
            if (count <= 0) error("EXPORT")
            for (i in 0 until count) {
                val page = src.loadPage(i)
                try {
                    val dev = writer.beginPage(page.bounds)
                    page.run(dev, Matrix.Identity(), null)
                    writer.endPage()
                } finally {
                    page.destroy()
                }
            }
        } finally {
            writer.close()
        }
    }

    private fun savePdf(dst: PDFDocument, dest: File) {
        stripTracing(dst)
        if (dest.exists()) dest.delete()
        dest.parentFile?.mkdirs()
        dst.save(dest.absolutePath, SAVE_OPTIONS)
    }

    private fun stripTracing(doc: PDFDocument) {
        val trailer = doc.trailer
        deleteKey(trailer, "Info")
        deleteKey(trailer, "ID")
        val root = trailer.get("Root")
        if (root != null && !root.isNull) {
            ROOT_TRACING_KEYS.forEach { deleteKey(root, it) }
        }
    }

    private fun deleteKey(obj: PDFObject, key: String) {
        runCatching { obj.delete(key) }
    }

    private fun openPdf(context: Context, uri: Uri): OpenedPdf {
        val opened = openAny(context, uri, 1f)
        val pdf = opened.doc as? PDFDocument
        if (pdf == null) {
            opened.close()
            error("NOT_PDF")
        }
        return OpenedPdf(pdf, opened)
    }

    private fun openAny(context: Context, uri: Uri, fontScale: Float): OpenedDoc {
        val pfd = DocumentMagic.openDescriptor(context, uri)
        return try {
            val magic = DocumentMagic.resolve(context, uri, pfd)
            val opened = Document.openDocument(PfdSeekableStream(pfd), magic)
            if (opened.needsPassword()) {
                opened.destroy()
                pfd.close()
                error("PASSWORD")
            }
            MuPdfEngine.layoutReflowable(opened, fontScale)
            OpenedDoc(opened, pfd)
        } catch (t: Throwable) {
            runCatching { pfd.close() }
            throw t
        }
    }

    private class OpenedDoc(
        val doc: Document,
        private val pfd: ParcelFileDescriptor,
    ) : AutoCloseable {
        override fun close() {
            runCatching { doc.destroy() }
            runCatching { pfd.close() }
        }
    }

    private class OpenedPdf(
        val doc: PDFDocument,
        private val owner: OpenedDoc,
    ) : AutoCloseable {
        override fun close() = owner.close()
    }

    private val ROOT_TRACING_KEYS =
        listOf("Metadata", "Outlines", "OpenAction", "AA", "PieceInfo", "Perms", "Lang")

    private const val SAVE_OPTIONS = "garbage=2,pretty=no,compress=yes,sanitize=yes"
}
