package dev.monet.app.feature.detail

import dev.monet.app.core.network.ExpressionKind
import org.junit.Assert.assertEquals
import org.junit.Test

class SyllablesTest {
    @Test
    fun `tones come from numbered pinyin aligned with tone marks`() {
        val s = splitSyllables("huà shé tiān zú", "hua4 she2 tian1 zu2")
        assertEquals(listOf("huà", "shé", "tiān", "zú"), s.map { it.text })
        assertEquals(listOf(4, 2, 1, 2), s.map { it.tone })
    }

    @Test
    fun `neutral tone 5 is kept`() {
        assertEquals(5, splitSyllables("de", "de5").single().tone)
    }

    @Test
    fun `mismatched syllable counts fall back to neutral instead of wrong colors`() {
        val s = splitSyllables("nǐ hǎo", "ni3")
        assertEquals(listOf(5, 5), s.map { it.tone })
    }

    @Test
    fun `missing numbered pinyin is neutral and missing pinyin is empty`() {
        assertEquals(listOf(5), splitSyllables("tā", null).map { it.tone })
        assertEquals(emptyList<Syllable>(), splitSyllables(null, "ta1"))
        assertEquals(emptyList<Syllable>(), splitSyllables("  ", null))
    }

    @Test
    fun `underline style follows the design system`() {
        assertEquals(UnderlineStyle.INDIGO_DOTTED, ExpressionKind.CHENGYU.underlineStyle())
        assertEquals(UnderlineStyle.INDIGO_DOTTED, ExpressionKind.IDIOM.underlineStyle())
        assertEquals(UnderlineStyle.SEAL_WAVY, ExpressionKind.MEME.underlineStyle())
        assertEquals(UnderlineStyle.SEAL_WAVY, ExpressionKind.WORDPLAY.underlineStyle())
    }
}
