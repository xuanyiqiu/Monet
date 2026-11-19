package dev.monet.app.core.settings

import dev.monet.app.feature.lens.capture.LINES_ALL
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsOptionsTest {
    @Test
    fun `lines options are 1 2 3 5 10 and all`() {
        assertEquals(listOf(1, 2, 3, 5, 10, LINES_ALL), LINES_OPTIONS)
    }

    @Test
    fun `snapLines picks the nearest option and keeps all`() {
        assertEquals(3, snapLines(4))
        assertEquals(5, snapLines(6))
        assertEquals(10, snapLines(50))
        assertEquals(1, snapLines(-3))
        assertEquals(LINES_ALL, snapLines(LINES_ALL))
        assertEquals(LINES_ALL, snapLines(5_000))
    }

    @Test
    fun `lines label shows All for the whole screen`() {
        assertEquals("All", linesLabel(LINES_ALL))
        assertEquals("5", linesLabel(5))
    }

    @Test
    fun `stepping walks the options and stops at the ends`() {
        assertEquals(3, stepLinesOption(2, +1))
        assertEquals(5, stepLinesOption(3, +1))
        assertEquals(LINES_ALL, stepLinesOption(10, +1))
        assertEquals(LINES_ALL, stepLinesOption(LINES_ALL, +1))
        assertEquals(1, stepLinesOption(1, -1))
        assertEquals(10, stepLinesOption(LINES_ALL, -1))
    }

    @Test
    fun `opacity snaps and drives bubble and scrim alpha`() {
        assertEquals(60, snapOpacityPercent(55))
        assertEquals(100, snapOpacityPercent(999))
        assertEquals(0.4f, bubbleIdleAlpha(40), 0.001f)
        assertEquals(0.6f, bubbleIdleAlpha(DEFAULT_OPACITY_PERCENT), 0.001f)
        assertEquals(0.85f, readScrimAlpha(DEFAULT_OPACITY_PERCENT), 0.001f)
        assertTrue(readScrimAlpha(40) < readScrimAlpha(80))
        assertTrue(readScrimAlpha(100) <= 0.95f)
    }

    @Test
    fun `chip scales grow from small to large with normal at one`() {
        assertEquals(1f, ChipSize.NORMAL.scale, 0f)
        assertTrue(ChipSize.SMALL.scale < 1f)
        assertTrue(ChipSize.LARGE.scale > 1f)
    }

    @Test
    fun `speech rate follows the setting and slow stays much slower`() {
        TtsSpeed.values().forEach { speed ->
            assertEquals(speed.rate, speechRate(speed, slow = false), 0f)
            assertTrue(speechRate(speed, slow = true) <= speechRate(speed, slow = false) * 0.6f)
        }
        assertTrue(speechRate(TtsSpeed.FAST, false) > speechRate(TtsSpeed.NORMAL, false))
        assertTrue(speechRate(TtsSpeed.SLOW, false) < speechRate(TtsSpeed.NORMAL, false))
        assertEquals(1.0f, speechRate(TtsSpeed.NORMAL, false), 0f)
    }
}
