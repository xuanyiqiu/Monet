package dev.monet.app.feature.library

import dev.monet.app.core.network.Document
import dev.monet.app.core.network.DocumentKind
import dev.monet.app.core.network.DocumentStatus
import dev.monet.app.core.network.Lang
import dev.monet.app.core.network.SourceLang
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DocumentLogicTest {
    private fun doc(status: DocumentStatus, progress: Double = 0.0, pages: Int? = null, error: String? = null) = Document(
        id = "id", title = "t", kind = DocumentKind.PDF, status = status, progress = progress, pageCount = pages,
        sourceLang = SourceLang.AUTO, targetLang = Lang.EN, error = error, createdAt = "a", updatedAt = "b",
    )

    @Test
    fun `kind comes from the extension first then the mime type`() {
        assertEquals(DocumentKind.PDF, kindFromFile("Book.PDF", "application/octet-stream"))
        assertEquals(DocumentKind.DOCX, kindFromFile("notes.docx", null))
        assertEquals(DocumentKind.TXT, kindFromFile("readme.txt", "application/octet-stream"))
        assertEquals(DocumentKind.PDF, kindFromFile("download", "application/pdf"))
        assertEquals(DocumentKind.TXT, kindFromFile(null, "text/plain"))
    }

    @Test
    fun `unsupported files are rejected`() {
        assertNull(kindFromFile("photo.png", "image/png"))
        assertNull(kindFromFile("archive.zip", null))
        assertNull(kindFromFile(null, null))
    }

    @Test
    fun `mime types match the kinds`() {
        assertEquals("application/pdf", mimeFor(DocumentKind.PDF))
        assertEquals("text/plain", mimeFor(DocumentKind.TXT))
    }

    @Test
    fun `status labels follow the design system wording`() {
        assertEquals("Queued", statusLabel(doc(DocumentStatus.QUEUED)))
        assertEquals("Processing · 42%", statusLabel(doc(DocumentStatus.PROCESSING, progress = 0.42)))
        assertEquals("12 pages", statusLabel(doc(DocumentStatus.READY, 1.0, pages = 12)))
        assertEquals("1 page", statusLabel(doc(DocumentStatus.READY, 1.0, pages = 1)))
        assertEquals("Ready", statusLabel(doc(DocumentStatus.READY, 1.0, pages = null)))
        assertEquals("Failed · Scanned PDF", statusLabel(doc(DocumentStatus.FAILED, error = "Scanned PDF")))
        assertEquals("Failed", statusLabel(doc(DocumentStatus.FAILED)))
    }

    @Test
    fun `progress is clamped and only shown while processing`() {
        assertEquals(1f, progressFraction(doc(DocumentStatus.PROCESSING, progress = 4.0))!!, 0f)
        assertEquals(0f, progressFraction(doc(DocumentStatus.PROCESSING, progress = -1.0))!!, 0f)
        assertNull(progressFraction(doc(DocumentStatus.READY, progress = 1.0)))
        assertEquals("Processing · 100%", statusLabel(doc(DocumentStatus.PROCESSING, progress = 7.0)))
    }

    @Test
    fun `polling is quick only while something is in progress`() {
        val idle = listOf(doc(DocumentStatus.READY, 1.0), doc(DocumentStatus.FAILED))
        val busy = idle + doc(DocumentStatus.QUEUED)
        assertFalse(hasActiveDocuments(idle))
        assertTrue(hasActiveDocuments(busy))
        assertEquals(15_000L, pollDelayMillis(idle))
        assertEquals(3_000L, pollDelayMillis(busy))
        assertEquals(15_000L, pollDelayMillis(emptyList()))
    }
}
