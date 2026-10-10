package dev.monet.app.feature.lens.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadingTest {
    private fun unit(i: Int) = ReadingUnit("u$i", "text $i", SelRect(0, i * 100, 500, i * 100 + 60), lineCount = 1)
    private fun session(count: Int, step: Int, index: Int = 0) =
        ReadingSession((0 until count).map(::unit), step, index)

    @Test
    fun `active window covers linesPerStep units and the rest are read or upcoming`() {
        val s = session(count = 7, step = 3, index = 3)
        assertEquals(listOf("u3", "u4", "u5"), s.active.map { it.id })
        assertEquals(listOf("u0", "u1", "u2"), s.read.map { it.id })
        assertEquals(listOf("u6"), s.upcoming().map { it.id })
    }

    @Test
    fun `next and prev walk the screen in steps and stop at the ends`() {
        var s = session(count = 5, step = 2)
        assertFalse(s.hasPrev)
        s = s.next()
        assertEquals(2, s.index)
        s = s.next()
        assertEquals(4, s.index)
        assertEquals(listOf("u4"), s.active.map { it.id })
        assertFalse(s.hasNext)
        assertEquals(s, s.next())
        s = s.prev()
        assertEquals(2, s.index)
        s = s.prev().prev()
        assertEquals(0, s.index)
    }

    @Test
    fun `prev never goes below zero when the step does not divide the index`() {
        val s = session(count = 6, step = 4, index = 2)
        assertEquals(0, s.prev().index)
    }

    @Test
    fun `changing the step size keeps the band position`() {
        val s = session(count = 8, step = 2, index = 4).withLinesPerStep(3)
        assertEquals(4, s.index)
        assertEquals(listOf("u4", "u5", "u6"), s.active.map { it.id })
    }

    @Test
    fun `empty session is safe`() {
        val s = session(count = 0, step = 2)
        assertTrue(s.active.isEmpty())
        assertFalse(s.hasNext)
        assertEquals("Nothing to read", s.label)
    }

    @Test
    fun `labels show a range or a single position`() {
        assertEquals("1–2 of 5", session(5, 2).label)
        assertEquals("5 of 5", session(5, 2, index = 4).label)
    }

    @Test
    fun `units are built from blocks with bounds and line counts`() {
        val block = TextBlock(listOf(ScreenLine("a", 10, 20, 110, 60), ScreenLine("b", 10, 66, 90, 106)))
        val u = buildReadingUnits(listOf(block)).single()
        assertEquals(SelRect(10, 20, 110, 106), u.bounds)
        assertEquals(2, u.lineCount)
        assertEquals("a b", u.text)
    }

    @Test
    fun `contrast ratio matches known WCAG values`() {
        assertEquals(21.0, contrastRatio(0xFF000000.toInt(), 0xFFFFFFFF.toInt()), 0.01)
        assertEquals(1.0, contrastRatio(0xFF777777.toInt(), 0xFF777777.toInt()), 0.001)
    }

    @Test
    fun `text color is whichever of ink and paper contrasts more and reaches 4_5`() {
        val lightBg = 0xFFFFFFFF.toInt()
        val darkBg = 0xFF111111.toInt()
        assertEquals(INK_LIGHT_THEME, pickTextColor(lightBg))
        assertEquals(PAPER_LIGHT_THEME, pickTextColor(darkBg))
        assertTrue(contrastRatio(lightBg, pickTextColor(lightBg)) >= 4.5)
        assertTrue(contrastRatio(darkBg, pickTextColor(darkBg)) >= 4.5)
    }

    @Test
    fun `median ignores outlier pixels`() {
        val white = 0xFFFFFFFF.toInt()
        val red = 0xFFFF0000.toInt()
        val pixels = intArrayOf(white, white, white, white, red)
        assertEquals(white, medianArgb(pixels))
        assertEquals(PAPER_LIGHT_THEME, medianArgb(intArrayOf()))
    }

    @Test
    fun `border sampling stays inside the frame and reads only edge pixels`() {
        val seen = mutableSetOf<Pair<Int, Int>>()
        borderPixels(100, 100, SelRect(0, 0, 99, 99), pad = 5, step = 10) { x, y ->
            assertTrue(x in 0..99 && y in 0..99)
            seen += x to y
            0
        }
        assertTrue(seen.isNotEmpty())
        assertTrue(seen.all { (x, y) -> x == 0 || x == 99 || y == 0 || y == 99 })
    }
}
