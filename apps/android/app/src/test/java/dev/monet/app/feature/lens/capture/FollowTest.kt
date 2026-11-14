package dev.monet.app.feature.lens.capture

import dev.monet.app.feature.lens.pillTop
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FollowTest {
    private fun unit(i: Int, text: String = "line $i") = ReadingUnit("u$i", text, SelRect(0, i * 100, 500, i * 100 + 60), lineCount = 1)
    private fun line(text: String, left: Int, top: Int, right: Int, bottom: Int) = ScreenLine(text, left, top, right, bottom)

    @Test
    fun `match key survives spacing, case and punctuation jitter`() {
        assertEquals(matchKey("Hello, World!"), matchKey("hello world"))
        assertEquals(matchKey("他这样做，简直是画蛇添足。"), matchKey("他这样做 简直是画蛇添足"))
        assertEquals("", matchKey("…—!"))
    }

    @Test
    fun `a fresh page starts at the top`() {
        assertEquals(0, reanchorIndex(listOf(unit(0), unit(1)), ReadHistory()))
    }

    @Test
    fun `after a scroll the band lands on the first unit not read yet`() {
        val history = ReadHistory()
        // The user read lines 0-1 and was on 2-3 when the page scrolled by two lines.
        history.markFrom(ReadingSession((0..5).map { unit(it) }, linesPerStep = 2, index = 2))
        val afterScroll = (2..7).map { unit(it) }
        assertEquals(0, reanchorIndex(afterScroll, history))
        // Scrolling by one line instead keeps line 1 (read) above the band.
        assertEquals(1, reanchorIndex((1..6).map { unit(it) }, history))
    }

    @Test
    fun `when the band had reached the bottom its lines count as read too`() {
        val history = ReadHistory()
        history.markFrom(ReadingSession((0..3).map { unit(it) }, linesPerStep = 2, index = 2))
        // Lines 2-3 were the last step; after scrolling on, reading resumes at line 4.
        assertEquals(2, reanchorIndex((2..6).map { unit(it) }, history))
    }

    @Test
    fun `a page with nothing new leaves the band past the end`() {
        val history = ReadHistory()
        history.mark((0..2).map { unit(it) })
        val units = (0..2).map { unit(it) }
        val index = reanchorIndex(units, history)
        assertEquals(units.size, index)
        val session = ReadingSession(units, linesPerStep = 2, index = index)
        assertTrue(session.active.isEmpty())
        assertEquals("All read", session.label)
        assertTrue(session.hasPrev)
        assertFalse(session.hasNext)
    }

    @Test
    fun `history forgets its oldest entries`() {
        val history = ReadHistory(capacity = 3)
        history.mark((0..4).map { unit(it) })
        assertFalse(unit(0) in history)
        assertFalse(unit(1) in history)
        assertTrue(unit(2) in history)
        assertTrue(unit(4) in history)
        history.clear()
        assertFalse(unit(4) in history)
    }

    @Test
    fun `all lines per step makes a single step of the whole screen`() {
        val session = ReadingSession((0..11).map { unit(it) }, linesPerStep = LINES_ALL)
        assertEquals(12, session.active.size)
        assertFalse(session.hasNext)
        assertTrue(session.upcoming().isEmpty())
        assertEquals("1–12 of 12", session.label)
    }

    @Test
    fun `text under the lens controls is dropped`() {
        val bubble = SelRect(900, 800, 1080, 980)
        val lines = listOf(
            line("page text", 40, 100, 600, 150),
            line("Auto Select Off", 920, 850, 1060, 900),
            line("touching edge", 880, 780, 905, 810),
            line("just outside", 40, 990, 600, 1040),
        )
        assertEquals(listOf("page text", "just outside"), excludeRegions(lines, listOf(bubble)).map { it.text })
        assertEquals(lines, excludeRegions(lines, emptyList()))
    }

    @Test
    fun `an open pill is lifted only as much as it needs to stay on screen`() {
        assertEquals(500, pillTop(collapsedY = 500, pillHeight = 900, bottomLimit = 2300))
        assertEquals(1400, pillTop(collapsedY = 2000, pillHeight = 900, bottomLimit = 2300))
        assertEquals(0, pillTop(collapsedY = 100, pillHeight = 900, bottomLimit = 600))
    }

    @Test
    fun `screen furniture is not worth reading`() {
        assertFalse(isReadable("10.0.2.2:8765/long.html"))
        assertFalse(isReadable("https://example.com/a/b?c=1"))
        assertFalse(isReadable("example.com"))
        assertFalse(isReadable("1"))
        assertFalse(isReadable("5:13"))
        assertFalse(isReadable("41%"))
        assertFalse(isReadable("A"))
    }

    @Test
    fun `real text is readable, including short words and sentences with dots`() {
        assertTrue(isReadable("他这样做简直是画蛇添足。"))
        assertTrue(isReadable("OK"))
        assertTrue(isReadable("Network & internet"))
        assertTrue(isReadable("Visit example.com today"))
        assertTrue(isReadable("3.今天天气不错"))
        assertTrue(isReadable("Sr.Gómez"))
    }

    @Test
    fun `chip text follows the source line height within readable bounds`() {
        // 55px lines at density 2.625: 55 * 0.55 / 2.625 = 11.5sp, raised to the 12sp minimum.
        assertEquals(12f * 2.625f, chipTextSizePx(55f, 2.625f, 1f), 0.01f)
        assertEquals(20f * 2f, chipTextSizePx(72.8f, 2f, 1f), 0.1f)
        assertEquals(28f * 2f, chipTextSizePx(400f, 2f, 1f), 0.01f)
        // The user's chip size preference scales it, still inside the bounds.
        assertEquals(20f * 2f * 1.25f, chipTextSizePx(72.8f, 2f, 1f, textScale = 1.25f), 0.1f)
    }

    @Test
    fun `a chip is as wide as its source unless the translation needs more room`() {
        assertEquals(600, chipWidthPx(sourceWidthPx = 600, textWidthPx = 300f, lineCount = 1, paddingPx = 20, maxWidthPx = 1000))
        assertEquals(884, chipWidthPx(sourceWidthPx = 600, textWidthPx = 800f, lineCount = 1, paddingPx = 20, maxWidthPx = 1000))
        // Never past the screen edge: the text wraps instead.
        assertEquals(1000, chipWidthPx(sourceWidthPx = 600, textWidthPx = 2000f, lineCount = 1, paddingPx = 20, maxWidthPx = 1000))
        // A three-line block has three lines to spread the translation over.
        assertEquals(600, chipWidthPx(sourceWidthPx = 600, textWidthPx = 1500f, lineCount = 3, paddingPx = 20, maxWidthPx = 1000))
        // A source already wider than the room to the edge keeps its own width.
        assertEquals(900, chipWidthPx(sourceWidthPx = 900, textWidthPx = 2000f, lineCount = 1, paddingPx = 20, maxWidthPx = 700))
    }
}
