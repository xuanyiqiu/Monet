package dev.monet.app.feature.lens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.monet.app.feature.lens.capture.ReadingSession
import dev.monet.app.feature.lens.capture.ReadingUnit
import dev.monet.app.ui.theme.Monet

sealed interface ChipText {
    data object Loading : ChipText
    data class Text(val value: String) : ChipText
    data class Unavailable(val note: String?) : ChipText
}

/** Colors sampled from the page behind a unit, so its chip reads as part of the page. */
data class ChipStyle(val background: Int, val foreground: Int)

data class ReadingUiState(
    val session: ReadingSession,
    val styles: Map<String, ChipStyle>,
    val translations: Map<String, ChipText>,
)

/** OCR boxes hug the glyphs; a few extra pixels let the chip fully cover ascenders, descenders and antialiasing. */
const val CHIP_INFLATE_PX = 6

/**
 * The non-touchable layer drawn over the other app: read units fade out and the active units sit
 * under a seal band with a rule on its left edge. Android 12+ caps the opacity of non-touchable
 * overlay windows at 0.8, so the opaque translation chips live in their own windows (see [ReadingChip]).
 */
@Composable
fun ReadingBand(state: ReadingUiState) {
    val c = Monet.colors
    Box(Modifier.fillMaxSize()) {
        Canvas(Modifier.fillMaxSize()) {
            val active = state.session.active
            if (active.isNotEmpty()) {
                val top = active.minOf { it.bounds.top } - 4.dp.toPx()
                val bottom = active.maxOf { it.bounds.bottom } + 4.dp.toPx()
                drawRect(c.sealSoft, Offset(0f, top), Size(size.width, bottom - top))
                drawRect(c.seal, Offset(0f, top), Size(2.dp.toPx(), bottom - top))
            }
        }
        state.session.read.forEach { unit ->
            state.styles[unit.id]?.let { ReadScrim(unit, it) }
        }
    }
}

/** Fades already-read text under its own page color over 200 ms. */
@Composable
private fun ReadScrim(unit: ReadingUnit, style: ChipStyle) {
    val alpha = remember(unit.id) { Animatable(0f) }
    LaunchedEffect(unit.id) { alpha.animateTo(0.85f, tween(200)) }
    val density = LocalDensity.current
    with(density) {
        Box(
            Modifier
                .offset { IntOffset(unit.bounds.left - 2.dp.roundToPx(), unit.bounds.top - 2.dp.roundToPx()) }
                .width((unit.bounds.width + 4.dp.toPx()).toDp())
                .height((unit.bounds.height + 4.dp.toPx()).toDp())
                .background(Color(style.background).copy(alpha = alpha.value)),
        )
    }
}

/**
 * A translation drawn over its source unit. It grows past the source box when the translation is
 * longer, up to the screen edge ([maxWidthPx] is the display width).
 */
@Composable
fun ReadingChip(unit: ReadingUnit, style: ChipStyle, text: ChipText, maxWidthPx: Int) {
    val d = LocalDensity.current
    val lineHeightPx = unit.bounds.height.toFloat() / unit.lineCount.coerceAtLeast(1)
    // Text follows the source line height, never smaller than 12sp (the design system minimum).
    val sizeSp = (lineHeightPx * 0.55f / (d.density * d.fontScale)).coerceIn(12f, 20f)
    val fg = Color(style.foreground)
    val shown = when (text) {
        ChipText.Loading -> "…"
        is ChipText.Text -> text.value
        is ChipText.Unavailable -> text.note ?: "Translation unavailable"
    }
    val minWidth = ((unit.bounds.width + 2 * CHIP_INFLATE_PX) / d.density).dp
    val maxWidth = ((maxWidthPx - unit.bounds.left + CHIP_INFLATE_PX) / d.density).dp
    Box(
        Modifier
            .widthIn(min = minWidth, max = maxOf(minWidth, maxWidth))
            .heightIn(min = ((unit.bounds.height + 2 * CHIP_INFLATE_PX) / d.density).dp)
            .background(Color(style.background), RoundedCornerShape(4.dp))
            .border(1.dp, fg.copy(alpha = 0.15f), RoundedCornerShape(4.dp))
            .padding(horizontal = 4.dp, vertical = 2.dp)
            .semantics { contentDescription = "Translation: $shown" },
    ) {
        Text(
            shown,
            style = Monet.type.reading.copy(fontSize = sizeSp.sp, lineHeight = (sizeSp * 1.3f).sp),
            color = if (text is ChipText.Text) fg else fg.copy(alpha = 0.6f),
        )
    }
}
