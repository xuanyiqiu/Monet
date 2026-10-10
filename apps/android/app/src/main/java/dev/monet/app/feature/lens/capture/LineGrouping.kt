package dev.monet.app.feature.lens.capture

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** One recognized line of text with its pixel bounds on the screen (no Android types, so it is unit-testable). */
data class ScreenLine(
    val text: String,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val centerX: Int get() = (left + right) / 2
    val centerY: Int get() = (top + bottom) / 2
}

/** Lines that read as one paragraph or one UI element (a chat bubble, a caption, a post). */
data class TextBlock(val lines: List<ScreenLine>) {
    val text: String get() = joinLines(lines.map { it.text })
    val left: Int get() = lines.minOf { it.left }
    val top: Int get() = lines.minOf { it.top }
    val right: Int get() = lines.maxOf { it.right }
    val bottom: Int get() = lines.maxOf { it.bottom }
}

private fun isCjk(c: Char): Boolean =
    c.code in 0x2E80..0x9FFF || c.code in 0xF900..0xFAFF || c.code in 0xFF00..0xFFEF || c.code in 0x3000..0x303F

/** CJK text flows without spaces; Latin lines need one between them. */
fun joinLines(lines: List<String>): String {
    val sb = StringBuilder()
    for (line in lines.map { it.trim() }.filter { it.isNotEmpty() }) {
        if (sb.isNotEmpty() && !isCjk(sb.last()) && !isCjk(line.first())) sb.append(' ')
        sb.append(line)
    }
    return sb.toString()
}

private const val MAX_HEIGHT_RATIO = 1.6f
private const val MAX_GAP_FACTOR = 0.8f
private const val MIN_OVERLAP = 0.3f

/** Top-to-bottom, then left-to-right; lines whose vertical centers are close count as one row. */
fun readingOrder(lines: List<ScreenLine>): List<ScreenLine> {
    val sorted = lines.sortedBy { it.centerY }
    val rows = mutableListOf<MutableList<ScreenLine>>()
    for (line in sorted) {
        val row = rows.lastOrNull()
        if (row != null && abs(line.centerY - row.map { it.centerY }.average()) < 0.5f * row.minOf { it.height }) {
            row += line
        } else {
            rows += mutableListOf(line)
        }
    }
    return rows.flatMap { row -> row.sortedBy { it.left } }
}

/**
 * Groups recognized lines into blocks. A line joins an existing block when it sits right below the
 * block's last line, is aligned with it (left edge or center), has a similar font size and overlaps
 * horizontally. Side-by-side columns therefore stay separate blocks.
 */
fun groupIntoBlocks(lines: List<ScreenLine>): List<TextBlock> {
    val usable = lines.filter { l -> l.text.any { it.isLetterOrDigit() || isCjk(it) } && l.width > 0 && l.height > 0 }
    val blocks = mutableListOf<MutableList<ScreenLine>>()
    for (line in readingOrder(usable)) {
        val target = blocks
            .map { it to gapBelow(it.last(), line) }
            .filter { it.second != null }
            .minByOrNull { it.second!! }
            ?.first
        if (target != null) target += line else blocks += mutableListOf(line)
    }
    return blocks.map { TextBlock(it) }.sortedWith(compareBy({ it.top }, { it.left }))
}

/** Vertical gap when [next] continues the block ending at [last]; null when it does not. */
private fun gapBelow(last: ScreenLine, next: ScreenLine): Int? {
    val h = min(last.height, next.height)
    val ratio = max(last.height, next.height).toFloat() / h
    if (ratio > MAX_HEIGHT_RATIO) return null
    val gap = next.top - last.bottom
    if (gap < -0.3f * h || gap > MAX_GAP_FACTOR * h) return null
    val overlap = min(last.right, next.right) - max(last.left, next.left)
    if (overlap < MIN_OVERLAP * min(last.width, next.width)) return null
    val aligned = abs(last.left - next.left) <= 2 * h || abs(last.centerX - next.centerX) <= h
    return if (aligned) gap else null
}
