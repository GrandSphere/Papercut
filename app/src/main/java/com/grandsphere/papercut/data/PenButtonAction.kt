package com.grandsphere.papercut.data

enum class PenButtonAction {
    DEFAULT_INTERACTION,
    DO_NOTHING,
    OPEN_BOOKMARKS,
    ADD_QUICK_BOOKMARK,
    QUICK_JUMP,
    SCROLL_NEXT_PAGE,
    SCROLL_PREV_PAGE,
    FOLLOW_LINK,
    ;

    fun label(): String = when (this) {
        DEFAULT_INTERACTION -> "Default interaction"
        DO_NOTHING -> "Do nothing"
        OPEN_BOOKMARKS -> "Open bookmarks"
        ADD_QUICK_BOOKMARK -> "Add quick bookmark"
        QUICK_JUMP -> "Quick jump"
        SCROLL_NEXT_PAGE -> "Scroll to next page"
        SCROLL_PREV_PAGE -> "Scroll to previous page"
        FOLLOW_LINK -> "Follow link"
    }

    companion object {
        fun fromStorage(value: String?, fallback: PenButtonAction): PenButtonAction =
            entries.firstOrNull { it.name == value } ?: fallback

        fun picker(includeFollowLink: Boolean): List<PenButtonAction> =
            entries.filter { includeFollowLink || it != FOLLOW_LINK }
    }
}
