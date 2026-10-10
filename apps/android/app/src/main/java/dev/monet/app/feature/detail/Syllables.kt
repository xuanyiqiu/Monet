package dev.monet.app.feature.detail

import dev.monet.app.core.network.ExpressionKind

/** One pinyin syllable with its tone (1..4, 5 = neutral/unknown). */
data class Syllable(val text: String, val tone: Int)

/**
 * Pairs tone-mark pinyin ("huà shé") with numbered pinyin ("hua4 she2") syllable by syllable.
 * When the two strings disagree on syllable count the tones are unknown, so everything is neutral
 * rather than wrongly colored.
 */
fun splitSyllables(pinyin: String?, numbered: String?): List<Syllable> {
    val marks = pinyin?.trim()?.split(Regex("\\s+"))?.filter { it.isNotEmpty() }.orEmpty()
    if (marks.isEmpty()) return emptyList()
    val nums = numbered?.trim()?.split(Regex("\\s+"))?.filter { it.isNotEmpty() }.orEmpty()
    val aligned = nums.size == marks.size
    return marks.mapIndexed { i, s ->
        val tone = if (aligned) nums[i].lastOrNull()?.digitToIntOrNull()?.takeIf { it in 1..5 } ?: 5 else 5
        Syllable(s, tone)
    }
}

/** Idioms and the like are underlined in indigo (dotted); memes and wordplay in seal (wavy). */
enum class UnderlineStyle { INDIGO_DOTTED, SEAL_WAVY }

fun ExpressionKind.underlineStyle(): UnderlineStyle = when (this) {
    ExpressionKind.CHENGYU,
    ExpressionKind.IDIOM,
    ExpressionKind.PROVERB,
    ExpressionKind.CLASSICAL,
    ExpressionKind.XIEHOUYU,
    -> UnderlineStyle.INDIGO_DOTTED
    ExpressionKind.MEME,
    ExpressionKind.SLANG,
    ExpressionKind.WORDPLAY,
    ExpressionKind.OTHER,
    -> UnderlineStyle.SEAL_WAVY
}
