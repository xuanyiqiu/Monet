package dev.monet.app.feature.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.monet.app.core.data.NotebookRepository
import dev.monet.app.core.data.notebookId
import dev.monet.app.core.network.AnnotateRequest
import dev.monet.app.core.network.FeedbackRequest
import dev.monet.app.core.network.Annotation
import dev.monet.app.core.network.ApiException
import dev.monet.app.core.network.MonetApi
import dev.monet.app.core.settings.PinyinStyle
import dev.monet.app.core.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface DetailUiState {
    data object Loading : DetailUiState

    data class Ready(val annotation: Annotation, val pinyinStyle: PinyinStyle) : DetailUiState {
        /** The model was unavailable: only dictionary data (tokens, expressions) and [Annotation.note] exist. */
        val translationMissing: Boolean get() = annotation.translation.isEmpty()
    }

    data class Failed(val offline: Boolean, val message: String) : DetailUiState
}

/** Where the "report a bad translation" action stands for the text on screen. */
sealed interface ReportState {
    data object Idle : ReportState
    data object Sending : ReportState
    data object Sent : ReportState
    data class Failed(val message: String) : ReportState
}

/** What the sheet shows besides the explanation itself: notebook membership and the report outcome. */
data class DetailExtras(val saved: Boolean = false, val report: ReportState = ReportState.Idle)

/**
 * Loads the full explanation of one text. It is plain (no Android ViewModel) so the same logic
 * serves the in-app sheet and the sheet the Lens draws over other apps.
 */
class DetailController(
    private val scope: CoroutineScope,
    private val api: MonetApi,
    private val settings: SettingsRepository,
    val text: String,
    private val notebook: NotebookRepository? = null,
    /** A stored explanation (from the notebook): shown at once, with no network call. */
    provided: Annotation? = null,
) {
    private val _state = MutableStateFlow<DetailUiState>(DetailUiState.Loading)
    val state: StateFlow<DetailUiState> = _state.asStateFlow()

    private val _report = MutableStateFlow<ReportState>(ReportState.Idle)

    /** Saved flag (from the notebook, so it is right even if the line was saved earlier) and report state. */
    val extras: StateFlow<DetailExtras> = combine(
        _state,
        notebook?.entries ?: flowOf(emptyList()),
        _report,
    ) { state, entries, report ->
        val ready = state as? DetailUiState.Ready
        DetailExtras(saved = ready != null && entries.any { it.id == notebookId(ready.annotation) }, report = report)
    }.stateIn(scope, SharingStarted.Eagerly, DetailExtras())

    private var job: Job? = null
    private var pendingProvided = provided

    init {
        notebook?.let { scope.launch { it.load() } }
        load()
    }

    /** Adds the explanation on screen to the notebook; saving twice keeps one card. */
    fun save() {
        val ready = _state.value as? DetailUiState.Ready ?: return
        val repo = notebook ?: return
        scope.launch { repo.save(ready.annotation) }
    }

    /** Sends the current translation as a bad one. Ignored while sending and once sent for this text. */
    fun report() {
        val ready = _state.value as? DetailUiState.Ready ?: return
        if (ready.translationMissing) return
        if (_report.value == ReportState.Sending || _report.value == ReportState.Sent) return
        _report.value = ReportState.Sending
        scope.launch {
            _report.update {
                try {
                    api.sendFeedback(FeedbackRequest(text = text, translation = ready.annotation.translation, targetLang = ready.annotation.targetLang))
                    ReportState.Sent
                } catch (e: ApiException) {
                    ReportState.Failed(if (e.isOffline) "Can't reach the server." else e.problem?.detail ?: e.problem?.title ?: "Something went wrong")
                }
            }
        }
    }

    fun load() {
        job?.cancel()
        job = scope.launch {
            _state.value = DetailUiState.Loading
            val s = settings.settings.first()
            pendingProvided?.let {
                pendingProvided = null
                _state.value = DetailUiState.Ready(it, s.pinyinStyle)
                return@launch
            }
            _state.value = try {
                DetailUiState.Ready(api.annotate(AnnotateRequest(text = text, sourceLang = s.sourceLang, targetLang = s.targetLang)), s.pinyinStyle)
            } catch (e: ApiException) {
                DetailUiState.Failed(offline = e.isOffline, message = e.problem?.detail ?: e.problem?.title ?: "Something went wrong")
            }
        }
    }

    fun cancel() {
        job?.cancel()
    }
}

class DetailViewModel(
    api: MonetApi,
    settings: SettingsRepository,
    text: String,
    notebook: NotebookRepository? = null,
    provided: Annotation? = null,
) : ViewModel() {
    private val controller = DetailController(viewModelScope, api, settings, text, notebook, provided)
    val state: StateFlow<DetailUiState> get() = controller.state
    val extras: StateFlow<DetailExtras> get() = controller.extras

    fun save() = controller.save()
    fun report() = controller.report()

    fun load() = controller.load()
}
