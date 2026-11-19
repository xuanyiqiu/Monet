package dev.monet.app.feature.notebook

import dev.monet.app.core.data.Grade
import dev.monet.app.core.data.NotebookEntry
import dev.monet.app.core.data.NotebookRepository
import dev.monet.app.core.network.AnnotateRequest
import dev.monet.app.core.network.Annotation
import dev.monet.app.core.network.FakeMonetApi
import dev.monet.app.core.network.Lang
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class NotebookViewModelTest {
    @get:Rule val tmp = TemporaryFolder()

    private val day = 24L * 60 * 60 * 1000
    private var now = 10 * day
    private lateinit var repo: NotebookRepository

    private val api = FakeMonetApi(latencyMs = 0)
    private fun annotate(text: String): Annotation = runBlocking { api.annotate(AnnotateRequest(text = text, targetLang = Lang.EN)) }

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        repo = NotebookRepository(File(tmp.root, "notebook.json")) { now }
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private suspend fun <T> StateFlow<T>.await(predicate: (T) -> Boolean): T = withTimeout(5_000) { first(predicate) }

    @Test
    fun `state lists saved entries newest first once loaded`() = runBlocking {
        repo.save(annotate("你好"))
        now += 1000
        repo.save(annotate("他这样做简直是画蛇添足。"))
        val vm = NotebookViewModel(repo) { now }
        val s = vm.state.await { it.loaded }
        assertEquals(2, s.total)
        assertEquals("他这样做简直是画蛇添足。", s.items.first().annotation.text)
    }

    @Test
    fun `search filters the list but keeps the total`() = runBlocking {
        repo.save(annotate("你好"))
        repo.save(annotate("他这样做简直是画蛇添足。"))
        val vm = NotebookViewModel(repo) { now }
        vm.state.await { it.loaded }
        vm.onQuery("你好")
        val s = vm.state.await { it.query == "你好" }
        assertEquals(1, s.items.size)
        assertEquals("你好", s.items.single().annotation.text)
        assertEquals(2, s.total)

        vm.onQuery("no such line anywhere")
        assertTrue(vm.state.await { it.query.startsWith("no such") }.items.isEmpty())
    }

    @Test
    fun `due count counts only cards due now and is capped at the review limit`() = runBlocking {
        repeat(12) { repo.save(annotate("你好$it")) }
        val vm = NotebookViewModel(repo) { now }
        assertEquals(REVIEW_LIMIT, vm.state.await { it.loaded }.dueToday)

        // Grading one good pushes it a day out, so one fewer is... still capped; grade them all.
        repo.entries.value.forEach { repo.grade(it.id, Grade.GOOD) }
        assertEquals(0, vm.state.await { it.dueToday == 0 }.dueToday)
    }

    @Test
    fun `delete needs a confirmation and cancel keeps the entry`() = runBlocking {
        repo.save(annotate("你好"))
        val vm = NotebookViewModel(repo) { now }
        val entry = vm.state.await { it.loaded }.items.single()

        vm.requestDelete(entry)
        assertEquals(entry, vm.state.await { it.pendingDelete != null }.pendingDelete)
        assertEquals(1, repo.entries.value.size)

        vm.cancelDelete()
        assertNull(vm.state.await { it.pendingDelete == null }.pendingDelete)
        assertEquals(1, repo.entries.value.size)
    }

    @Test
    fun `confirming removes the entry and clears the prompt`() = runBlocking {
        repo.save(annotate("你好"))
        val vm = NotebookViewModel(repo) { now }
        vm.requestDelete(vm.state.await { it.loaded }.items.single())
        vm.confirmDelete()
        val s = vm.state.await { it.total == 0 }
        assertNull(s.pendingDelete)
        assertTrue(repo.entries.value.isEmpty())
    }

    @Test
    fun `confirm with nothing pending does nothing`() = runBlocking {
        repo.save(annotate("你好"))
        val vm = NotebookViewModel(repo) { now }
        vm.state.await { it.loaded }
        vm.confirmDelete()
        assertEquals(1, repo.entries.value.size)
    }

    @Test
    fun `due label reads naturally`() {
        fun e(due: Long) = NotebookEntry("x", 0, annotate("你好"), due)
        assertEquals("Due now", dueLabel(e(now - 5), now))
        assertEquals("Due in 1 hour", dueLabel(e(now + 60_000), now))
        assertEquals("Due in 5 hours", dueLabel(e(now + 5 * 3_600_000L - 1), now))
        assertEquals("Due tomorrow", dueLabel(e(now + day), now))
        assertEquals("Due in 3 days", dueLabel(e(now + 3 * day), now))
    }

    // ---- Review view model ----

    @Test
    fun `review queues today's due cards and grading persists to the notebook`() = runBlocking {
        repo.save(annotate("你好"))
        repo.save(annotate("他这样做简直是画蛇添足。"))
        val vm = ReviewViewModel(repo, { now })
        val session = vm.state.await { it.session != null }.session!!
        assertEquals(2, session.total)

        val first = session.current!!
        vm.reveal()
        vm.grade(Grade.GOOD)
        val after = vm.state.await { it.session?.index == 1 }.session!!
        assertEquals(listOf(Grade.GOOD), after.grades)
        val saved = withTimeout(5_000) { repo.entries.first { list -> list.first { it.id == first.id }.reviews == 1 } }
        assertTrue(saved.first { it.id == first.id }.dueAt > now)
    }

    @Test
    fun `review ignores a grade before the meaning is revealed`() = runBlocking {
        repo.save(annotate("你好"))
        val vm = ReviewViewModel(repo, { now })
        vm.state.await { it.session != null }
        vm.grade(Grade.EASY)
        assertEquals(0, vm.state.value.session!!.index)
        assertEquals(0, repo.entries.value.single().reviews)
    }

    @Test
    fun `review with nothing due starts with an empty finished session`() = runBlocking {
        repo.save(annotate("你好"))
        repo.grade(repo.entries.value.single().id, Grade.GOOD)
        val vm = ReviewViewModel(repo, { now })
        val s = vm.state.await { it.session != null }.session!!
        assertEquals(0, s.total)
        assertTrue(s.done)
    }
}
