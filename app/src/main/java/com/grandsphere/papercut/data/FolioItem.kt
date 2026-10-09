package com.grandsphere.papercut.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "folio_items",
    indices = [Index(value = ["uri"], unique = true)],
)
data class FolioItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uri: String,
    val displayName: String,
    val displayPath: String,
    val sortOrder: Int,
    val hidden: Boolean = false,
    val isStartItem: Boolean = false,
    val zoomMode: String = ZoomMode.FIT_WIDTH.name,
    val customZoomPercent: Float = 100f,
    val lockZoom: Boolean = false,
    val lockSideScroll: Boolean = true,
    val originalColors: Boolean = false,
    val lastPageIndex: Int = 0,
    val lastScrollXPdf: Float = 0f,
    val lastScrollYPdf: Float = 0f,
    val localName: String = "",
)
