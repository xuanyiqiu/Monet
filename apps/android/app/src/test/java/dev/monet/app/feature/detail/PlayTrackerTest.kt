package dev.monet.app.feature.detail

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayTrackerTest {
    private var now = 0L
    private val tracker = PlayTracker { now }

    @Test
    fun `a second tap while the first is playing is slow`() {
        assertFalse(tracker.tap(textLength = 10))
        now += 500
        assertTrue(tracker.tap(textLength = 10))
    }

    @Test
    fun `a tap after playback has finished is normal again`() {
        assertFalse(tracker.tap(textLength = 4))
        now += 5_000
        assertFalse(tracker.tap(textLength = 4))
    }

    @Test
    fun `a third tap goes back to normal speed`() {
        tracker.tap(10)
        now += 100
        assertTrue(tracker.tap(10))
        now += 100
        assertFalse(tracker.tap(10))
    }

    @Test
    fun `longer text keeps the playing window open longer`() {
        tracker.tap(textLength = 40)
        now += 8_000
        assertTrue(tracker.tap(textLength = 40))
    }

    @Test
    fun `reset ends the playing window`() {
        tracker.tap(10)
        tracker.reset()
        now += 100
        assertFalse(tracker.tap(10))
    }
}
