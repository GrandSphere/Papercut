package com.grandsphere.papercut.pdf

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import com.artifex.mupdf.fitz.Document
import com.artifex.mupdf.fitz.DocumentWriter
import com.artifex.mupdf.fitz.Matrix
import com.artifex.mupdf.fitz.PDFDocument
import com.artifex.mupdf.fitz.PDFObject
import com.artifex.mupdf.fitz.SeekableInputStream
import com.artifex.mupdf.fitz.SeekableStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

object PdfComposer {
    suspend fun extractPages(context: Context, uri: Uri, pages: List<Int>, dest: File) {
        withContext(Dispatchers.IO) {
            if (pages.isEmpty()) error("No pages")
            val dst = PDFDocument()
            try {
                openPdf(context, uri).use { src ->
                    pages.forEach { index ->
                        dst.graftPage(dst.countPages(), src.doc, index)
                    }
                }
                savePdf(dst, dest)
            } finally {
                dst.destroy()
            }
        }
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
                    val box = page.bounds
                    val dev = writer.beginPage(box)
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
            deleteKey(root, "Metadata")
            deleteKey(root, "Outlines")
            deleteKey(root, "OpenAction")
            deleteKey(root, "AA")
            deleteKey(root, "PieceInfo")
            deleteKey(root, "Perms")
            deleteKey(root, "Lang")
        }
    }

    private fun deleteKey(obj: PDFObject, key: String) {
        runCatching { obj.delete(key) }
    }

    private fun openPdf(context: Context, uri: Uri): OpenedPdf {
        return openAny(context, uri, 1f).let { opened ->
            val pdf = opened.doc as? PDFDocument
            if (pdf == null) {
                opened.close()
                error("NOT_PDF")
            }
            OpenedPdf(pdf, opened)
        }
    }

    private fun openAny(context: Context, uri: Uri, fontScale: Float): OpenedDoc {
        val pfd = if (uri.scheme == "file") {
            val path = uri.path ?: error("Unable to open $uri")
            ParcelFileDescriptor.open(File(path), ParcelFileDescriptor.MODE_READ_ONLY)
        } else {
            context.contentResolver.openFileDescriptor(uri, "r") ?: error("Unable to open $uri")
        }
        return try {
            val magic = DocumentMagic.resolve(context, uri, pfd)
            val opened = Document.openDocument(PfdStream(pfd), magic)
            if (opened.needsPassword()) {
                opened.destroy()
                pfd.close()
                error("PASSWORD")
            }
            if (opened.isReflowable) {
                opened.layout(450f, 600f, 12f * fontScale.coerceIn(0.7f, 1.4f))
            }
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
        override fun close() {
            owner.close()
        }
    }

    private class PfdStream(
        private val pfd: ParcelFileDescriptor,
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

    private const val SAVE_OPTIONS = "garbage=2,pretty=no,compress=yes,sanitize=yes"
}
