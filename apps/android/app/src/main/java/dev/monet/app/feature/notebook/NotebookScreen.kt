package dev.monet.app.feature.notebook

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.monet.app.MonetApp
import dev.monet.app.core.data.NotebookEntry
import dev.monet.app.core.network.Annotation
import dev.monet.app.core.network.FakeMonetApi
import dev.monet.app.ui.theme.Monet
import dev.monet.app.ui.theme.MonetTheme
import dev.monet.app.ui.theme.Radius
import dev.monet.app.ui.theme.Spacing

class NotebookActions(
    val onBack: () -> Unit,
    val onQuery: (String) -> Unit,
    val onOpen: (Annotation) -> Unit,
    val onReview: () -> Unit,
    val onDelete: (NotebookEntry) -> Unit,
    val onConfirmDelete: () -> Unit,
    val onCancelDelete: () -> Unit,
)

@Composable
fun NotebookRoute(onBack: () -> Unit, onOpen: (Annotation) -> Unit, onReview: () -> Unit) {
    val container = (LocalContext.current.applicationContext as MonetApp).container
    val viewModel: NotebookViewModel = viewModel(
        factory = viewModelFactory { initializer { NotebookViewModel(container.notebook) } },
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    NotebookScreen(
        state = state,
        now = System.currentTimeMillis(),
        actions = NotebookActions(
            onBack = onBack,
            onQuery = viewModel::onQuery,
            onOpen = onOpen,
            onReview = onReview,
            onDelete = viewModel::requestDelete,
            onConfirmDelete = viewModel::confirmDelete,
            onCancelDelete = viewModel::cancelDelete,
        ),
    )
}

@Composable
fun NotebookScreen(state: NotebookUiState, now: Long, actions: NotebookActions, modifier: Modifier = Modifier) {
    val c = Monet.colors
    val t = Monet.type
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
            Text("Notebook", style = t.title, color = c.ink)
        }
        if (state.total > 0) {
            Button(
                onClick = actions.onReview,
                enabled = state.dueToday > 0,
                shape = RoundedCornerShape(Radius.sm),
                colors = ButtonDefaults.buttonColors(
                    containerColor = c.seal,
                    contentColor = c.paper,
                    disabledContainerColor = c.surfaceSunken,
                    disabledContentColor = c.inkFaint,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics {
                        contentDescription = if (state.dueToday > 0) "Review ${state.dueToday} due cards" else "Nothing due to review today"
                    },
            ) {
                Text(if (state.dueToday > 0) "Review ${state.dueToday} due today" else "Nothing due today", style = t.label)
            }
            TextField(
                value = state.query,
                onValueChange = actions.onQuery,
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = Spacing.x3)
                    .semantics { contentDescription = "Search the notebook" },
                textStyle = t.body.copy(color = c.ink),
                placeholder = { Text("Search saved lines", style = t.body, color = c.inkFaint) },
                shape = RoundedCornerShape(Radius.sm),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = c.surfaceSunken,
                    unfocusedContainerColor = c.surfaceSunken,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    cursorColor = c.seal,
                ),
            )
        }
        when {
            !state.loaded -> Text("Loading…", style = t.body, color = c.inkMuted, modifier = Modifier.padding(top = Spacing.x4))
            state.total == 0 -> Text(
                "Nothing saved yet. Tap Save under a translation to keep it here.",
                style = t.body,
                color = c.inkMuted,
                modifier = Modifier.padding(top = Spacing.x8),
            )
            state.items.isEmpty() -> Text(
                "No saved line matches \"${state.query}\".",
                style = t.body,
                color = c.inkMuted,
                modifier = Modifier.padding(top = Spacing.x6),
            )
            else -> LazyColumn(Modifier.fillMaxSize().padding(top = Spacing.x2)) {
                items(state.items, key = { it.id }) { entry ->
                    EntryRow(entry, now, actions)
                    HorizontalDivider(color = c.hairline)
                }
            }
        }
    }
    state.pendingDelete?.let { entry ->
        AlertDialog(
            onDismissRequest = actions.onCancelDelete,
            containerColor = c.surface,
            title = { Text("Delete this line?", style = t.title, color = c.ink) },
            text = {
                Text(
                    "\"${entry.annotation.text}\" and its review progress will be removed from the notebook.",
                    style = t.body,
                    color = c.inkMuted,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            confirmButton = { TextButton(onClick = actions.onConfirmDelete) { Text("Delete", style = t.label, color = c.danger) } },
            dismissButton = { TextButton(onClick = actions.onCancelDelete) { Text("Keep", style = t.label, color = c.inkMuted) } },
        )
    }
}

@Composable
private fun EntryRow(entry: NotebookEntry, now: Long, actions: NotebookActions) {
    val c = Monet.colors
    val t = Monet.type
    var menuOpen by remember { mutableStateOf(false) }
    val due = dueLabel(entry, now)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clickable { actions.onOpen(entry.annotation) }
            .semantics(mergeDescendants = true) {
                contentDescription = "${entry.annotation.text}, ${entry.annotation.translation}, $due. Tap for details"
            }
            .padding(vertical = Spacing.x3),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.x3),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.x1)) {
            Text(entry.annotation.text, style = t.body, color = c.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                entry.annotation.translation.ifEmpty { "No translation saved" },
                style = t.body,
                color = c.inkMuted,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(due, style = t.label, color = if (entry.dueAt <= now) c.seal else c.inkFaint)
        }
        IconButton(onClick = { menuOpen = true }) {
            Icon(Icons.Outlined.MoreVert, contentDescription = "More actions for ${entry.annotation.text}", tint = c.inkMuted)
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(
                text = { Text("Delete", style = t.body, color = c.danger) },
                onClick = {
                    menuOpen = false
                    actions.onDelete(entry)
                },
            )
        }
    }
}

// ---- Previews ------------------------------------------------------------------------------

private val previewActions = NotebookActions({}, {}, {}, {}, {}, {}, {})

private fun previewState(): NotebookUiState {
    val items = listOf(FakeMonetApi.Samples.chengyu, FakeMonetApi.Samples.meme).mapIndexed { i, a ->
        NotebookEntry(id = "p$i", savedAt = i.toLong(), annotation = a, dueAt = if (i == 0) 0L else 3L * 24 * 60 * 60 * 1000)
    }
    return NotebookUiState(loaded = true, items = items, total = 2, dueToday = 1)
}

@Preview(name = "Notebook light", showBackground = true, widthDp = 360, heightDp = 640)
@Composable
private fun NotebookLight() = MonetTheme(darkTheme = false) { NotebookScreen(previewState(), now = 1000L, actions = previewActions) }

@Preview(name = "Notebook dark", showBackground = true, widthDp = 360, heightDp = 640, backgroundColor = 0xFF151412)
@Composable
private fun NotebookDark() = MonetTheme(darkTheme = true) { NotebookScreen(previewState(), now = 1000L, actions = previewActions) }

@Preview(name = "Notebook large font", showBackground = true, widthDp = 360, heightDp = 640, fontScale = 2f)
@Composable
private fun NotebookLargeFont() = MonetTheme(darkTheme = false) { NotebookScreen(previewState(), now = 1000L, actions = previewActions) }

@Preview(name = "Notebook empty", showBackground = true, widthDp = 360, heightDp = 640)
@Composable
private fun NotebookEmpty() = MonetTheme(darkTheme = false) { NotebookScreen(NotebookUiState(loaded = true), now = 0L, actions = previewActions) }
