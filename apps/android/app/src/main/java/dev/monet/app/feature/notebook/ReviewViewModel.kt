package dev.monet.app.feature.notebook

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.monet.app.core.data.Grade
import dev.monet.app.core.data.NotebookRepository
import dev.monet.app.core.data.dueEntries
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** [session] is null until the notebook has loaded; an empty queue means nothing is due. */
data class ReviewUiState(val session: ReviewSession? = null)

class ReviewViewModel(
    private val notebook: NotebookRepository,
    private val clock: () -> Long = System::currentTimeMillis,
    private val limit: Int = REVIEW_LIMIT,
) : ViewModel() {
    private val _state = MutableStateFlow(ReviewUiState())
    val state: StateFlow<ReviewUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            notebook.load()
            _state.value = ReviewUiState(ReviewSession(dueEntries(notebook.entries.value, clock(), limit)))
        }
    }

    fun reveal() {
        _state.value = ReviewUiState(_state.value.session?.reveal())
    }

    fun grade(grade: Grade) {
        val session = _state.value.session ?: return
        val card = session.current ?: return
        val next = session.grade(grade)
        if (next === session) return
        _state.value = ReviewUiState(next)
        viewModelScope.launch { notebook.grade(card.id, grade) }
    }
}
