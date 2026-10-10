package dev.monet.app.feature.lens.capture

import org.junit.Assert.assertEquals
import org.junit.Test

class LineGroupingTest {
    private fun line(text: String, left: Int, top: Int, width: Int = 400, height: Int = 40) =
        ScreenLine(text, left, top, left + width, top + height)

    @Test
    fun `stacked lines of one paragraph form a single block`() {
        val blocks = groupIntoBlocks(
            listOf(
                line("The quick brown fox", 40, 100),
                line("jumps over the lazy dog", 40, 148),
                line("and keeps running.", 40, 196, width = 300),
            ),
        )
        assertEquals(1, blocks.size)
        assertEquals("The quick brown fox jumps over the lazy dog and keeps running.", blocks.single().text)
    }

    @Test
    fun `large vertical gap starts a new block`() {
        val blocks = groupIntoBlocks(listOf(line("First post", 40, 100), line("Second post", 40, 400)))
        assertEquals(listOf("First post", "Second post"), blocks.map { it.text })
    }

    @Test
    fun `different font size separates a heading from its body`() {
        val blocks = groupIntoBlocks(
            listOf(
                line("BIG HEADLINE", 40, 100, height = 80),
                line("small body text under it", 40, 190, height = 30),
            ),
        )
        assertEquals(2, blocks.size)
    }

    @Test
    fun `side by side columns stay separate and keep their own lines`() {
        val blocks = groupIntoBlocks(
            listOf(
                line("left one", 40, 100, width = 300),
                line("right one", 600, 100, width = 300),
                line("left two", 40, 148, width = 300),
                line("right two", 600, 148, width = 300),
            ),
        )
        assertEquals(setOf("left one left two", "right one right two"), blocks.map { it.text }.toSet())
    }

    @Test
    fun `cjk lines join without spaces and mixed text keeps spacing`() {
        assertEquals("你好世界", joinLines(listOf("你好", "世界")))
        assertEquals("hello世界", joinLines(listOf("hello", "世界")))
        assertEquals("one two", joinLines(listOf("one", "two")))
    }

    @Test
    fun `reading order sorts rows top to bottom then left to right`() {
        val ordered = readingOrder(
            listOf(
                line("c", 40, 200),
                line("b", 500, 104),
                line("a", 40, 100),
            ),
        )
        assertEquals(listOf("a", "b", "c"), ordered.map { it.text })
    }

    @Test
    fun `noise lines and empty text are dropped`() {
        val blocks = groupIntoBlocks(
            listOf(line("   ", 0, 0), line("--", 0, 100), line("real text", 0, 200)),
        )
        assertEquals(listOf("real text"), blocks.map { it.text })
    }

    @Test
    fun `block bounds cover all of its lines`() {
        val block = groupIntoBlocks(listOf(line("aaa", 40, 100, width = 200), line("bbb", 60, 148, width = 300))).single()
        assertEquals(40, block.left)
        assertEquals(100, block.top)
        assertEquals(360, block.right)
        assertEquals(188, block.bottom)
    }
}
