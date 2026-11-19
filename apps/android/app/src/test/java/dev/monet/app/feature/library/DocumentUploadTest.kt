package dev.monet.app.feature.library

import dev.monet.app.core.network.Document
import dev.monet.app.core.network.DocumentKind
import dev.monet.app.core.network.DocumentStatus
import dev.monet.app.core.network.FakeMonetApi
import dev.monet.app.core.network.Lang
import dev.monet.app.core.network.SourceLang
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DocumentUploadTest {
    private fun doc(title: String, kind: DocumentKind, status: DocumentStatus) = Document(
        "x", title, kind, status, 0.0, null, 10, SourceLang.AUTO, Lang.EN, null, "2026-10-10T10:00:00Z", "2026-10-10T10:00:00Z",
    )

    @Test
    fun `retry file name adds the extension only when missing`() {
        assertEquals("Notes.pdf", retryFileName(doc("Notes", DocumentKind.PDF, DocumentStatus.FAILED)))
        assertEquals("Notes.DOCX", retryFileName(doc("Notes.DOCX", DocumentKind.DOCX, DocumentStatus.FAILED)))
        assertEquals("a.b.txt", retryFileName(doc("a.b", DocumentKind.TXT, DocumentStatus.FAILED)))
    }

    @Test
    fun `only failed documents can be retried`() {
        assertTrue(canRetry(doc("a", DocumentKind.PDF, DocumentStatus.FAILED)))
        assertFalse(canRetry(doc("a", DocumentKind.PDF, DocumentStatus.READY)))
        assertFalse(canRetry(doc("a", DocumentKind.PDF, DocumentStatus.PROCESSING)))
    }

    @Test
    fun `retry uploads a copy and deletes the failed original`() = runBlocking {
        val api = FakeMonetApi(latencyMs = 0)
        val original = api.uploadDocument("Chapter.txt", "text/plain", "你好".encodeToByteArray(), Lang.ES, SourceLang.AUTO, null)
        val before = api.listDocuments().items.map { it.id }
        assertNull(retryDocument(api, original.copy(status = DocumentStatus.FAILED)))
        val after = api.listDocuments().items
        assertFalse(after.any { it.id == original.id })
        assertEquals(before.size, after.size)
        val copy = after.first { it.id !in before }
        assertEquals(original.title, copy.title)
        assertEquals(Lang.ES, copy.targetLang)
    }
}
