package dev.monet.app.feature.reader

import android.graphics.Bitmap
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.monet.app.MonetApp
import dev.monet.app.core.network.Document
import dev.monet.app.core.network.DocumentKind
import dev.monet.app.core.network.DocumentLine
import dev.monet.app.core.network.DocumentStatus
import dev.monet.app.feature.library.statusLabel
import dev.monet.app.ui.theme.Monet
import dev.monet.app.ui.theme.Radius
import dev.monet.app.ui.theme.Spacing

class ReaderActions(
    val onBack: () -> Unit,
    val onLoadPage: (Int) -> Unit,
    val onRetryPage: (Int) -> Unit,
    val onSelect: (pageIndex: Int, line: DocumentLine?) -> Unit,
    val onExplain: (String) -> Unit,
)

@Composable
fun ReaderRoute(documentId: String, onBack: () -> Unit, onExplain: (String) -> Unit) {
    val context = LocalContext.current
    val container = (context.applicationContext as MonetApp).container
    val viewModel: ReaderViewModel = viewModel(
        key = "reader:$documentId",
        factory = viewModelFactory {
            initializer { ReaderViewModel(container.api, documentId, context.applicationContext.cacheDir) }
        },
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    ReaderScreen(
        state = state,
        bitmapFor = viewModel::pageBitmap,
        actions = ReaderActions(
            onBack = onBack,
            onLoadPage = viewModel::loadPage,
            onRetryPage = viewModel::retryPage,
            onSelect = viewModel::select,
            onExplain = onExplain,
        ),
    )
}

@Composable
fun ReaderScreen(
    state: ReaderUiState,
    bitmapFor: suspend (index: Int, widthPx: Int) -> Bitmap?,
    actions: ReaderActions,
    modifier: Modifier = Modifier,
) {
    val c = Monet.colors
    val t = Monet.type
    val doc = state.document
    Box(modifier.fillMaxSize().background(c.paper).systemBarsPadding()) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = Spacing.x2, vertical = Spacing.x2),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = actions.onBack) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back", tint = c.ink)
                }
                Text(doc?.title ?: "Document", style = t.title, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            HorizontalDivider(color = c.hairline)
            when {
                doc == null && state.error != null -> Notice(state.error)
                doc == null -> Notice("Loading…")
                doc.status == DocumentStatus.FAILED -> Notice(statusLabel(doc))
                doc.status != DocumentStatus.READY -> Notice(statusLabel(doc))
                state.error != null -> Notice(state.error)
                doc.kind == DocumentKind.PDF && !state.pdfReady -> Notice("Opening…")
                doc.kind == DocumentKind.PDF -> PdfPageList(doc, state, bitmapFor, actions)
                else -> ReflowPages(doc, state, actions)
            }
        }
        state.selected?.let { selected ->
            LineCard(
                line = selected.line,
                onDetails = { actions.onExplain(selected.line.text) },
                onClose = { actions.onSelect(selected.pageIndex, null) },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }
}

@Composable
private fun Notice(text: String) {
    Text(
        text,
        style = Monet.type.body,
        color = Monet.colors.inkMuted,
        modifier = Modifier.padding(horizontal = Spacing.gutter, vertical = Spacing.x6),
    )
}

// ---- PDF -----------------------------------------------------------------------------------

@Composable
private fun PdfPageList(
    doc: Document,
    state: ReaderUiState,
    bitmapFor: suspend (Int, Int) -> Bitmap?,
    actions: ReaderActions,
) {
    val density = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val widthPx = with(density) { maxWidth.roundToPx() }
        LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(Spacing.x2)) {
            items(doc.pageCount ?: 1, key = { it }) { index ->
                PdfPageItem(index, widthPx, state.pages[index], state.selected?.takeIf { it.pageIndex == index }?.line, bitmapFor, actions)
            }
            item { Box(Modifier.height(Spacing.x12 * 3)) }
        }
    }
}

@Composable
private fun PdfPageItem(
    index: Int,
    widthPx: Int,
    load: PageLoad?,
    selected: DocumentLine?,
    bitmapFor: suspend (Int, Int) -> Bitmap?,
    actions: ReaderActions,
) {
    val c = Monet.colors
    val density = LocalDensity.current
    LaunchedEffect(index) { actions.onLoadPage(index) }
    var bitmap by remember(index, widthPx) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(index, widthPx) { bitmap = bitmapFor(index, widthPx) }
    val page = (load as? PageLoad.Ready)?.page
    val heightPx = bitmap?.height ?: fitHeight(page?.width, page?.height, widthPx)

    Box(
        Modifier
            .fillMaxWidth()
            .height(with(density) { heightPx.toDp() })
            .background(c.surface)
            .semantics { contentDescription = "Page ${index + 1}" }
            .pointerInput(page) {
                detectTapGestures { p ->
                    val hit = page?.let { hitLine(it, p.x, p.y, size.width.toFloat(), size.height.toFloat(), slopPx = 8.dp.toPx()) }
                    actions.onSelect(index, hit)
                }
            },
    ) {
        bitmap?.let {
            Image(it.asImageBitmap(), contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
        }
        Canvas(Modifier.fillMaxSize()) {
            val box = selected?.let { normalizedToPx(it.bbox, size.width, size.height) } ?: return@Canvas
            drawRoundRect(c.sealSoft, Offset(box.left, box.top), Size(box.width, box.height), CornerRadius(2.dp.toPx()))
            drawRoundRect(c.seal, Offset(box.left, box.top), Size(box.width, box.height), CornerRadius(2.dp.toPx()), style = Stroke(1.5.dp.toPx()))
        }
        if (load is PageLoad.Failed) {
            Row(
                Modifier.align(Alignment.TopCenter).fillMaxWidth().background(c.surface).padding(Spacing.x2),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(load.message, style = Monet.type.label, color = c.inkMuted, modifier = Modifier.weight(1f))
                TextButton(onClick = { actions.onRetryPage(index) }) { Text("Try again", style = Monet.type.label, color = c.seal) }
            }
        }
    }
}

// ---- Reflow (DOCX, TXT) --------------------------------------------------------------------

@Composable
private fun ReflowPages(doc: Document, state: ReaderUiState, actions: ReaderActions) {
    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = Spacing.gutter),
        verticalArrangement = Arrangement.spacedBy(Spacing.x6),
    ) {
        item { Box(Modifier.height(Spacing.x2)) }
        items(doc.pageCount ?: 1, key = { it }) { index ->
            ReflowPage(index, state.pages[index], state.selected?.takeIf { it.pageIndex == index }?.line, actions)
        }
        item { Box(Modifier.height(Spacing.x12 * 3)) }
    }
}

@Composable
private fun ReflowPage(index: Int, load: PageLoad?, selected: DocumentLine?, actions: ReaderActions) {
    val c = Monet.colors
    val t = Monet.type
    LaunchedEffect(index) { actions.onLoadPage(index) }
    when (load) {
        is PageLoad.Ready -> Column(verticalArrangement = Arrangement.spacedBy(Spacing.x4)) {
            load.page.blocks.forEach { block ->
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.x1)) {
                    block.lines.forEach { line ->
                        val isSelected = selected?.id == line.id
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .background(if (isSelected) c.sealSoft else androidx.compose.ui.graphics.Color.Transparent, RoundedCornerShape(Radius.xs))
                                .clickable { actions.onSelect(index, if (isSelected) null else line) }
                                .padding(vertical = Spacing.x1),
                        ) {
                            Text(line.text, style = t.reading, color = c.ink)
                            if (line.annotation.translation.isNotEmpty()) {
                                Text(line.annotation.translation, style = t.body, color = c.inkMuted)
                            }
                        }
                    }
                }
            }
        }
        is PageLoad.Failed -> Row(verticalAlignment = Alignment.CenterVertically) {
            Text(load.message, style = t.body, color = c.inkMuted, modifier = Modifier.weight(1f))
            TextButton(onClick = { actions.onRetryPage(index) }) { Text("Try again", style = t.label, color = c.seal) }
        }
        else -> Text("Translating…", style = t.label, color = c.inkFaint)
    }
}

// ---- Selected line card --------------------------------------------------------------------

@Composable
private fun LineCard(line: DocumentLine, onDetails: () -> Unit, onClose: () -> Unit, modifier: Modifier) {
    val c = Monet.colors
    val t = Monet.type
    Surface(
        color = c.surface,
        shape = RoundedCornerShape(topStart = Radius.lg, topEnd = Radius.lg),
        border = BorderStroke(1.dp, c.hairline),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            Modifier.navigationBarsPadding().padding(horizontal = Spacing.gutter, vertical = Spacing.x4),
            verticalArrangement = Arrangement.spacedBy(Spacing.x2),
        ) {
            Text(line.text, style = t.body, color = c.inkMuted, maxLines = 3, overflow = TextOverflow.Ellipsis)
            val translation = line.annotation.translation
            Text(
                translation.ifEmpty { "Translation unavailable right now." },
                style = if (translation.isEmpty()) t.body else t.reading,
                color = c.ink,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.x2)) {
                TextButton(onClick = onDetails) { Text("Details", style = t.label, color = c.seal) }
                TextButton(onClick = onClose) { Text("Close", style = t.label, color = c.inkMuted) }
            }
        }
    }
}
