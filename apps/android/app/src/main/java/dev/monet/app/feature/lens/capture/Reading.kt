package dev.monet.app.feature.lens.capture

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** What the reading band steps over: one text block (a paragraph, a chat message, a caption). */
data class ReadingUnit(
    val id: String,
    val text: String,
    val bounds: SelRect,
    /** Number of OCR lines in the block; used to size the translation chip text. */
    val lineCount: Int,
)

fun buildReadingUnits(blocks: List<TextBlock>): List<ReadingUnit> =
    blocks.mapIndexed { i, b ->
        ReadingUnit("u$i", b.text, SelRect(b.left, b.top, b.right, b.bottom), b.lines.size)
    }

/** A pass over the screen: [linesPerStep] units are active at a time, earlier ones count as read. */
data class ReadingSession(
    val units: List<ReadingUnit>,
    val linesPerStep: Int,
    val index: Int = 0,
) {
    init {
        require(linesPerStep >= 1) { "linesPerStep must be at least 1" }
    }

    val end: Int get() = min(index + linesPerStep, units.size)
    val active: List<ReadingUnit> get() = units.subList(min(index, units.size), end)
    val read: List<ReadingUnit> get() = units.subList(0, min(index, units.size))
    val hasPrev: Boolean get() = index > 0
    val hasNext: Boolean get() = end < units.size

    /** The units after the active ones, for prefetching the next translation. */
    fun upcoming(count: Int = linesPerStep): List<ReadingUnit> = units.subList(end, min(end + count, units.size))

    fun next(): ReadingSession = if (hasNext) copy(index = end) else this
    fun prev(): ReadingSession = if (hasPrev) copy(index = max(0, index - linesPerStep)) else this

    /** Changing the step size keeps the band where it is. */
    fun withLinesPerStep(n: Int): ReadingSession = copy(linesPerStep = n.coerceAtLeast(1))

    val label: String
        get() = if (units.isEmpty()) "Nothing to read" else if (end - index <= 1) "${index + 1} of ${units.size}" else "${index + 1}–$end of ${units.size}"
}

// ---- Color helpers for chips that blend into the page ---------------------------------------

private fun channel(c: Int, shift: Int): Double {
    val v = ((c shr shift) and 0xFF) / 255.0
    return if (v <= 0.03928) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
}

/** WCAG relative luminance of an ARGB color (alpha ignored). */
fun relativeLuminance(argb: Int): Double =
    0.2126 * channel(argb, 16) + 0.7152 * channel(argb, 8) + 0.0722 * channel(argb, 0)

fun contrastRatio(a: Int, b: Int): Double {
    val la = relativeLuminance(a)
    val lb = relativeLuminance(b)
    return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
}

const val INK_LIGHT_THEME = 0xFF1B1A17.toInt()
const val PAPER_LIGHT_THEME = 0xFFF7F4EE.toInt()

/** Chip text color: ink or paper, whichever contrasts more with the sampled background. */
fun pickTextColor(background: Int, ink: Int = INK_LIGHT_THEME, paper: Int = PAPER_LIGHT_THEME): Int =
    if (contrastRatio(background, ink) >= contrastRatio(background, paper)) ink else paper

/** Per-channel median, so a stray anti-aliased pixel or an icon does not tint the whole chip. */
fun medianArgb(pixels: IntArray): Int {
    if (pixels.isEmpty()) return PAPER_LIGHT_THEME
    fun med(shift: Int): Int = pixels.map { (it shr shift) and 0xFF }.sorted()[pixels.size / 2]
    return (0xFF shl 24) or (med(16) shl 16) or (med(8) shl 8) or med(0)
}

/**
 * Samples the pixels on the border of [rect] (just outside the text, where the page background is)
 * through [pixelAt], clamped to the frame. Step keeps the sample count small on big blocks.
 */
fun borderPixels(width: Int, height: Int, rect: SelRect, pad: Int = 2, step: Int = 4, pixelAt: (Int, Int) -> Int): IntArray {
    val l = (rect.left - pad).coerceIn(0, width - 1)
    val r = (rect.right + pad).coerceIn(0, width - 1)
    val t = (rect.top - pad).coerceIn(0, height - 1)
    val b = (rect.bottom + pad).coerceIn(0, height - 1)
    val out = ArrayList<Int>()
    var x = l
    while (x <= r) {
        out += pixelAt(x, t); out += pixelAt(x, b); x += step
    }
    var y = t
    while (y <= b) {
        out += pixelAt(l, y); out += pixelAt(r, y); y += step
    }
    return out.toIntArray()
}
