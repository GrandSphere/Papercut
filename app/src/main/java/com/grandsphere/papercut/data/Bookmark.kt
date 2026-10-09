package com.grandsphere.papercut.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "bookmarks",
    foreignKeys = [
        ForeignKey(
            entity = PdfDocument::class,
            parentColumns = ["uri"],
            childColumns = ["documentUri"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("documentUri")],
)
data class Bookmark(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val documentUri: String,
    val name: String,
    val pageIndex: Int,
    val scrollXPdf: Float,
    val scrollYPdf: Float,
    val sortOrder: Int,
)
