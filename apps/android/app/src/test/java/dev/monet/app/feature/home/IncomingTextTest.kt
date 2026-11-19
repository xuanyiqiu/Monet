package dev.monet.app.feature.home

import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IncomingTextTest {
    @Test
    fun `send intent uses EXTRA_TEXT and trims`() {
        assertEquals("你好", extractIncomingText(Intent.ACTION_SEND, "text/plain", "  你好\n", null))
    }

    @Test
    fun `send intent with non text mime type is ignored`() {
        assertNull(extractIncomingText(Intent.ACTION_SEND, "image/png", "x", null))
    }

    @Test
    fun `process text intent uses its own extra`() {
        assertEquals("hola", extractIncomingText(Intent.ACTION_PROCESS_TEXT, "text/plain", "ignored", "hola"))
    }

    @Test
    fun `launcher intent falls back to debug extra and blank means nothing`() {
        assertEquals("debug", extractIncomingText(Intent.ACTION_MAIN, null, null, null, "debug"))
        assertNull(extractIncomingText(Intent.ACTION_MAIN, null, null, null, null))
        assertNull(extractIncomingText(Intent.ACTION_SEND, "text/plain", "   ", null))
    }

    @Test
    fun `text is capped at the contract limit`() {
        val long = "a".repeat(MAX_TEXT_LENGTH + 500)
        assertEquals(MAX_TEXT_LENGTH, normalizeIncoming(long)!!.length)
    }

    private val docx = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"

    @Test
    fun `text share with EXTRA_TEXT stays text even when a stream is present`() {
        assertEquals(IncomingShare.Text("hola"), classifyIncoming(Intent.ACTION_SEND, "text/plain", "hola", true, null))
        assertEquals(IncomingShare.Text("hola"), classifyIncoming(Intent.ACTION_SEND, "text/plain", "hola", false, null))
    }

    @Test
    fun `pdf and docx shares with a stream are documents`() {
        assertEquals(IncomingShare.File("application/pdf"), classifyIncoming(Intent.ACTION_SEND, "application/pdf", null, true, null))
        assertEquals(IncomingShare.File(docx), classifyIncoming(Intent.ACTION_SEND, docx, null, true, null))
    }

    @Test
    fun `plain text file share without EXTRA_TEXT is a document`() {
        assertEquals(IncomingShare.File("text/plain"), classifyIncoming(Intent.ACTION_SEND, "text/plain", null, true, null))
        assertEquals(IncomingShare.File("text/plain"), classifyIncoming(Intent.ACTION_SEND, "text/plain", "  ", true, null))
    }

    @Test
    fun `unsupported stream types are flagged and no stream means nothing`() {
        assertEquals(IncomingShare.Unsupported, classifyIncoming(Intent.ACTION_SEND, "image/png", null, true, null))
        assertEquals(IncomingShare.Nothing, classifyIncoming(Intent.ACTION_SEND, "application/pdf", null, false, null))
        assertEquals(IncomingShare.Nothing, classifyIncoming(Intent.ACTION_MAIN, null, null, false, null))
    }

    @Test
    fun `process text still wins over streams`() {
        assertEquals(IncomingShare.Text("x"), classifyIncoming(Intent.ACTION_PROCESS_TEXT, "text/plain", null, false, "x"))
    }
}
