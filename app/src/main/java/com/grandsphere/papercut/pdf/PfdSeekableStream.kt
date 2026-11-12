package com.grandsphere.papercut.pdf

import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import com.artifex.mupdf.fitz.SeekableInputStream
import com.artifex.mupdf.fitz.SeekableStream

internal class PfdSeekableStream(
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
            SeekableStream.SEEK_CUR -> OsConstants.SEEK_CUR
            SeekableStream.SEEK_END -> OsConstants.SEEK_END
            else -> OsConstants.SEEK_SET
        }
        return Os.lseek(pfd.fileDescriptor, offset, osWhence)
    }
}
