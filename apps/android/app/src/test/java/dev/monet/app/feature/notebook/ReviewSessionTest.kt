package dev.monet.app.feature.notebook

import dev.monet.app.core.data.Grade
import dev.monet.app.core.data.NotebookEntry
import dev.monet.app.core.network.AnnotateRequest
import dev.monet.app.core.network.FakeMonetApi
import dev.monet.app.core.network.Lang
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ReviewSessionTest {
    private val base = runBlocking { FakeMonetApi(latencyMs = 0).annotate(AnnotateRequest(text = "你好", targetLang = Lang.EN)) }
    private fun entry(id: String, due: Long = 0) = NotebookEntry(id = id, savedAt = 0, annotation = base, dueAt = due)
    private fun session(n: Int) = ReviewSession(List(n) { entry("e$it") })

    @Test
    fun `a new session starts on the first card, hidden`() {
        val s = session(3)
        assertEquals("e0", s.current?.id)
        assertEquals(1, s.position)
        assertEquals(3, s.total)
        assertFalse(s.revealed)
        assertFalse(s.done)
    }

    @Test
    fun `grading before the answer is shown is ignored`() {
        val s = session(2)
        assertSame(s, s.grade(Grade.GOOD))
    }

    @Test
    fun `reveal then grade moves to the next card hidden again`() {
        val s = session(2).reveal().grade(Grade.GOOD)
        assertEquals("e1", s.current?.id)
        assertEquals(2, s.position)
        assertFalse(s.revealed)
        assertEquals(listOf(Grade.GOOD), s.grades)
    }

    @Test
    fun `grading the last card finishes the session with a tally`() {
        val s = session(3)
            .reveal().grade(Grade.AGAIN)
            .reveal().grade(Grade.GOOD)
            .reveal().grade(Grade.EASY)
        assertTrue(s.done)
        assertNull(s.current)
        assertEquals(3, s.position)
        assertEquals(1, s.againCount)
        assertEquals(2, s.knownCount)
        assertEquals(0, s.remaining)
    }

    @Test
    fun `a finished or empty session ignores further input`() {
        val empty = ReviewSession(emptyList())
        assertTrue(empty.done)
        assertSame(empty, empty.reveal())
        assertSame(empty, empty.grade(Grade.GOOD))
    }
}
