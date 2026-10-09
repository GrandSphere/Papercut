package com.grandsphere.papercut.data

import android.content.Context
import java.io.File

object AppFiles {
    const val LAST_PDF = "last.pdf"
    const val EXPORT_PDF = "papercut-export.pdf"

    fun stored(context: Context): File = File(context.filesDir, "Files").also { it.mkdirs() }

    fun temp(context: Context): File = File(context.cacheDir, "tempFiles").also { it.mkdirs() }

    fun lastPdf(context: Context): File = File(stored(context), LAST_PDF)

    fun folioDir(context: Context): File = File(stored(context), "folio").also { it.mkdirs() }

    fun exportPdf(context: Context): File = File(temp(context), EXPORT_PDF)

    fun jsonShare(context: Context, name: String): File = File(temp(context), name)

    fun migrate(context: Context) {
        val stored = stored(context)
        val temp = temp(context)
        moveFile(File(context.filesDir, LAST_PDF), File(stored, LAST_PDF))
        moveDirContents(File(context.filesDir, "folio"), File(stored, "folio"))
        moveFile(File(context.cacheDir, EXPORT_PDF), File(temp, EXPORT_PDF))
        context.cacheDir.listFiles()?.forEach { file ->
            if (file.isFile && file.name.startsWith("Papercut-") && file.name.endsWith(".json")) {
                moveFile(file, File(temp, file.name))
            }
        }
    }

    private fun moveFile(from: File, to: File) {
        if (!from.exists() || from.absoluteFile == to.absoluteFile) return
        if (to.exists()) {
            from.delete()
            return
        }
        to.parentFile?.mkdirs()
        if (!from.renameTo(to)) {
            from.copyTo(to, overwrite = false)
            from.delete()
        }
    }

    private fun moveDirContents(from: File, to: File) {
        if (!from.isDirectory) return
        to.mkdirs()
        from.listFiles()?.forEach { child ->
            if (child.isFile) moveFile(child, File(to, child.name))
        }
        from.delete()
    }
}
