package dev.monet.app.feature.detail

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.SlowMotionVideo
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.monet.app.core.network.Annotation
import dev.monet.app.core.network.Expression
import dev.monet.app.core.network.ExpressionKind
import dev.monet.app.core.network.ExpressionSource
import dev.monet.app.core.network.FakeMonetApi
import dev.monet.app.core.network.Lang
import dev.monet.app.core.network.Token
import dev.monet.app.core.network.TokenKind
import dev.monet.app.core.settings.PinyinStyle
import dev.monet.app.ui.theme.Monet
import dev.monet.app.ui.theme.MonetColors
import dev.monet.app.ui.theme.MonetTheme
import dev.monet.app.ui.theme.Radius
import dev.monet.app.ui.theme.Spacing

class DetailActions(
    val onRetry: () -> Unit,
    val onSpeak: (slow: Boolean) -> Unit,
    val onCopy: () -> Unit,
    val onShare: () -> Unit,
)

@Composable
fun DetailContent(state: DetailUiState, actions: DetailActions, modifier: Modifier = Modifier) {
    Column(
        modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .navigationBarsPadding()
            .padding(horizontal = Spacing.gutter, vertical = Spacing.x4),
        verticalArrangement = Arrangement.spacedBy(Spacing.x6),
    ) {
        when (state) {
            DetailUiState.Loading -> DetailSkeleton()
            is DetailUiState.Failed -> FailedBlock(state, actions.onRetry)
            is DetailUiState.Ready -> ReadyBlock(state, actions)
        }
    }
}

@Composable
private fun ReadyBlock(state: DetailUiState.Ready, actions: DetailActions) {
    val a = state.annotation
    Section("MEANING") {
        if (state.translationMissing) {
            Text(a.note ?: "Translation unavailable right now.", style = Monet.type.body, color = Monet.colors.inkMuted)
            TextButton(onClick = actions.onRetry) {
                Text("Try again", style = Monet.type.label, color = Monet.colors.seal)
            }
        } else {
            Text(a.translation, style = Monet.type.reading, color = Monet.colors.ink)
            a.note?.let { Text(it, style = Monet.type.body, color = Monet.colors.inkMuted) }
        }
        ActionRow(actions)
    }
    HorizontalDivider(color = Monet.colors.hairline)
    Section("BREAKDOWN") { Breakdown(a, state.pinyinStyle) }
    if (a.expressions.isNotEmpty()) {
        HorizontalDivider(color = Monet.colors.hairline)
        Section("WHY IT SAYS THAT") {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.x3)) {
                a.expressions.forEach { ExpressionCard(it) }
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.x3)) {
        Text(title, style = Monet.type.label, color = Monet.colors.inkMuted)
        content()
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ActionRow(actions: DetailActions) {
    val c = Monet.colors
    FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.x1)) {
        ActionButton("Listen", Icons.AutoMirrored.Outlined.VolumeUp, c.seal) { actions.onSpeak(false) }
        ActionButton("Slow", Icons.Outlined.SlowMotionVideo, c.ink) { actions.onSpeak(true) }
        ActionButton("Copy", Icons.Outlined.ContentCopy, c.ink, actions.onCopy)
        ActionButton("Share", Icons.Outlined.Share, c.ink, actions.onShare)
    }
}

@Composable
private fun ActionButton(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, tint: Color, onClick: () -> Unit) {
    TextButton(onClick = onClick) {
        Icon(icon, contentDescription = null, tint = tint)
        Text(label, style = Monet.type.label, color = tint, modifier = Modifier.padding(start = Spacing.x1))
    }
}

// ---- Breakdown -----------------------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Breakdown(a: Annotation, style: PinyinStyle) {
    val underline = remember(a) { underlineByToken(a) }
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(Spacing.x1),
        verticalArrangement = Arrangement.spacedBy(Spacing.x3),
    ) {
        a.tokens.forEachIndexed { i, token ->
            if (token.kind == TokenKind.SPACE) {
                Box(Modifier.width(Spacing.x2))
            } else {
                TokenCell(token, a.sourceLang, style, underline[i])
            }
        }
    }
}

private fun underlineByToken(a: Annotation): Map<Int, UnderlineStyle> = buildMap {
    a.expressions.forEach { e ->
        for (i in e.tokenStart until minOf(e.tokenEnd, a.tokens.size)) put(i, e.kind.underlineStyle())
    }
}

@Composable
private fun TokenCell(token: Token, lang: Lang, style: PinyinStyle, underline: UnderlineStyle?) {
    val c = Monet.colors
    val t = Monet.type
    val ruby = rubyText(token, lang, style, c)
    val description = listOfNotNull(token.text, token.pinyin ?: token.ipa, token.gloss).joinToString(", ")
    Column(
        Modifier
            .semantics(mergeDescendants = true) { contentDescription = description }
            .padding(horizontal = Spacing.x1),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(ruby, style = if (lang == Lang.ZH) t.ruby else t.ipa, textAlign = TextAlign.Center, color = c.inkMuted)
        Text(
            token.text,
            style = if (lang == Lang.ZH) t.hanzi.copy(fontSize = t.reading.fontSize * 1.2f) else t.reading,
            color = if (token.kind == TokenKind.PUNCT) c.inkFaint else c.ink,
            modifier = Modifier.expressionUnderline(underline, c),
        )
        Text(
            token.gloss.orEmpty(),
            style = t.ruby,
            textAlign = TextAlign.Center,
            color = c.inkFaint,
            modifier = Modifier.widthIn(max = 96.dp),
        )
    }
}

internal fun rubyText(token: Token, lang: Lang, style: PinyinStyle, c: MonetColors): AnnotatedString {
    if (lang != Lang.ZH) return AnnotatedString(token.ipa.orEmpty())
    if (style == PinyinStyle.OFF) return AnnotatedString("")
    if (style == PinyinStyle.NUMBERED) return AnnotatedString(token.pinyinNumbered ?: token.pinyin.orEmpty())
    val syllables = splitSyllables(token.pinyin, token.pinyinNumbered)
    return buildAnnotatedString {
        syllables.forEachIndexed { i, s ->
            if (i > 0) append(" ")
            val color = if (style == PinyinStyle.TONE_COLORS) toneColor(s.tone, c) else Color.Unspecified
            withStyle(SpanStyle(color = color)) { append(s.text) }
        }
    }
}

internal fun toneColor(tone: Int, c: MonetColors): Color = when (tone) {
    1 -> c.tone1
    2 -> c.tone2
    3 -> c.tone3
    4 -> c.tone4
    else -> c.tone5
}

private fun Modifier.expressionUnderline(style: UnderlineStyle?, c: MonetColors): Modifier {
    if (style == null) return this
    return drawBehind {
        val stroke = 1.5.dp.toPx()
        val y = size.height - stroke
        when (style) {
            UnderlineStyle.INDIGO_DOTTED -> drawLine(
                color = c.indigo,
                start = Offset(0f, y),
                end = Offset(size.width, y),
                strokeWidth = stroke,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(stroke, stroke * 1.5f)),
            )
            UnderlineStyle.SEAL_WAVY -> {
                val step = 4.dp.toPx()
                val amp = 1.5.dp.toPx()
                val path = Path().apply {
                    moveTo(0f, y)
                    var x = 0f
                    var up = true
                    while (x < size.width) {
                        quadraticTo(x + step / 2, if (up) y - amp else y + amp, x + step, y)
                        x += step
                        up = !up
                    }
                }
                drawPath(path, c.seal, style = Stroke(width = stroke / 1.5f))
            }
        }
    }
}

// ---- Expression card -----------------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ExpressionCard(e: Expression) {
    val c = Monet.colors
    val t = Monet.type
    val accent = if (e.kind.underlineStyle() == UnderlineStyle.INDIGO_DOTTED) c.indigo else c.seal
    Surface(
        color = c.surface,
        shape = RoundedCornerShape(Radius.md),
        border = BorderStroke(1.dp, c.hairline),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(Spacing.x4), verticalArrangement = Arrangement.spacedBy(Spacing.x2)) {
            FlowRow(
                verticalArrangement = Arrangement.spacedBy(Spacing.x1),
                horizontalArrangement = Arrangement.spacedBy(Spacing.x2),
            ) {
                Surface(
                    color = Color.Transparent,
                    shape = RoundedCornerShape(Radius.xs),
                    border = BorderStroke(1.dp, accent),
                ) {
                    Text(
                        e.kind.label(),
                        style = t.label,
                        color = accent,
                        modifier = Modifier.padding(horizontal = Spacing.x2, vertical = 2.dp),
                    )
                }
                Text(e.text, style = t.title, color = c.ink)
                e.pinyin?.let { Text(it, style = t.ruby, color = c.inkMuted) }
            }
            val literal = e.literal
            Text(
                if (literal != null) "$literal → ${e.meaning}" else e.meaning,
                style = t.reading,
                color = c.ink,
            )
            DefinitionRow("Origin", e.origin)
            DefinitionRow("Tone", e.register)
            DefinitionRow("Equivalent", e.equivalent)
            Text(
                if (e.source == ExpressionSource.MODEL) "likely" else "✓ dictionary",
                style = t.label,
                color = c.inkMuted,
            )
        }
    }
}

private fun ExpressionKind.label(): String = when (this) {
    ExpressionKind.CHENGYU -> "chengyu"
    ExpressionKind.IDIOM -> "idiom"
    ExpressionKind.PROVERB -> "proverb"
    ExpressionKind.XIEHOUYU -> "xiehouyu"
    ExpressionKind.MEME -> "meme"
    ExpressionKind.SLANG -> "slang"
    ExpressionKind.WORDPLAY -> "wordplay"
    ExpressionKind.CLASSICAL -> "classical"
    ExpressionKind.OTHER -> "expression"
}

@Composable
private fun DefinitionRow(label: String, value: String?) {
    if (value.isNullOrBlank()) return
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.x3)) {
        Text(label, style = Monet.type.label, color = Monet.colors.inkMuted, modifier = Modifier.width(80.dp))
        Text(value, style = Monet.type.body, color = Monet.colors.ink, modifier = Modifier.weight(1f))
    }
}

// ---- Loading and failure -------------------------------------------------------------------

/** Pulsing placeholders (alpha only: the design system forbids gradients and shimmer sweeps). */
@Composable
private fun DetailSkeleton() {
    val pulse by rememberInfiniteTransition(label = "skeleton").animateFloat(
        initialValue = 0.5f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
        label = "alpha",
    )
    val c = Monet.colors
    Column(
        Modifier
            .alpha(pulse)
            .semantics { contentDescription = "Translating" },
        verticalArrangement = Arrangement.spacedBy(Spacing.x3),
    ) {
        Text("Translating…", style = Monet.type.label, color = c.inkMuted)
        listOf(1f, 0.85f, 0.6f).forEach {
            Box(Modifier.fillMaxWidth(it).height(20.dp).background(c.surfaceSunken, RoundedCornerShape(Radius.xs)))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.x2), modifier = Modifier.padding(top = Spacing.x4)) {
            repeat(5) {
                Box(Modifier.width(48.dp).height(64.dp).background(c.surfaceSunken, RoundedCornerShape(Radius.xs)))
            }
        }
    }
}

@Composable
private fun FailedBlock(state: DetailUiState.Failed, onRetry: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.x2)) {
        Text(
            if (state.offline) "Can't reach the server." else state.message,
            style = Monet.type.body,
            color = Monet.colors.ink,
        )
        TextButton(onClick = onRetry) { Text("Try again", style = Monet.type.label, color = Monet.colors.seal) }
    }
}

// ---- Previews ------------------------------------------------------------------------------

private val previewActions = DetailActions({}, {}, {}, {})

private fun previewState(a: Annotation) = DetailUiState.Ready(a, PinyinStyle.TONE_COLORS)

@Preview(name = "Chengyu light", showBackground = true, widthDp = 360, heightDp = 780)
@Composable
private fun PreviewChengyuLight() = MonetTheme(darkTheme = false) {
    Box(Modifier.background(Monet.colors.paper)) {
        DetailContent(previewState(FakeMonetApi.Samples.chengyu), previewActions)
    }
}

@Preview(name = "Meme dark", showBackground = true, widthDp = 360, heightDp = 780, backgroundColor = 0xFF151412)
@Composable
private fun PreviewMemeDark() = MonetTheme(darkTheme = true) {
    Box(Modifier.background(Monet.colors.paper)) {
        DetailContent(previewState(FakeMonetApi.Samples.meme), previewActions)
    }
}
