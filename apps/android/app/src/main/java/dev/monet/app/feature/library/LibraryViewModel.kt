package dev.monet.app.feature.library

import android.content.ContentResolver
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.monet.app.core.network.ApiException
import dev.monet.app.core.network.Document
import dev.monet.app.core.network.MonetApi
import dev.monet.app.core.settings.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class LibraryUiState(
    val items: List<Document> = emptyList(),
    val loaded: Boolean = false,
    /** Set when the last refresh failed; the previous items stay visible. */
    val error: String? = null,
    val uploading: Boolean = false,
    /** One-shot notice (rejected file, failed delete); consume with [LibraryViewModel.messageShown]. */
    val message: String? = null,
)

class LibraryViewModel(
    private val api: MonetApi,
    private val settings: SettingsRepository,
    private val resolver: ContentResolver,
) : ViewModel() {

    private val _state = MutableStateFlow(LibraryUiState())
    val state: StateFlow<LibraryUiState> = _state.asStateFlow()

    /** Refreshes forever (fast while something processes). Run it from a LaunchedEffect so it stops with the screen. */
    suspend fun poll() {
        while (true) {
            refresh()
            delay(pollDelayMillis(_state.value.items))
        }
    }

    suspend fun refresh() {
        try {
            val items = api.listDocuments().items
            _state.update { it.copy(items = items, loaded = true, error = null) }
        } catch (e: ApiException) {
            _state.update { it.copy(loaded = true, error = if (e.isOffline) "Can't reach the server." else e.problem?.title) }
        }
    }

    fun upload(uri: Uri) {
        viewModelScope.launch {
            _state.update { it.copy(uploading = true) }
            try {
                uploadFromUri(api, settings, resolver, uri)?.let { notify(it) } ?: refresh()
            } finally {
                _state.update { it.copy(uploading = false) }
            }
        }
    }

    fun retry(doc: Document) {
        if (!canRetry(doc)) return
        viewModelScope.launch {
            _state.update { it.copy(uploading = true) }
            try {
                retryDocument(api, doc)?.let { notify(it) } ?: refresh()
            } finally {
                _state.update { it.copy(uploading = false) }
            }
        }
    }

    fun delete(id: String) {
        viewModelScope.launch {
            val before = _state.value.items
            _state.update { s -> s.copy(items = s.items.filterNot { it.id == id }) }
            try {
                api.deleteDocument(id)
            } catch (e: ApiException) {
                _state.update { it.copy(items = before) }
                notify("Could not delete the document.")
            }
        }
    }

    fun messageShown() = _state.update { it.copy(message = null) }

    private fun notify(text: String) = _state.update { it.copy(message = text) }
}
