package dev.monet.app.feature.reader

import dev.monet.app.core.network.DocumentBlock
import dev.monet.app.core.network.DocumentLine
import dev.monet.app.core.network.DocumentPage

/** A rectangle in pixels. */
data class PxRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val area: Float get() = width * height

    fun contains(x: Float, y: Float): Boolean = x in left..right && y in top..bottom
}

/** Turns the contract's normalized `[x0, y0, x1, y1]` (origin top-left) into pixels; null for malformed boxes. */
fun normalizedToPx(bbox: List<Float>?, widthPx: Float, heightPx: Float): PxRect? {
    if (bbox == null || bbox.size != 4) return null
    val (x0, y0, x1, y1) = bbox.map { it.coerceIn(0f, 1f) }
    if (x1 <= x0 || y1 <= y0) return null
    return PxRect(x0 * widthPx, y0 * heightPx, x1 * widthPx, y1 * heightPx)
}

/** Height for a page rendered at [targetWidthPx], keeping the PDF's aspect ratio. */
fun fitHeight(pageWidth: Double?, pageHeight: Double?, targetWidthPx: Int): Int {
    if (pageWidth == null || pageHeight == null || pageWidth <= 0.0 || pageHeight <= 0.0) return (targetWidthPx * 1.414).toInt()
    return (targetWidthPx * (pageHeight / pageWidth)).toInt()
}

private fun lineBox(line: DocumentLine, block: DocumentBlock, w: Float, h: Float): PxRect? =
    normalizedToPx(line.bbox, w, h) ?: normalizedToPx(block.bbox, w, h)

/**
 * The line under a tap. Line boxes win; when none contains the point the nearest line within
 * [slopPx] is used, so a fingertip a little off the glyphs still selects something.
 */
fun hitLine(page: DocumentPage, x: Float, y: Float, widthPx: Float, heightPx: Float, slopPx: Float = 12f): DocumentLine? {
    var inside: Pair<DocumentLine, Float>? = null
    var near: Pair<DocumentLine, Float>? = null
    for (block in page.blocks) {
        for (line in block.lines) {
            val box = lineBox(line, block, widthPx, heightPx) ?: continue
            if (box.contains(x, y)) {
                if (inside == null || box.area < inside.second) inside = line to box.area
            } else {
                val dx = maxOf(box.left - x, 0f, x - box.right)
                val dy = maxOf(box.top - y, 0f, y - box.bottom)
                val dist = maxOf(dx, dy)
                if (dist <= slopPx && (near == null || dist < near.second)) near = line to dist
            }
        }
    }
    return inside?.first ?: near?.first
}

/** Page numbers to keep rendered around the visible one, so scrolling feels instant. */
fun pagesToPrefetch(current: Int, pageCount: Int, radius: Int = 1): List<Int> =
    ((current - radius)..(current + radius)).filter { it in 0 until pageCount }
