package dev.monet.app.feature.lens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LensStateTest {
    @Test
    fun `bubble snaps to the nearest edge`() {
        assertEquals(0, snapToEdgeX(windowX = 100, windowWidth = 120, screenWidth = 1080))
        assertEquals(1080 - 120, snapToEdgeX(windowX = 700, windowWidth = 120, screenWidth = 1080))
    }

    @Test
    fun `exact middle counts as right half`() {
        assertTrue(isOnRightHalf(windowX = 480, windowWidth = 120, screenWidth = 1080))
        assertFalse(isOnRightHalf(windowX = 400, windowWidth = 120, screenWidth = 1080))
    }

    @Test
    fun `expanded pill snaps by its own width`() {
        assertEquals(1080 - 816, snapToEdgeX(windowX = 500, windowWidth = 816, screenWidth = 1080))
    }

    @Test
    fun `y is clamped inside the screen`() {
        assertEquals(0, clampY(-50, 120, 2400))
        assertEquals(2400 - 120, clampY(5000, 120, 2400))
        assertEquals(0, clampY(10, 3000, 2400))
    }

    @Test
    fun `lines per step stays within bounds`() {
        assertEquals(MIN_LINES_PER_STEP, stepLines(1, -1))
        assertEquals(3, stepLines(2, +1))
        assertEquals(MAX_LINES_PER_STEP, stepLines(MAX_LINES_PER_STEP, +1))
    }

    @Test
    fun `state updates are visible to collectors`() {
        LensState.update { LensUiState(running = true, mode = LensMode.SELECT, linesPerStep = 4) }
        assertEquals(LensMode.SELECT, LensState.state.value.mode)
        LensState.update { it.copy(running = false) }
        assertFalse(LensState.state.value.running)
    }
}
