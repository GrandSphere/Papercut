package com.grandsphere.papercut.data

enum class ZoomMode {
    FIT_PAGE,
    FIT_WIDTH,
    CUSTOM;

    companion object {
        fun fromStorage(value: String?): ZoomMode =
            entries.firstOrNull { it.name == value } ?: FIT_WIDTH
    }
}
