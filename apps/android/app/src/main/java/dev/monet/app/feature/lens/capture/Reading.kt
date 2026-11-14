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
        get() = when {
            units.isEmpty() -> "Nothing to read"
            // After a scroll that showed nothing new, the band waits past the last unit.
            active.isEmpty() -> "All read"
            end - index <= 1 -> "${index + 1} of ${units.size}"
            else -> "${index + 1}–$end of ${units.size}"
        }
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

// ---- Following a page that scrolls ----------------------------------------------------------

/** "All" in the lines-per-step setting: one step covers everything on screen. */
const val LINES_ALL = 1_000

/** Text reduced to what survives OCR jitter between two captures of the same line. */
fun matchKey(text: String): String = text.filter { it.isLetterOrDigit() }.lowercase()

/** Remembers what the user has already read, so a re-read after a scroll can skip it. */
class ReadHistory(private val capacity: Int = 400) {
    private val seen = LinkedHashSet<String>()

    fun mark(units: List<ReadingUnit>) {
        units.forEach { unit ->
            val key = matchKey(unit.text)
            if (key.isNotEmpty()) {
                seen.remove(key)
                seen.add(key)
            }
        }
        while (seen.size > capacity) seen.remove(seen.first())
    }

    operator fun contains(unit: ReadingUnit): Boolean = matchKey(unit.text) in seen

    fun clear() = seen.clear()

    /**
     * Records what [session] has shown before the page is read again: every unit the band has
     * passed, plus the active ones when the band had reached the bottom (the user scrolled on).
     */
    fun markFrom(session: ReadingSession) {
        mark(session.read)
        if (!session.hasNext) mark(session.active)
    }
}

/** Where the band starts after a re-read: the first unit not read yet (the end when all were read). */
fun reanchorIndex(units: List<ReadingUnit>, history: ReadHistory): Int {
    val index = units.indexOfFirst { it !in history }
    return if (index < 0) units.size else index
}

/** Drops text lines that sit under one of the Lens's own windows (the bubble, an open pill). */
fun excludeRegions(lines: List<ScreenLine>, regions: List<SelRect>): List<ScreenLine> =
    if (regions.isEmpty()) lines else lines.filterNot { line ->
        regions.any { r -> line.left < r.right && line.right > r.left && line.top < r.bottom && line.bottom > r.top }
    }

// ---- What is worth reading, and how big its chip is -----------------------------------------

private val URL_LIKE = Regex("""^(https?://)?[\w.-]+(:\d+)?(/\S*)?$""")

/**
 * False for screen furniture that OCR finds but nobody wants translated: address bars, bare
 * numbers, counters, clock times. A unit needs at least two letters and must not be a lone URL.
 */
fun isReadable(text: String): Boolean {
    val trimmed = text.trim()
    if (trimmed.count { it.isLetter() } < 2) return false
    if (!trimmed.contains(' ') && trimmed.contains('.') && URL_LIKE.matches(trimmed) && trimmed.all { it.code < 0x2E80 }) return false
    return true
}

/** Chip text size in pixels: it follows the source line height, bounded so it stays readable (12-28sp). */
fun chipTextSizePx(lineHeightPx: Float, density: Float, fontScale: Float, textScale: Float = 1f): Float {
    val scaled = density * fontScale
    val sp = (lineHeightPx * 0.55f * textScale / scaled).coerceIn(12f, 28f)
    return sp * scaled
}

/**
 * Width of a chip window: at least the source box, widened toward the screen edge when the
 * translation is longer. [textWidthPx] is the translation measured on one line; a block of
 * [lineCount] source lines has that many lines to spread it over.
 */
fun chipWidthPx(sourceWidthPx: Int, textWidthPx: Float, lineCount: Int, paddingPx: Int, maxWidthPx: Int): Int {
    val needed = (textWidthPx * 1.08f / lineCount.coerceAtLeast(1)).toInt() + paddingPx
    return max(sourceWidthPx, needed).coerceAtMost(max(sourceWidthPx, maxWidthPx))
}
