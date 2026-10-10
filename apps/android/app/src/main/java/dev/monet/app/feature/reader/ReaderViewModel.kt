package dev.monet.app.feature.reader

import android.graphics.Bitmap
import android.util.LruCache
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.monet.app.core.network.ApiException
import dev.monet.app.core.network.Document
import dev.monet.app.core.network.DocumentKind
import dev.monet.app.core.network.DocumentLine
import dev.monet.app.core.network.DocumentPage
import dev.monet.app.core.network.DocumentStatus
import dev.monet.app.core.network.MonetApi
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface PageLoad {
    data object Loading : PageLoad
    data class Ready(val page: DocumentPage) : PageLoad
    data class Failed(val message: String) : PageLoad
}

data class SelectedLine(val pageIndex: Int, val line: DocumentLine)

data class ReaderUiState(
    val document: Document? = null,
    val error: String? = null,
    /** True once the PDF file is on disk and open for rendering (PDF documents only). */
    val pdfReady: Boolean = false,
    val pages: Map<Int, PageLoad> = emptyMap(),
    val selected: SelectedLine? = null,
)

class ReaderViewModel(
    private val api: MonetApi,
    private val documentId: String,
    private val cacheDir: File,
) : ViewModel() {

    private val _state = MutableStateFlow(ReaderUiState())
    val state: StateFlow<ReaderUiState> = _state.asStateFlow()

    private var pdf: PdfPages? = null
    private val bitmaps = object : LruCache<Int, Bitmap>(4) {}

    init {
        viewModelScope.launch { followDocument() }
    }

    /** Loads the document and, while the server is still working on it, keeps checking. */
    private suspend fun followDocument() {
        while (true) {
            try {
                val doc = api.getDocument(documentId)
                _state.update { it.copy(document = doc, error = null) }
                when (doc.status) {
                    DocumentStatus.READY -> {
                        if (doc.kind == DocumentKind.PDF) preparePdf()
                        return
                    }
                    DocumentStatus.FAILED -> return
                    else -> delay(3_000)
                }
            } catch (e: ApiException) {
                _state.update { it.copy(error = if (e.isOffline) "Can't reach the server." else e.problem?.title ?: "Something went wrong.") }
                return
            }
        }
    }

    private suspend fun preparePdf() {
        try {
            val file = withContext(Dispatchers.IO) {
                val target = File(File(cacheDir, "documents").apply { mkdirs() }, "$documentId.pdf")
                if (!target.exists() || target.length() == 0L) {
                    // Write to a temp name first so a half-downloaded file is never opened.
                    val temp = File(target.parentFile, "$documentId.part")
                    temp.writeBytes(api.downloadDocument(documentId))
                    temp.renameTo(target)
                }
                target
            }
            val opened = withContext(Dispatchers.IO) { PdfPages(file) }
            pdf = opened
            _state.update { it.copy(pdfReady = true) }
        } catch (e: ApiException) {
            _state.update { it.copy(error = if (e.isOffline) "Can't reach the server." else "Could not load the file.") }
        } catch (e: Exception) {
            _state.update { it.copy(error = "Could not open the PDF.") }
        }
    }

    fun loadPage(index: Int) {
        val current = _state.value.pages[index]
        if (current is PageLoad.Loading || current is PageLoad.Ready) return
        _state.update { it.copy(pages = it.pages + (index to PageLoad.Loading)) }
        viewModelScope.launch {
            val result = try {
                PageLoad.Ready(api.getDocumentPage(documentId, index))
            } catch (e: ApiException) {
                PageLoad.Failed(
                    when {
                        e.isOffline -> "Can't reach the server."
                        e.status == 409 -> "This page is still being prepared."
                        else -> e.problem?.title ?: "Could not load this page."
                    },
                )
            }
            _state.update { it.copy(pages = it.pages + (index to result)) }
        }
    }

    fun retryPage(index: Int) {
        _state.update { it.copy(pages = it.pages - index) }
        loadPage(index)
    }

    /** A rendered page, cached for the few pages around the visible one. */
    suspend fun pageBitmap(index: Int, widthPx: Int): Bitmap? {
        bitmaps.get(index)?.let { if (it.width == widthPx) return it }
        val renderer = pdf ?: return null
        return runCatching { renderer.render(index, widthPx) }.getOrNull()?.also { bitmaps.put(index, it) }
    }

    fun select(pageIndex: Int, line: DocumentLine?) =
        _state.update { it.copy(selected = line?.let { l -> SelectedLine(pageIndex, l) }) }

    override fun onCleared() {
        pdf?.close()
        bitmaps.evictAll()
    }
}
