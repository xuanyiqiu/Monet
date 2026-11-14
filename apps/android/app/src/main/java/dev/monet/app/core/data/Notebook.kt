package dev.monet.app.core.data

import dev.monet.app.core.network.Annotation
import dev.monet.app.core.network.MonetJson
import java.io.File
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

/** A saved line with everything the Detail sheet shows, plus its spaced-repetition state. */
@Serializable
data class NotebookEntry(
    val id: String,
    val savedAt: Long,
    val annotation: Annotation,
    /** When the entry is next due for review (epoch millis). New entries are due at once. */
    val dueAt: Long,
    val intervalDays: Int = 0,
    val ease: Double = START_EASE,
    val reviews: Int = 0,
    val lapses: Int = 0,
)

enum class Grade { AGAIN, GOOD, EASY }

const val START_EASE = 2.5
private const val MIN_EASE = 1.3
private const val DAY_MS = 24L * 60 * 60 * 1000

/** One entry per text and target language: saving the same line twice keeps a single card. */
fun notebookId(annotation: Annotation): String = "${annotation.targetLang.name}:${annotation.id}"

/**
 * SM-2 with three answers. "Again" brings the card back in ten minutes and makes it harder;
 * "Good" and "Easy" push it out by growing intervals (1 day, 3 days, then interval x ease).
 */
fun review(entry: NotebookEntry, grade: Grade, now: Long): NotebookEntry = when (grade) {
    Grade.AGAIN -> entry.copy(
        dueAt = now + 10 * 60 * 1000,
        intervalDays = 0,
        ease = (entry.ease - 0.2).coerceAtLeast(MIN_EASE),
        reviews = entry.reviews + 1,
        lapses = entry.lapses + 1,
    )
    Grade.GOOD, Grade.EASY -> {
        val easy = grade == Grade.EASY
        val ease = if (easy) entry.ease + 0.15 else entry.ease
        val interval = when {
            entry.intervalDays == 0 -> if (easy) 3 else 1
            entry.intervalDays == 1 -> if (easy) 6 else 3
            else -> (entry.intervalDays * ease * (if (easy) 1.3 else 1.0)).roundToInt().coerceAtLeast(entry.intervalDays + 1)
        }
        entry.copy(dueAt = now + interval * DAY_MS, intervalDays = interval, ease = ease, reviews = entry.reviews + 1)
    }
}

/** Today's review: the most overdue cards first, capped so a session stays short (5-10 cards). */
fun dueEntries(entries: List<NotebookEntry>, now: Long, limit: Int = 10): List<NotebookEntry> =
    entries.filter { it.dueAt <= now }.sortedBy { it.dueAt }.take(limit)

/** Case-insensitive search over the source text, its translation and the expressions it contains. */
fun searchNotebook(entries: List<NotebookEntry>, query: String): List<NotebookEntry> {
    val q = query.trim().lowercase()
    if (q.isEmpty()) return entries
    return entries.filter { entry ->
        val a = entry.annotation
        a.text.lowercase().contains(q) ||
            a.translation.lowercase().contains(q) ||
            a.expressions.any { it.text.lowercase().contains(q) || it.meaning.lowercase().contains(q) }
    }
}

/**
 * The notebook, kept in one JSON file. It is read once and held in memory; every change rewrites
 * the file through a temp file so a crash mid-write cannot lose what was saved before.
 */
class NotebookRepository(
    private val file: File,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val mutex = Mutex()
    private var loaded = false
    private val _entries = MutableStateFlow<List<NotebookEntry>>(emptyList())

    /** Newest first. Empty until [load] has run. */
    val entries: StateFlow<List<NotebookEntry>> = _entries.asStateFlow()

    suspend fun load() = mutex.withLock { ensureLoaded() }

    private suspend fun ensureLoaded() {
        if (loaded) return
        _entries.value = withContext(Dispatchers.IO) {
            if (!file.isFile) return@withContext emptyList()
            try {
                MonetJson.decodeFromString(ListSerializer(NotebookEntry.serializer()), file.readText())
            } catch (e: Exception) {
                // Keep the unreadable file for a manual rescue instead of overwriting it silently.
                file.renameTo(File(file.parentFile, file.name + ".corrupt"))
                emptyList()
            }
        }.sortedByDescending { it.savedAt }
        loaded = true
    }

    suspend fun save(annotation: Annotation): NotebookEntry = mutex.withLock {
        ensureLoaded()
        val id = notebookId(annotation)
        _entries.value.firstOrNull { it.id == id }?.let { return@withLock it }
        val now = clock()
        val entry = NotebookEntry(id = id, savedAt = now, annotation = annotation, dueAt = now)
        persist(listOf(entry) + _entries.value)
        entry
    }

    suspend fun remove(id: String) = mutex.withLock {
        ensureLoaded()
        if (_entries.value.any { it.id == id }) persist(_entries.value.filterNot { it.id == id })
    }

    suspend fun grade(id: String, grade: Grade) = mutex.withLock {
        ensureLoaded()
        val now = clock()
        persist(_entries.value.map { if (it.id == id) review(it, grade, now) else it })
    }

    private suspend fun persist(entries: List<NotebookEntry>) {
        withContext(Dispatchers.IO) {
            file.parentFile?.mkdirs()
            val temp = File(file.parentFile, file.name + ".tmp")
            temp.writeText(MonetJson.encodeToString(ListSerializer(NotebookEntry.serializer()), entries))
            if (!temp.renameTo(file)) {
                file.delete()
                check(temp.renameTo(file)) { "Could not replace the notebook file" }
            }
        }
        _entries.value = entries
    }
}
