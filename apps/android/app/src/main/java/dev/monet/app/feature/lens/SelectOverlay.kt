package dev.monet.app.feature.lens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.monet.app.core.network.Annotation
import dev.monet.app.feature.lens.capture.ScreenLine
import dev.monet.app.feature.lens.capture.SelRect
import dev.monet.app.feature.lens.capture.SelectionResult
import dev.monet.app.feature.lens.capture.selRect
import dev.monet.app.ui.theme.Monet
import dev.monet.app.ui.theme.Radius
import dev.monet.app.ui.theme.Spacing

sealed interface CardState {
    data object Loading : CardState
    data class Ready(val annotation: Annotation) : CardState
    data class Failed(val offline: Boolean, val message: String) : CardState
}

data class SelectionUiState(
    val frame: ImageBitmap,
    val lines: List<ScreenLine>,
    val result: SelectionResult? = null,
    val card: CardState = CardState.Loading,
)

class SelectActions(
    /** A finished gesture in frame pixels. */
    val onGesture: (SelRect) -> Unit,
    val onClose: () -> Unit,
    val onDetails: () -> Unit,
    val onRetry: () -> Unit,
)

@Composable
fun SelectOverlay(state: SelectionUiState, actions: SelectActions) {
    val c = Monet.colors
    var size by remember { mutableStateOf(IntSize.Zero) }
    var start by remember { mutableStateOf<Offset?>(null) }
    var current by remember { mutableStateOf<Offset?>(null) }
    // The overlay window and the frame cover the same display, but map through the ratio to be safe.
    val scaleX = if (size.width > 0) state.frame.width.toFloat() / size.width else 1f
    val scaleY = if (size.height > 0) state.frame.height.toFloat() / size.height else 1f

    Box(Modifier.fillMaxSize().onSizeChanged { size = it }) {
        Image(
            bitmap = state.frame,
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.FillBounds,
        )
        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInput(scaleX, scaleY) {
                    detectDragGestures(
                        onDragStart = { start = it; current = it },
                        onDrag = { change, _ -> change.consume(); current = change.position },
                        onDragEnd = {
                            val s = start
                            val e = current
                            start = null
                            current = null
                            if (s != null && e != null) actions.onGesture(selRect(s.x * scaleX, s.y * scaleY, e.x * scaleX, e.y * scaleY))
                        },
                        onDragCancel = { start = null; current = null },
                    )
                }
                .pointerInput(scaleX, scaleY) {
                    detectTapGestures(onTap = { p ->
                        actions.onGesture(selRect(p.x * scaleX, p.y * scaleY, p.x * scaleX, p.y * scaleY))
                    })
                },
        ) {
            val s = start
            val e = current
            val highlight: androidx.compose.ui.geometry.Rect? = when {
                s != null && e != null -> androidx.compose.ui.geometry.Rect(s, e)
                state.result != null -> state.result.bounds.let {
                    androidx.compose.ui.geometry.Rect(it.left / scaleX, it.top / scaleY, it.right / scaleX, it.bottom / scaleY)
                }
                else -> null
            }
            val dim = Color.Black.copy(alpha = 0.45f)
            if (highlight == null) {
                drawRect(dim)
            } else {
                // Dim everything except the highlighted area.
                drawRect(dim, Offset.Zero, Size(size.width.toFloat(), highlight.top))
                drawRect(dim, Offset(0f, highlight.bottom), Size(size.width.toFloat(), size.height - highlight.bottom))
                drawRect(dim, Offset(0f, highlight.top), Size(highlight.left, highlight.height))
                drawRect(dim, Offset(highlight.right, highlight.top), Size(size.width - highlight.right, highlight.height))
                drawRect(c.sealSoft, highlight.topLeft, highlight.size)
                drawRoundRect(c.seal, highlight.topLeft, highlight.size, CornerRadius(4.dp.toPx()), style = Stroke(2.dp.toPx()))
            }
            // Faint outlines show what OCR found, so the user knows what can be picked.
            if (state.result == null) {
                state.lines.forEach { l ->
                    drawRoundRect(
                        Color.White.copy(alpha = 0.55f),
                        Offset(l.left / scaleX, l.top / scaleY),
                        Size(l.width / scaleX, l.height / scaleY),
                        CornerRadius(2.dp.toPx()),
                        style = Stroke(1.dp.toPx()),
                    )
                }
            }
        }

        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(start = Spacing.x4, end = Spacing.x4, top = 36.dp, bottom = Spacing.x2),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (state.result == null) "Drag over text, or tap a block" else "",
                style = Monet.type.label,
                color = Color.White,
            )
            TextButton(onClick = actions.onClose, modifier = Modifier.semantics { contentDescription = "Close selection" }) {
                Text("Close", style = Monet.type.label, color = Color.White)
            }
        }

        if (state.result != null) {
            TranslationCard(
                source = state.result.text,
                card = state.card,
                actions = actions,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }
}

@Composable
private fun TranslationCard(source: String, card: CardState, actions: SelectActions, modifier: Modifier) {
    val c = Monet.colors
    val t = Monet.type
    Surface(
        color = c.surface,
        shape = RoundedCornerShape(topStart = Radius.lg, topEnd = Radius.lg),
        border = BorderStroke(1.dp, c.hairline),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            Modifier.navigationBarsPadding().padding(start = Spacing.gutter, end = Spacing.gutter, top = Spacing.x4, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(Spacing.x2),
        ) {
            Text(
                source,
                style = t.body,
                color = c.inkMuted,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            when (card) {
                CardState.Loading -> Text("Translating…", style = t.reading, color = c.inkFaint)
                is CardState.Ready -> {
                    val a = card.annotation
                    if (a.translation.isEmpty()) {
                        Text(a.note ?: "Translation unavailable right now.", style = t.body, color = c.ink)
                    } else {
                        Text(
                            a.translation,
                            style = t.reading,
                            color = c.ink,
                            modifier = Modifier.heightIn(max = 220.dp),
                        )
                    }
                }
                is CardState.Failed -> Text(
                    if (card.offline) "Can't reach the server." else card.message,
                    style = t.body,
                    color = c.ink,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.x2)) {
                val needsRetry = card is CardState.Failed || (card is CardState.Ready && card.annotation.translation.isEmpty())
                if (needsRetry) {
                    TextButton(onClick = actions.onRetry) { Text("Try again", style = t.label, color = c.seal) }
                }
                TextButton(onClick = actions.onDetails) { Text("Details", style = t.label, color = c.seal) }
            }
        }
    }
}
