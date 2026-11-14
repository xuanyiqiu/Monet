package dev.monet.app.feature.lens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.monet.app.feature.detail.UnderlineStyle
import dev.monet.app.feature.lens.capture.ReadingSession
import dev.monet.app.feature.lens.capture.ReadingUnit
import dev.monet.app.ui.theme.Monet

sealed interface ChipText {
    data object Loading : ChipText

    /** @param hint set when the line holds an idiom, meme or wordplay worth opening the details for. */
    data class Text(val value: String, val hint: UnderlineStyle? = null) : ChipText

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

val ReadingHandleSize = 40.dp

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
 * A translation drawn over its source unit, filling its window: the Lens sizes that window from
 * the source box and the length of the translation. Tapping the chip opens the details; a colored
 * rule under the text says there is an expression worth opening them for.
 */
@Composable
fun ReadingChip(
    unit: ReadingUnit,
    style: ChipStyle,
    text: ChipText,
    textSizeSp: Float,
    onClick: () -> Unit = {},
) {
    val c = Monet.colors
    val d = LocalDensity.current
    val fg = Color(style.foreground)
    val shown = when (text) {
        ChipText.Loading -> "\u2026"
        is ChipText.Text -> text.value
        is ChipText.Unavailable -> text.note ?: "Translation unavailable"
    }
    val hint = (text as? ChipText.Text)?.hint
    val hintColor = when (hint) {
        UnderlineStyle.INDIGO_DOTTED -> c.indigo
        UnderlineStyle.SEAL_WAVY -> c.seal
        null -> Color.Transparent
    }
    val shape = RoundedCornerShape(4.dp)
    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(min = ((unit.bounds.height + 2 * CHIP_INFLATE_PX) / d.density).dp)
            .clip(shape)
            .background(Color(style.background))
            .border(1.dp, fg.copy(alpha = 0.15f), shape)
            .drawBehind {
                if (hint != null) {
                    val stroke = 2.dp.toPx()
                    drawRect(hintColor, Offset(0f, size.height - stroke), Size(size.width, stroke))
                }
            }
            .clickable(onClickLabel = "Open details", onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 2.dp)
            .semantics {
                contentDescription = if (hint != null) "Translation, with an expression explained: $shown" else "Translation: $shown"
            },
    ) {
        Text(
            shown,
            style = Monet.type.reading.copy(fontSize = textSizeSp.sp, lineHeight = (textSizeSp * 1.3f).sp),
            color = if (text is ChipText.Text) fg else fg.copy(alpha = 0.6f),
        )
    }
}

/** The band's handle: tap for the next step, long-press for the previous one. */
@Composable
fun ReadingHandle(hasNext: Boolean, onNext: () -> Unit, onPrevious: () -> Unit) {
    val c = Monet.colors
    Box(
        Modifier
            .size(ReadingHandleSize)
            .clip(CircleShape)
            .background(c.surface)
            .border(1.dp, c.hairline, CircleShape)
            .semantics(mergeDescendants = true) {
                role = Role.Button
                contentDescription = "Next lines"
                onClick("Next lines") { onNext(); true }
                onLongClick("Previous lines") { onPrevious(); true }
            }
            .pointerInput(Unit) {
                detectTapGestures(onTap = { onNext() }, onLongPress = { onPrevious() })
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Outlined.KeyboardArrowDown, contentDescription = null, tint = if (hasNext) c.seal else c.inkFaint)
    }
}
