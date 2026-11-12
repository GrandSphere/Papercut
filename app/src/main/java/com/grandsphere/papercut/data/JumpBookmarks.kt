package com.grandsphere.papercut.data

import kotlin.math.abs

object JumpBookmarks {
    const val Jump1 = "Quick jump 1"
    const val Jump2 = "Quick jump 2"

    fun isJumpName(name: String): Boolean = name == Jump1 || name == Jump2

    fun at(mark: Bookmark, page: Int, scrollX: Float, scrollY: Float): Boolean {
        return mark.pageIndex == page &&
            abs(mark.scrollXPdf - scrollX) < 2f &&
            abs(mark.scrollYPdf - scrollY) < 8f
    }

    data class Action(
        val createName: String? = null,
        val toast: String? = null,
        val goToName: String? = null,
    )

    fun plan(jump1: Bookmark?, jump2: Bookmark?, atJump1: Boolean): Action {
        return if (jump1 != null) {
            if (jump2 != null) {
                Action(goToName = if (atJump1) Jump2 else Jump1)
            } else {
                Action(createName = Jump2, toast = "created Quick jump 2", goToName = Jump1)
            }
        } else {
            Action(
                createName = Jump1,
                toast = "created Quick jump 1",
                goToName = if (jump2 != null) Jump2 else null,
            )
        }
    }
}
