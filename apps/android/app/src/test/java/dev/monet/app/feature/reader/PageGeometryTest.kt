package dev.monet.app.feature.reader

import dev.monet.app.core.network.DocumentBlock
import dev.monet.app.core.network.DocumentLine
import dev.monet.app.core.network.DocumentPage
import dev.monet.app.core.network.Lang
import dev.monet.app.core.network.LineAnnotation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PageGeometryTest {
    private fun line(id: String, bbox: List<Float>?) = DocumentLine(
        id, "text $id", bbox, LineAnnotation(id, "text $id", Lang.ZH, "tr $id", emptyList(), emptyList()),
    )

    private fun page(blocks: List<DocumentBlock>) = DocumentPage("doc", 0, 600.0, 800.0, blocks)

    @Test
    fun `normalized boxes scale to pixels`() {
        val r = normalizedToPx(listOf(0.1f, 0.2f, 0.5f, 0.4f), 1000f, 2000f)!!
        assertEquals(100f, r.left, 0.01f)
        assertEquals(400f, r.top, 0.01f)
        assertEquals(500f, r.right, 0.01f)
        assertEquals(800f, r.bottom, 0.01f)
    }

    @Test
    fun `malformed or empty boxes are rejected`() {
        assertNull(normalizedToPx(null, 100f, 100f))
        assertNull(normalizedToPx(listOf(0.1f, 0.2f), 100f, 100f))
        assertNull(normalizedToPx(listOf(0.5f, 0.5f, 0.5f, 0.9f), 100f, 100f))
        assertNull(normalizedToPx(listOf(0.6f, 0.1f, 0.2f, 0.9f), 100f, 100f))
    }

    @Test
    fun `out of range values are clamped to the page`() {
        val r = normalizedToPx(listOf(-0.5f, 0.1f, 1.5f, 0.2f), 100f, 100f)!!
        assertEquals(0f, r.left, 0f)
        assertEquals(100f, r.right, 0f)
    }

    @Test
    fun `page height keeps the pdf aspect ratio`() {
        assertEquals(1000, fitHeight(500.0, 625.0, 800))
        assertEquals(1131, fitHeight(null, null, 800))
        assertEquals(1131, fitHeight(0.0, 10.0, 800))
    }

    @Test
    fun `a tap inside a line selects it and the smallest box wins`() {
        val outer = line("outer", listOf(0.0f, 0.0f, 1.0f, 1.0f))
        val inner = line("inner", listOf(0.2f, 0.2f, 0.4f, 0.3f))
        val p = page(listOf(DocumentBlock("b", lines = listOf(outer, inner))))
        assertEquals("inner", hitLine(p, 300f, 250f, 1000f, 1000f)!!.id)
        assertEquals("outer", hitLine(p, 900f, 900f, 1000f, 1000f)!!.id)
    }

    @Test
    fun `a tap just outside a line still selects the nearest one within the slop`() {
        val a = line("a", listOf(0.1f, 0.10f, 0.9f, 0.15f))
        val b = line("b", listOf(0.1f, 0.30f, 0.9f, 0.35f))
        val p = page(listOf(DocumentBlock("b", lines = listOf(a, b))))
        assertEquals("a", hitLine(p, 500f, 160f, 1000f, 1000f, slopPx = 12f)!!.id)
        assertNull(hitLine(p, 500f, 230f, 1000f, 1000f, slopPx = 12f))
    }

    @Test
    fun `lines without a box fall back to their block box`() {
        val l = line("l", null)
        val p = page(listOf(DocumentBlock("b", bbox = listOf(0.1f, 0.1f, 0.5f, 0.2f), lines = listOf(l))))
        assertEquals("l", hitLine(p, 200f, 150f, 1000f, 1000f)!!.id)
        assertNull(hitLine(p, 800f, 800f, 1000f, 1000f))
    }

    @Test
    fun `prefetch stays inside the page range`() {
        assertEquals(listOf(0, 1), pagesToPrefetch(0, 5))
        assertEquals(listOf(3, 4), pagesToPrefetch(4, 5))
        assertEquals(listOf(1, 2, 3), pagesToPrefetch(2, 5))
        assertEquals(listOf(0), pagesToPrefetch(0, 1))
    }
}
