package dev.monet.app.feature.lens.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SelectionTest {
    private fun line(text: String, left: Int, top: Int, width: Int = 400, height: Int = 40) =
        ScreenLine(text, left, top, left + width, top + height)

    private val screen = listOf(
        line("第一段的第一行", 40, 100),
        line("第一段的第二行", 40, 148),
        line("Another post below", 40, 600),
        line("Right column text", 560, 100, width = 300),
    )

    @Test
    fun `drag corners are normalized in any direction`() {
        assertEquals(SelRect(10, 20, 110, 220), selRect(110f, 220f, 10f, 20f))
        assertEquals(SelRect(10, 20, 110, 220), selRect(10f, 20f, 110f, 220f))
    }

    @Test
    fun `drag selects only the lines it mostly covers`() {
        val r = resolveSelection(screen, SelRect(0, 90, 500, 200), tapSizePx = 48)!!
        assertEquals("第一段的第一行第一段的第二行", r.text)
        assertEquals(SelRect(40, 100, 440, 188), r.bounds)
    }

    @Test
    fun `a line only grazed by the drag is not selected`() {
        val r = resolveSelection(screen, SelRect(0, 90, 500, 105), tapSizePx = 48)
        assertNull(r)
    }

    @Test
    fun `tap selects the whole block under the finger`() {
        val r = resolveSelection(screen, SelRect(200, 160, 205, 165), tapSizePx = 48)!!
        assertEquals("第一段的第一行第一段的第二行", r.text)
    }

    @Test
    fun `tap on empty space selects nothing`() {
        assertNull(resolveSelection(screen, SelRect(300, 900, 302, 902), tapSizePx = 48))
    }

    @Test
    fun `tap slop forgives a finger just outside the block`() {
        val r = resolveSelection(screen, SelRect(40, 575, 42, 577), tapSizePx = 48)!!
        assertEquals("Another post below", r.text)
    }

    @Test
    fun `drag across two columns keeps them as separate paragraphs`() {
        val r = resolveSelection(screen, SelRect(0, 90, 900, 150), tapSizePx = 48)!!
        assertEquals(setOf("第一段的第一行", "Right column text"), r.text.split("\n").toSet())
    }

    @Test
    fun `smallest block wins when blocks overlap at the tap point`() {
        val blocks = listOf(
            TextBlock(listOf(line("big", 0, 0, width = 800, height = 800))),
            TextBlock(listOf(line("small", 100, 100, width = 100, height = 40))),
        )
        assertEquals("small", blockAt(blocks, 120, 120, slop = 10)!!.text)
    }
}
