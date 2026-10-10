package dev.monet.app.feature.lens.capture

import kotlin.math.max
import kotlin.math.min

/** A pixel rectangle on the captured frame. */
data class SelRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val centerX: Int get() = (left + right) / 2
    val centerY: Int get() = (top + bottom) / 2
}

/** Builds a normalized rectangle from two drag corners, whatever direction the finger moved. */
fun selRect(x1: Float, y1: Float, x2: Float, y2: Float) = SelRect(
    left = min(x1, x2).toInt(),
    top = min(y1, y2).toInt(),
    right = max(x1, x2).toInt(),
    bottom = max(y1, y2).toInt(),
)

/** What the user picked: the text to translate and the area to highlight. */
data class SelectionResult(val text: String, val bounds: SelRect)

private fun coverage(line: ScreenLine, rect: SelRect): Float {
    val w = min(line.right, rect.right) - max(line.left, rect.left)
    val h = min(line.bottom, rect.bottom) - max(line.top, rect.top)
    if (w <= 0 || h <= 0) return 0f
    return (w.toLong() * h).toFloat() / (line.width.toLong() * line.height).coerceAtLeast(1)
}

/** Lines at least [minCoverage] inside [rect]. Line-level boxes are the finest grain OCR gives us. */
fun linesInRect(lines: List<ScreenLine>, rect: SelRect, minCoverage: Float = 0.4f): List<ScreenLine> =
    lines.filter { coverage(it, rect) >= minCoverage }

/** The block that contains the point, allowing [slop] pixels of imprecision. */
fun blockAt(blocks: List<TextBlock>, x: Int, y: Int, slop: Int): TextBlock? =
    blocks.filter { x in (it.left - slop)..(it.right + slop) && y in (it.top - slop)..(it.bottom + slop) }
        .minByOrNull { (it.right - it.left).toLong() * (it.bottom - it.top) }

/**
 * Resolves a user gesture to text. A drag smaller than [tapSizePx] in both directions counts as a
 * tap and selects the block under the finger; a bigger drag selects every line it mostly covers.
 * Returns null when nothing readable is under the gesture.
 */
fun resolveSelection(lines: List<ScreenLine>, rect: SelRect, tapSizePx: Int): SelectionResult? {
    if (rect.width < tapSizePx && rect.height < tapSizePx) {
        val block = blockAt(groupIntoBlocks(lines), rect.centerX, rect.centerY, slop = tapSizePx / 2) ?: return null
        return SelectionResult(block.text, SelRect(block.left, block.top, block.right, block.bottom))
    }
    val picked = linesInRect(lines, rect)
    if (picked.isEmpty()) return null
    val blocks = groupIntoBlocks(picked)
    if (blocks.isEmpty()) return null
    return SelectionResult(
        text = blocks.joinToString("\n") { it.text },
        bounds = SelRect(blocks.minOf { it.left }, blocks.minOf { it.top }, blocks.maxOf { it.right }, blocks.maxOf { it.bottom }),
    )
}
