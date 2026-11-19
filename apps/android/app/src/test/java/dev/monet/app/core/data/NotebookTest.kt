package dev.monet.app.core.data

import dev.monet.app.core.network.AnnotateRequest
import dev.monet.app.core.network.Annotation
import dev.monet.app.core.network.FakeMonetApi
import dev.monet.app.core.network.Lang
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class NotebookTest {
    @get:Rule val tmp = TemporaryFolder()

    private val now = 1_000_000_000L
    private val day = 24L * 60 * 60 * 1000

    private val a: Annotation = runBlocking {
        FakeMonetApi(latencyMs = 0)
            .annotate(AnnotateRequest(text = "他这样做简直是画蛇添足。", targetLang = Lang.EN))
    }

    private fun newEntry() = NotebookEntry(id = "x", savedAt = 0, annotation = a, dueAt = 0)

    @Test
    fun `good on a new entry schedules it tomorrow`() {
        val result = review(newEntry(), Grade.GOOD, now)
        assertEquals(1, result.intervalDays)
        assertEquals(now + day, result.dueAt)
        assertEquals(2.5, result.ease, 1e-9)
        assertEquals(1, result.reviews)
        assertEquals(0, result.lapses)
    }

    @Test
    fun `easy on a new entry schedules it in three days and raises ease`() {
        val result = review(newEntry(), Grade.EASY, now)
        assertEquals(3, result.intervalDays)
        assertEquals(2.65, result.ease, 1e-9)
    }

    @Test
    fun `again brings the card back in ten minutes and never drops ease below the floor`() {
        val result = review(newEntry(), Grade.AGAIN, now)
        assertEquals(now + 10 * 60 * 1000, result.dueAt)
        assertEquals(0, result.intervalDays)
        assertEquals(2.3, result.ease, 1e-9)
        assertEquals(1, result.lapses)

        var repeated = newEntry()
        repeat(8) { repeated = review(repeated, Grade.AGAIN, now) }
        assertTrue(repeated.ease >= 1.3)
        assertEquals(1.3, repeated.ease, 1e-9)
    }

    @Test
    fun `three good reviews grow the interval one three eight`() {
        val first = review(newEntry(), Grade.GOOD, now)
        val second = review(first, Grade.GOOD, now)
        val third = review(second, Grade.GOOD, now)
        assertEquals(listOf(1, 3, 8), listOf(first.intervalDays, second.intervalDays, third.intervalDays))
    }

    @Test
    fun `due entries come most overdue first and future cards stay out`() {
        val entries = listOf(
            newEntry().copy(id = "a", dueAt = now - 3),
            newEntry().copy(id = "b", dueAt = now - 1),
            newEntry().copy(id = "c", dueAt = now),
            newEntry().copy(id = "d", dueAt = now + 1),
        )

        val two = dueEntries(entries, now, limit = 2)
        assertEquals(listOf("a", "b"), two.map { it.id })
        assertFalse(two.any { it.id == "d" })

        val all = dueEntries(entries, now, limit = 10)
        assertEquals(listOf("a", "b", "c"), all.map { it.id })
    }

    @Test
    fun `search matches text translation and expressions ignoring case`() {
        val entry = newEntry().copy(
            annotation = a.copy(
                text = "Hello World",
                translation = "Greeting",
                expressions = listOf(
                    a.expressions[0].copy(text = "Zebra Crossing", meaning = "pedestrian"),
                ),
            ),
        )
        val entries = listOf(entry)

        listOf("hello", "GREET", "zebra", "PEDESTRIAN").forEach { query ->
            assertEquals(query, listOf(entry), searchNotebook(entries, query))
        }
        assertEquals(entries, searchNotebook(entries, "  "))
        assertEquals(emptyList<NotebookEntry>(), searchNotebook(entries, "qqq"))
    }

    @Test
    fun `repository persists saves grades and removes across instances`() = runBlocking {
        val file = File(tmp.root, "notebook.json")
        val clock = { now }
        val repo = NotebookRepository(file, clock)

        val id = repo.save(a).id
        repo.save(a)
        assertEquals(1, repo.entries.value.size)
        assertEquals(id, repo.entries.value.single().id)

        val second = NotebookRepository(file, clock)
        second.load()
        assertEquals(repo.entries.value, second.entries.value)

        second.grade(id, Grade.GOOD)
        val third = NotebookRepository(file, clock)
        third.load()
        assertEquals(second.entries.value, third.entries.value)
        assertEquals(1, third.entries.value.single().reviews)

        third.remove(id)
        assertTrue(third.entries.value.isEmpty())
        val fourth = NotebookRepository(file, clock)
        fourth.load()
        assertTrue(fourth.entries.value.isEmpty())
    }

    @Test
    fun `a corrupt notebook file is set aside and saving works afterwards`() = runBlocking {
        val file = File(tmp.root, "notebook.json")
        file.writeText("not json")
        val repo = NotebookRepository(file, { now })

        repo.load()
        assertTrue(repo.entries.value.isEmpty())
        val corrupt = File(tmp.root, "notebook.json.corrupt")
        assertTrue(corrupt.isFile)
        assertFalse(file.isFile)

        repo.save(a)
        assertEquals(1, repo.entries.value.size)
    }
}
