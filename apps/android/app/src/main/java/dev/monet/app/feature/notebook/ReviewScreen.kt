package dev.monet.app.feature.notebook

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.monet.app.MonetApp
import dev.monet.app.core.data.Grade
import dev.monet.app.core.data.NotebookEntry
import dev.monet.app.core.network.FakeMonetApi
import dev.monet.app.ui.theme.Monet
import dev.monet.app.ui.theme.MonetTheme
import dev.monet.app.ui.theme.Radius
import dev.monet.app.ui.theme.Spacing

class ReviewActions(
    val onBack: () -> Unit,
    val onReveal: () -> Unit,
    val onGrade: (Grade) -> Unit,
)

@Composable
fun ReviewRoute(onBack: () -> Unit) {
    val container = (LocalContext.current.applicationContext as MonetApp).container
    val viewModel: ReviewViewModel = viewModel(
        factory = viewModelFactory { initializer { ReviewViewModel(container.notebook) } },
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    ReviewScreen(state, ReviewActions(onBack, viewModel::reveal, viewModel::grade))
}

@Composable
fun ReviewScreen(state: ReviewUiState, actions: ReviewActions, modifier: Modifier = Modifier) {
    val c = Monet.colors
    val t = Monet.type
    val session = state.session
    Column(
        modifier
            .fillMaxSize()
            .background(c.paper)
            .systemBarsPadding()
            .padding(horizontal = Spacing.gutter),
    ) {
        Row(Modifier.fillMaxWidth().padding(vertical = Spacing.x2), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = actions.onBack) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back", tint = c.ink)
            }
            Text("Review", style = t.title, color = c.ink, modifier = Modifier.weight(1f))
            if (session != null && !session.done) {
                Text(
                    "${session.position} of ${session.total}",
                    style = t.label,
                    color = c.inkMuted,
                    modifier = Modifier
                        .padding(end = Spacing.x2)
                        .semantics { contentDescription = "Card ${session.position} of ${session.total}" },
                )
            }
        }
        when {
            session == null -> Text("Loading…", style = t.body, color = c.inkMuted, modifier = Modifier.padding(top = Spacing.x4))
            session.total == 0 -> Text("Nothing due today", style = t.reading, color = c.inkMuted, modifier = Modifier.padding(top = Spacing.x8))
            session.done -> Summary(session, actions.onBack)
            else -> CardFlow(session, actions)
        }
    }
}

@Composable
private fun CardFlow(session: ReviewSession, actions: ReviewActions) {
    val c = Monet.colors
    val t = Monet.type
    val entry = session.current ?: return
    val a = entry.annotation
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(Spacing.x4)) {
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .border(1.dp, c.hairline, RoundedCornerShape(Radius.md))
                .background(c.surface, RoundedCornerShape(Radius.md))
                .clickable(enabled = !session.revealed, onClickLabel = "Show the meaning", onClick = actions.onReveal)
                .semantics { if (!session.revealed) contentDescription = "${a.text}. Tap to show the meaning" }
                .verticalScroll(rememberScrollState())
                .padding(Spacing.x4),
            verticalArrangement = Arrangement.spacedBy(Spacing.x4),
        ) {
            Text(a.text, style = t.reading, color = c.ink)
            if (session.revealed) {
                Text(a.translation.ifEmpty { a.note ?: "No translation saved" }, style = t.reading, color = c.seal)
                a.expressions.forEach {
                    Text("${it.text}: ${it.meaning}", style = t.body, color = c.inkMuted)
                }
            } else {
                Text("Tap to show the meaning", style = t.label, color = c.inkFaint)
            }
        }
        if (session.revealed) {
            Row(Modifier.fillMaxWidth().padding(bottom = Spacing.x4), horizontalArrangement = Arrangement.spacedBy(Spacing.x2)) {
                GradeButton("Again", "Again, see it again soon", Modifier.weight(1f)) { actions.onGrade(Grade.AGAIN) }
                GradeButton("Good", "Good, remembered it", Modifier.weight(1f), primary = true) { actions.onGrade(Grade.GOOD) }
                GradeButton("Easy", "Easy, remembered it well", Modifier.weight(1f)) { actions.onGrade(Grade.EASY) }
            }
        } else {
            Button(
                onClick = actions.onReveal,
                shape = RoundedCornerShape(Radius.sm),
                colors = ButtonDefaults.buttonColors(containerColor = c.seal, contentColor = c.paper),
                modifier = Modifier.fillMaxWidth().padding(bottom = Spacing.x4),
            ) { Text("Show meaning", style = t.label) }
        }
    }
}

@Composable
private fun GradeButton(label: String, description: String, modifier: Modifier, primary: Boolean = false, onClick: () -> Unit) {
    val c = Monet.colors
    val shape = RoundedCornerShape(Radius.sm)
    val mod = modifier.heightIn(min = 48.dp).semantics { contentDescription = description }
    if (primary) {
        Button(onClick = onClick, shape = shape, colors = ButtonDefaults.buttonColors(containerColor = c.seal, contentColor = c.paper), modifier = mod) {
            Text(label, style = Monet.type.label)
        }
    } else {
        OutlinedButton(onClick = onClick, shape = shape, modifier = mod) { Text(label, style = Monet.type.label, color = c.ink) }
    }
}

@Composable
private fun Summary(session: ReviewSession, onDone: () -> Unit) {
    val c = Monet.colors
    val t = Monet.type
    Column(Modifier.fillMaxWidth().padding(top = Spacing.x8), verticalArrangement = Arrangement.spacedBy(Spacing.x3)) {
        Text("Done for today", style = t.title, color = c.ink)
        Text(
            "${session.total} reviewed. ${session.knownCount} remembered, ${session.againCount} to see again soon.",
            style = t.body,
            color = c.inkMuted,
        )
        Button(
            onClick = onDone,
            shape = RoundedCornerShape(Radius.sm),
            colors = ButtonDefaults.buttonColors(containerColor = c.seal, contentColor = c.paper),
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Back to notebook", style = t.label) }
    }
}

// ---- Previews ------------------------------------------------------------------------------

private val previewActions = ReviewActions({}, {}, {})

private fun previewSession(revealed: Boolean) = ReviewSession(
    queue = listOf(FakeMonetApi.Samples.chengyu, FakeMonetApi.Samples.meme).mapIndexed { i, a ->
        NotebookEntry(id = "p$i", savedAt = 0, annotation = a, dueAt = 0)
    },
    revealed = revealed,
)

@Preview(name = "Review hidden light", showBackground = true, widthDp = 360, heightDp = 640)
@Composable
private fun ReviewHiddenLight() = MonetTheme(darkTheme = false) { ReviewScreen(ReviewUiState(previewSession(false)), previewActions) }

@Preview(name = "Review revealed dark", showBackground = true, widthDp = 360, heightDp = 640, backgroundColor = 0xFF151412)
@Composable
private fun ReviewRevealedDark() = MonetTheme(darkTheme = true) { ReviewScreen(ReviewUiState(previewSession(true)), previewActions) }

@Preview(name = "Review revealed large font", showBackground = true, widthDp = 360, heightDp = 640, fontScale = 2f)
@Composable
private fun ReviewRevealedLarge() = MonetTheme(darkTheme = false) { ReviewScreen(ReviewUiState(previewSession(true)), previewActions) }

@Preview(name = "Review empty", showBackground = true, widthDp = 360, heightDp = 640)
@Composable
private fun ReviewEmpty() = MonetTheme(darkTheme = false) { ReviewScreen(ReviewUiState(ReviewSession(emptyList())), previewActions) }
