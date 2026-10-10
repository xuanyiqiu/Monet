package dev.monet.app.core.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Response captured from the real API (gemini provider) for a 成语 sentence. */
class RealApiFixtureTest {
    @Test
    fun `real annotate response decodes and keeps the token invariant`() {
        val json = javaClass.classLoader!!.getResource("fixtures/annotate_real_zh.json")!!.readText()
        val a = MonetJson.decodeFromString(Annotation.serializer(), json)
        assertEquals(Lang.ZH, a.sourceLang)
        assertEquals(a.text, a.tokens.joinToString("") { it.text })
        assertTrue(a.translation.isNotEmpty())
        a.expressions.forEach { assertTrue(it.tokenStart in 0 until it.tokenEnd && it.tokenEnd <= a.tokens.size) }
    }
}
