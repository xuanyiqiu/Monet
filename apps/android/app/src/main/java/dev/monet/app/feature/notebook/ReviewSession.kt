package dev.monet.app.feature.notebook

import dev.monet.app.core.data.Grade
import dev.monet.app.core.data.NotebookEntry

/**
 * One review run over a fixed queue: show a card, reveal its meaning, grade it, move on.
 * Immutable, so the screen state is just the latest value.
 */
data class ReviewSession(
    val queue: List<NotebookEntry>,
    val index: Int = 0,
    val revealed: Boolean = false,
    val grades: List<Grade> = emptyList(),
) {
    val total: Int get() = queue.size
    val done: Boolean get() = index >= queue.size
    val current: NotebookEntry? get() = queue.getOrNull(index)

    /** 1-based number of the card on screen, capped at [total] once finished. */
    val position: Int get() = minOf(index + 1, total)
    val remaining: Int get() = total - index.coerceAtMost(total)
    val againCount: Int get() = grades.count { it == Grade.AGAIN }
    val knownCount: Int get() = grades.size - againCount

    fun reveal(): ReviewSession = if (done) this else copy(revealed = true)

    /** Grading needs the answer to be visible first, so a stray tap cannot skip a card unseen. */
    fun grade(grade: Grade): ReviewSession =
        if (done || !revealed) this else copy(index = index + 1, revealed = false, grades = grades + grade)
}
