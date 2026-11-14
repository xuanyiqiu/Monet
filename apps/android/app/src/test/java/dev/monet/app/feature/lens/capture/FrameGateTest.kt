package dev.monet.app.feature.lens.capture

import dev.monet.app.feature.lens.capture.FrameGate.Event
import org.junit.Assert.assertEquals
import org.junit.Test

class FrameGateTest {
    private val cells = FrameGate.COLS * FrameGate.ROWS

    /** A frame whose first [changedCells] cells are bright and the rest dark. */
    private fun frame(changedCells: Int = 0, level: Int = 200) =
        ByteArray(cells) { if (it < changedCells) level.toByte() else 10 }

    private fun gate() = FrameGate(changedFraction = 0.05f, motionFraction = 0.01f, settleMs = 300, suppressMs = 450, maxMovingMs = 4_000)

    @Test
    fun `a static screen never triggers`() {
        val g = gate()
        assertEquals(Event.NONE, g.onFrame(frame(), 0))
        assertEquals(Event.NONE, g.onFrame(frame(), 100))
        assertEquals(Event.NONE, g.onTick(1_000))
    }

    @Test
    fun `small changes such as a clock or a cursor are ignored`() {
        val g = gate()
        g.onFrame(frame(), 0)
        assertEquals(Event.NONE, g.onFrame(frame(changedCells = cells / 100), 100))
        assertEquals(Event.NONE, g.onTick(2_000))
    }

    @Test
    fun `a scroll reports moving once and settled after the quiet period`() {
        val g = gate()
        g.onFrame(frame(), 0)
        assertEquals(Event.MOVING, g.onFrame(frame(cells / 2), 100))
        assertEquals(Event.NONE, g.onFrame(frame(cells / 3), 150))
        assertEquals(Event.NONE, g.onTick(300))
        assertEquals(Event.SETTLED, g.onTick(460))
        assertEquals(Event.NONE, g.onTick(900))
    }

    @Test
    fun `motion keeps postponing the settle`() {
        val g = gate()
        g.onFrame(frame(), 0)
        g.onFrame(frame(cells / 2), 100)
        g.onFrame(frame(cells / 3), 350)
        assertEquals(Event.NONE, g.onTick(500))
        assertEquals(Event.SETTLED, g.onTick(660))
    }

    @Test
    fun `identical frames while moving do not postpone the settle`() {
        val g = gate()
        g.onFrame(frame(), 0)
        g.onFrame(frame(cells / 2), 100)
        g.onFrame(frame(cells / 2), 350)
        assertEquals(Event.SETTLED, g.onTick(410))
    }

    @Test
    fun `own overlay changes are suppressed and become the new baseline`() {
        val g = gate()
        g.onFrame(frame(), 0)
        g.ownChange(1_000)
        // Our chips appear: a big change, but inside the suppression window.
        assertEquals(Event.NONE, g.onFrame(frame(cells / 2), 1_100))
        // Same picture after the window: it is the baseline now, so nothing happens.
        assertEquals(Event.NONE, g.onFrame(frame(cells / 2), 1_600))
        // The page then scrolls under the chips.
        assertEquals(Event.MOVING, g.onFrame(frame(cells / 2, level = 90), 1_700))
    }

    @Test
    fun `a scroll that starts right after the suppression window is still caught`() {
        val g = gate()
        g.ownChange(0)
        g.onFrame(frame(cells / 2), 100)
        assertEquals(Event.MOVING, g.onFrame(frame(), 500))
    }

    @Test
    fun `a page that never stops changing is reported as restless`() {
        val g = gate()
        g.onFrame(frame(), 0)
        g.onFrame(frame(cells / 2), 100)
        var t = 200L
        var result = Event.NONE
        while (t <= 4_300 && result == Event.NONE) {
            g.onFrame(frame(cells / 2, level = if (t % 400 == 0L) 90 else 200), t)
            result = g.onTick(t + 50)
            t += 200
        }
        assertEquals(Event.RESTLESS, result)
    }

    @Test
    fun `a different frame size counts as a full change`() {
        val g = gate()
        g.onFrame(frame(), 0)
        assertEquals(Event.MOVING, g.onFrame(ByteArray(cells / 2) { 10 }, 100))
    }

    @Test
    fun `after settling the next frame is compared with the settled screen`() {
        val g = gate()
        g.onFrame(frame(), 0)
        g.onFrame(frame(cells / 2), 100)
        assertEquals(Event.SETTLED, g.onTick(500))
        assertEquals(Event.NONE, g.onFrame(frame(cells / 2), 600))
        assertEquals(Event.MOVING, g.onFrame(frame(), 700))
    }
}
