package dev.monet.app.core.settings

import dev.monet.app.feature.lens.capture.LINES_ALL
import kotlin.math.abs

/** Lines per step choices; [LINES_ALL] means "the whole screen at once". */
val LINES_OPTIONS: List<Int> = listOf(1, 2, 3, 5, 10, LINES_ALL)

/** Snaps any stored value (including the old 4, or garbage) to the nearest allowed option. */
fun snapLines(value: Int): Int {
    if (value >= LINES_ALL) return LINES_ALL
    return LINES_OPTIONS.dropLast(1).minByOrNull { abs(it - value) } ?: LINES_OPTIONS.first()
}

fun linesLabel(lines: Int): String = if (lines >= LINES_ALL) "All" else lines.toString()

/** Next/previous option from [current]; stays put at either end. */
fun stepLinesOption(current: Int, delta: Int): Int {
    val i = LINES_OPTIONS.indexOf(snapLines(current))
    return LINES_OPTIONS[(i + delta).coerceIn(0, LINES_OPTIONS.lastIndex)]
}

/** Overlay opacity as a percentage of the idle (resting) state. */
val OPACITY_OPTIONS: List<Int> = listOf(40, 60, 80, 100)
const val DEFAULT_OPACITY_PERCENT = 60

fun snapOpacityPercent(value: Int): Int = OPACITY_OPTIONS.minByOrNull { abs(it - value) } ?: DEFAULT_OPACITY_PERCENT

/** Resting alpha of the lens bubble. */
fun bubbleIdleAlpha(percent: Int): Float = snapOpacityPercent(percent) / 100f

/** Alpha of the scrim that fades already-read text; 60% (default) keeps the original 0.85. */
fun readScrimAlpha(percent: Int): Float = (snapOpacityPercent(percent) / 100f + 0.25f).coerceAtMost(0.95f)

enum class ChipSize(val scale: Float) { SMALL(0.85f), NORMAL(1.0f), LARGE(1.2f) }

enum class TtsSpeed(val rate: Float) { SLOW(0.75f), NORMAL(1.0f), FAST(1.3f) }

/** "Slow" playback is always clearly slower than the user's normal rate. */
fun speechRate(speed: TtsSpeed, slow: Boolean): Float = if (slow) speed.rate * 0.55f else speed.rate
