package dev.monet.app.feature.lens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.monet.app.ui.theme.Monet
import dev.monet.app.ui.theme.MonetTheme
import dev.monet.app.ui.theme.Radius
import dev.monet.app.ui.theme.Spacing
import kotlinx.coroutines.delay

class LensOverlayActions(
    val onToggle: () -> Unit,
    val onTurnOff: () -> Unit,
    val onDragStart: (x: Float, y: Float) -> Unit,
    val onDrag: (x: Float, y: Float) -> Unit,
    val onDragEnd: () -> Unit,
    val onMode: (LensMode) -> Unit,
    val onLines: (Int) -> Unit,
)

val BubbleSize = 48.dp
val OverlayPillWidth = 272.dp

@Composable
fun LensOverlay(state: LensUiState, expanded: Boolean, rightSide: Boolean, actions: LensOverlayActions) {
    var touches by remember { mutableIntStateOf(0) }
    var idle by remember { mutableStateOf(false) }
    // Fades to 60% after 3 s without interaction; stays solid while the pill is open.
    LaunchedEffect(touches, expanded) {
        idle = false
        if (!expanded) {
            delay(3_000)
            idle = true
        }
    }
    val alpha by animateFloatAsState(if (idle) 0.6f else 1f, label = "bubbleAlpha")

    Column(
        Modifier.fillMaxWidth().alpha(alpha),
        horizontalAlignment = if (rightSide) Alignment.End else Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(Spacing.x2),
    ) {
        Bubble(
            actions = actions,
            onTouch = { touches++ },
        )
        if (expanded) ModePill(state, actions)
    }
}

@Composable
private fun Bubble(actions: LensOverlayActions, onTouch: () -> Unit) {
    val c = Monet.colors
    Box(
        Modifier
            .size(BubbleSize)
            .clip(CircleShape)
            .background(c.surface)
            .border(1.dp, c.hairline, CircleShape)
            .semantics(mergeDescendants = true) {
                contentDescription = "Monet lens"
                onClick("Open lens options") { actions.onToggle(); true }
                onLongClick("Turn lens off") { actions.onTurnOff(); true }
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = { onTouch() },
                    onTap = { actions.onToggle() },
                    onLongPress = { actions.onTurnOff() },
                )
            }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { actions.onDragStart(it.x, it.y) },
                    onDragEnd = actions.onDragEnd,
                    onDragCancel = actions.onDragEnd,
                ) { change, _ ->
                    change.consume()
                    actions.onDrag(change.position.x, change.position.y)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        SealMark(Modifier.size(24.dp))
    }
}

/** The custom seal glyph: a red square with three paper-colored strokes (see ic_seal). */
@Composable
fun SealMark(modifier: Modifier = Modifier) {
    val c = Monet.colors
    Canvas(modifier) {
        val u = size.width / 10f
        drawRoundRect(c.seal, Offset.Zero, size, CornerRadius(u))
        drawRect(c.paper, Offset(2.5f * u, 2.5f * u), Size(2 * u, 5 * u))
        drawRect(c.paper, Offset(5.5f * u, 2.5f * u), Size(2 * u, 2 * u))
        drawRect(c.paper, Offset(5.5f * u, 5.5f * u), Size(2 * u, 2 * u))
    }
}

@Composable
private fun ModePill(state: LensUiState, actions: LensOverlayActions) {
    val c = Monet.colors
    val shape = RoundedCornerShape(Radius.lg)
    Column(
        Modifier
            .widthIn(max = OverlayPillWidth)
            .background(c.surface, shape)
            .border(1.dp, c.hairline, shape)
            .padding(Spacing.x2),
        verticalArrangement = Arrangement.spacedBy(Spacing.x1),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.x1)) {
            Segment("Auto", state.mode == LensMode.AUTO, Modifier.weight(1f)) { actions.onMode(LensMode.AUTO) }
            Segment("Select", state.mode == LensMode.SELECT, Modifier.weight(1f)) { actions.onMode(LensMode.SELECT) }
            Segment("Off", false, Modifier.weight(1f)) { actions.onMode(LensMode.OFF) }
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = Spacing.x2),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("Lines per step", style = Monet.type.label, color = c.inkMuted)
            Row(verticalAlignment = Alignment.CenterVertically) {
                StepButton("–", "Fewer lines", state.linesPerStep > MIN_LINES_PER_STEP) {
                    actions.onLines(stepLines(state.linesPerStep, -1))
                }
                Text("${state.linesPerStep}", style = Monet.type.title, color = c.ink)
                StepButton("+", "More lines", state.linesPerStep < MAX_LINES_PER_STEP) {
                    actions.onLines(stepLines(state.linesPerStep, +1))
                }
            }
        }
    }
}

@Composable
private fun Segment(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val c = Monet.colors
    val shape = RoundedCornerShape(Radius.sm)
    Box(
        modifier
            .heightIn(min = 48.dp)
            .clip(shape)
            .background(if (selected) c.sealSoft else androidx.compose.ui.graphics.Color.Transparent)
            .border(1.dp, if (selected) c.seal else c.hairline, shape)
            .semantics { role = Role.RadioButton; this.selected = selected }
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = Monet.type.label, color = if (selected) c.seal else c.ink)
    }
}

@Composable
private fun StepButton(glyph: String, description: String, enabled: Boolean, onClick: () -> Unit) {
    val c = Monet.colors
    Box(
        Modifier
            .size(48.dp)
            .semantics { contentDescription = description; role = Role.Button }
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(glyph, style = Monet.type.title, color = if (enabled) c.ink else c.inkFaint)
    }
}

private val previewActions = LensOverlayActions({}, {}, { _, _ -> }, { _, _ -> }, {}, {}, {})

@Preview(name = "Lens expanded light", showBackground = true, widthDp = 320, heightDp = 220)
@Composable
private fun LensExpandedLight() = MonetTheme(darkTheme = false) {
    LensOverlay(LensUiState(running = true), expanded = true, rightSide = false, actions = previewActions)
}

@Preview(name = "Lens collapsed dark", showBackground = true, widthDp = 120, heightDp = 80, backgroundColor = 0xFF151412)
@Composable
private fun LensCollapsedDark() = MonetTheme(darkTheme = true) {
    LensOverlay(LensUiState(running = true), expanded = false, rightSide = true, actions = previewActions)
}
