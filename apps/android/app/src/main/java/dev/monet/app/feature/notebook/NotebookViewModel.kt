package dev.monet.app.feature.notebook

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.monet.app.core.data.NotebookEntry
import dev.monet.app.core.data.NotebookRepository
import dev.monet.app.core.data.dueEntries
import dev.monet.app.core.data.searchNotebook
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Cards in one review session; the same cap the review screen uses. */
const val REVIEW_LIMIT = 10

data class NotebookUiState(
    val loaded: Boolean = false,
    val query: String = "",
    /** Entries matching [query], newest first. */
    val items: List<NotebookEntry> = emptyList(),
    /** Everything saved, regardless of the search. */
    val total: Int = 0,
    /** Cards a review would show today (capped at [REVIEW_LIMIT]). */
    val dueToday: Int = 0,
    /** The entry waiting for the user to confirm its deletion. */
    val pendingDelete: NotebookEntry? = null,
)

/** "Due now", "Due in 3 days": a short label for the list. */
fun dueLabel(entry: NotebookEntry, now: Long): String {
    val ms = entry.dueAt - now
    if (ms <= 0) return "Due now"
    val hours = (ms + HOUR_MS - 1) / HOUR_MS
    if (hours < 24) return if (hours <= 1) "Due in 1 hour" else "Due in $hours hours"
    val days = (ms + DAY_MS - 1) / DAY_MS
    return if (days == 1L) "Due tomorrow" else "Due in $days days"
}

private const val HOUR_MS = 60L * 60 * 1000
private const val DAY_MS = 24 * HOUR_MS

class NotebookViewModel(
    private val notebook: NotebookRepository,
    private val clock: () -> Long = System::currentTimeMillis,
) : ViewModel() {
    private val query = MutableStateFlow("")
    private val pending = MutableStateFlow<NotebookEntry?>(null)
    private val loaded = MutableStateFlow(false)

    val state: StateFlow<NotebookUiState> = combine(notebook.entries, query, pending, loaded) { entries, q, p, l ->
        NotebookUiState(
            loaded = l,
            query = q,
            items = searchNotebook(entries, q),
            total = entries.size,
            dueToday = dueEntries(entries, clock(), REVIEW_LIMIT).size,
            pendingDelete = p,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, NotebookUiState())

    init {
        viewModelScope.launch {
            notebook.load()
            loaded.value = true
        }
    }

    fun onQuery(value: String) {
        query.value = value.take(200)
    }

    fun requestDelete(entry: NotebookEntry) {
        pending.value = entry
    }

    fun cancelDelete() {
        pending.value = null
    }

    /** Removes the entry the user confirmed; does nothing if no deletion is pending. */
    fun confirmDelete() {
        val entry = pending.value ?: return
        pending.update { null }
        viewModelScope.launch { notebook.remove(entry.id) }
    }
}
