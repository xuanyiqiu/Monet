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
}
