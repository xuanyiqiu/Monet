package dev.monet.app.feature.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.monet.app.core.network.AnnotateRequest
import dev.monet.app.core.network.Annotation
import dev.monet.app.core.network.ApiException
import dev.monet.app.core.network.MonetApi
import dev.monet.app.core.settings.PinyinStyle
import dev.monet.app.core.settings.SettingsRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

sealed interface DetailUiState {
    data object Loading : DetailUiState

    data class Ready(val annotation: Annotation, val pinyinStyle: PinyinStyle) : DetailUiState {
        /** The model was unavailable: only dictionary data (tokens, expressions) and [Annotation.note] exist. */
        val translationMissing: Boolean get() = annotation.translation.isEmpty()
    }

    data class Failed(val offline: Boolean, val message: String) : DetailUiState
}

class DetailViewModel(
    private val api: MonetApi,
    private val settings: SettingsRepository,
    private val text: String,
) : ViewModel() {

    private val _state = MutableStateFlow<DetailUiState>(DetailUiState.Loading)
    val state: StateFlow<DetailUiState> = _state.asStateFlow()

    private var job: Job? = null

    init {
        load()
    }

    fun load() {
        job?.cancel()
        job = viewModelScope.launch {
            _state.value = DetailUiState.Loading
            val s = settings.settings.first()
            _state.value = try {
                DetailUiState.Ready(api.annotate(AnnotateRequest(text = text, targetLang = s.targetLang)), s.pinyinStyle)
            } catch (e: ApiException) {
                DetailUiState.Failed(offline = e.isOffline, message = e.problem?.detail ?: e.problem?.title ?: "Something went wrong")
            }
        }
    }
}
