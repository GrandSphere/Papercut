package com.grandsphere.papercut.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "documents")
data class PdfDocument(
    /** Working identifier (SAF/content URI). Not shown in the UI. */
    @PrimaryKey val uri: String,
    val displayName: String,
    /** Simplified user-facing path, e.g. Downloads/my.pdf */
    val displayPath: String,
    val lastPageIndex: Int = 0,
    val lastScrollXPdf: Float = 0f,
    val lastScrollYPdf: Float = 0f,
)
