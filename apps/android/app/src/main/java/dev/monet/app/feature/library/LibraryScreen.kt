package dev.monet.app.feature.library

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import dev.monet.app.core.network.Document
import dev.monet.app.core.network.DocumentKind
import dev.monet.app.core.network.DocumentStatus
import dev.monet.app.core.network.Lang
import dev.monet.app.core.network.SourceLang
import dev.monet.app.ui.theme.Monet
import dev.monet.app.ui.theme.MonetTheme
import dev.monet.app.ui.theme.Radius
import dev.monet.app.ui.theme.Spacing

class LibraryActions(
    val onBack: () -> Unit,
    val onAdd: () -> Unit,
    val onOpen: (Document) -> Unit,
    val onDelete: (Document) -> Unit,
)

private val DOCUMENT_MIME_TYPES = arrayOf(
    mimeFor(DocumentKind.PDF),
    mimeFor(DocumentKind.DOCX),
    mimeFor(DocumentKind.TXT),
)

/** Wires the screen to its ViewModel, the system file picker and the polling loop. */
@Composable
fun LibraryRoute(onBack: () -> Unit, onOpen: (String) -> Unit) {
    val context = LocalContext.current
    val container = (context.applicationContext as MonetApp).container
    val viewModel: LibraryViewModel = viewModel(
        factory = viewModelFactory {
            initializer { LibraryViewModel(container.api, container.settingsRepository, context.applicationContext.contentResolver) }
        },
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    // Leaving the screen cancels this effect, which stops the polling.
    LaunchedEffect(viewModel) { viewModel.poll() }
    LaunchedEffect(state.message) {
        state.message?.let {
            Toast.makeText(context, it, Toast.LENGTH_LONG).show()
            viewModel.messageShown()
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.upload(uri)
    }
    LibraryScreen(
        state = state,
        actions = LibraryActions(
            onBack = onBack,
            onAdd = { picker.launch(DOCUMENT_MIME_TYPES) },
            onOpen = { onOpen(it.id) },
            onDelete = { viewModel.delete(it.id) },
        ),
    )
}

@Composable
fun LibraryScreen(state: LibraryUiState, actions: LibraryActions, modifier: Modifier = Modifier) {
    val c = Monet.colors
    val t = Monet.type
    Column(
        modifier
            .fillMaxSize()
            .background(c.paper)
            .systemBarsPadding()
            .padding(horizontal = Spacing.gutter),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(vertical = Spacing.x2),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = actions.onBack) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back", tint = c.ink)
                }
                Text("Documents", style = t.title, color = c.ink)
            }
            TextButton(onClick = actions.onAdd, enabled = !state.uploading) {
                Icon(Icons.Outlined.Add, contentDescription = null, tint = if (state.uploading) c.inkFaint else c.seal)
                Text(
                    if (state.uploading) "Uploading…" else "Add",
                    style = t.label,
                    color = if (state.uploading) c.inkFaint else c.seal,
                    modifier = Modifier.padding(start = Spacing.x1),
                )
            }
        }

        state.error?.let {
            Row(
                Modifier.fillMaxWidth().padding(bottom = Spacing.x2),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(it, style = t.body, color = c.danger, modifier = Modifier.weight(1f))
            }
        }

        when {
            !state.loaded -> Text("Loading…", style = t.body, color = c.inkMuted, modifier = Modifier.padding(top = Spacing.x4))
            state.items.isEmpty() -> EmptyState(actions.onAdd)
            else -> LazyColumn(Modifier.fillMaxSize()) {
                items(state.items, key = { it.id }) { doc ->
                    DocumentRow(doc, actions)
                    HorizontalDivider(color = c.hairline)
                }
            }
        }
    }
}

@Composable
private fun EmptyState(onAdd: () -> Unit) {
    val c = Monet.colors
    Column(Modifier.fillMaxWidth().padding(top = Spacing.x8), verticalArrangement = Arrangement.spacedBy(Spacing.x2)) {
        Text("No documents yet.", style = Monet.type.body, color = c.inkMuted)
        TextButton(onClick = onAdd) { Text("Add a document", style = Monet.type.label, color = c.seal) }
    }
}

@Composable
private fun DocumentRow(doc: Document, actions: LibraryActions) {
    val c = Monet.colors
    val t = Monet.type
    var menuOpen by remember { mutableStateOf(false) }
    val openable = doc.status == DocumentStatus.READY
    val status = statusLabel(doc)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clickable(enabled = openable) { actions.onOpen(doc) }
            .semantics(mergeDescendants = true) { contentDescription = "${doc.title}, $status" }
            .padding(vertical = Spacing.x3),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.x3),
    ) {
        Icon(
            if (doc.kind == DocumentKind.PDF) Icons.Outlined.PictureAsPdf else Icons.Outlined.Description,
            contentDescription = null,
            tint = c.inkMuted,
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.x1)) {
            Text(doc.title, style = t.body, color = if (doc.status == DocumentStatus.FAILED) c.inkMuted else c.ink, maxLines = 1)
            Text(
                status,
                style = t.label,
                color = when (doc.status) {
                    DocumentStatus.READY -> c.success
                    DocumentStatus.FAILED -> c.danger
                    else -> c.inkMuted
                },
            )
            progressFraction(doc)?.let { ProgressRule(it) }
        }
        Box {
            IconButton(onClick = { menuOpen = true }) {
                Icon(Icons.Outlined.MoreVert, contentDescription = "More options for ${doc.title}", tint = c.inkMuted)
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }, containerColor = c.surface) {
                DropdownMenuItem(
                    text = { Text("Delete", style = t.body, color = c.danger) },
                    onClick = {
                        menuOpen = false
                        actions.onDelete(doc)
                    },
                )
            }
        }
    }
}

/** A 2dp rule with the filled part in seal: the design system's progress indicator. */
@Composable
private fun ProgressRule(fraction: Float) {
    val c = Monet.colors
    Box(Modifier.fillMaxWidth().height(2.dp).background(c.hairline)) {
        Box(Modifier.fillMaxWidth(fraction).height(2.dp).background(c.seal))
    }
}

private fun previewDoc(id: String, title: String, kind: DocumentKind, status: DocumentStatus, progress: Double, pages: Int?, error: String? = null) =
    Document(id, title, kind, status, progress, pages, 1024, SourceLang.AUTO, Lang.EN, error, "2026-10-10T10:00:00Z", "2026-10-10T10:00:00Z")

private val previewItems = listOf(
    previewDoc("1", "Three-Body Problem, ch. 1", DocumentKind.PDF, DocumentStatus.PROCESSING, 0.42, null),
    previewDoc("2", "Lecture notes", DocumentKind.DOCX, DocumentStatus.READY, 1.0, 12),
    previewDoc("3", "Subtitles", DocumentKind.TXT, DocumentStatus.QUEUED, 0.0, null),
    previewDoc("4", "Scan", DocumentKind.PDF, DocumentStatus.FAILED, 0.0, null, "No text found"),
)

private val previewActions = LibraryActions({}, {}, {}, {})

@Preview(name = "Library light", showBackground = true, widthDp = 360, heightDp = 640)
@Composable
private fun LibraryLight() = MonetTheme(darkTheme = false) {
    LibraryScreen(LibraryUiState(previewItems, loaded = true), previewActions)
}

@Preview(name = "Library empty dark", showBackground = true, widthDp = 360, heightDp = 640, backgroundColor = 0xFF151412)
@Composable
private fun LibraryEmptyDark() = MonetTheme(darkTheme = true) {
    LibraryScreen(LibraryUiState(emptyList(), loaded = true), previewActions)
}
