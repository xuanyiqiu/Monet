package dev.monet.app.core.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// DTOs mirror contracts/openapi.yaml exactly. Do not change shapes here without a contract change.

@Serializable
enum class Lang {
    @SerialName("zh") ZH,
    @SerialName("es") ES,
    @SerialName("en") EN,
}

@Serializable
enum class SourceLang {
    @SerialName("auto") AUTO,
    @SerialName("zh") ZH,
    @SerialName("es") ES,
    @SerialName("en") EN,
}

@Serializable
enum class Depth {
    @SerialName("fast") FAST,
    @SerialName("full") FULL,
}

@Serializable
enum class TokenKind {
    @SerialName("word") WORD,
    @SerialName("punct") PUNCT,
    @SerialName("space") SPACE,
    @SerialName("number") NUMBER,
    @SerialName("symbol") SYMBOL,
}

@Serializable
enum class ExpressionKind {
    @SerialName("chengyu") CHENGYU,
    @SerialName("idiom") IDIOM,
    @SerialName("proverb") PROVERB,
    @SerialName("xiehouyu") XIEHOUYU,
    @SerialName("meme") MEME,
    @SerialName("slang") SLANG,
    @SerialName("wordplay") WORDPLAY,
    @SerialName("classical") CLASSICAL,
    @SerialName("other") OTHER,
}

@Serializable
enum class ExpressionSource {
    @SerialName("dictionary") DICTIONARY,
    @SerialName("model") MODEL,
    @SerialName("both") BOTH,
}

@Serializable
enum class DocumentStatus {
    @SerialName("queued") QUEUED,
    @SerialName("processing") PROCESSING,
    @SerialName("ready") READY,
    @SerialName("failed") FAILED,
}

@Serializable
enum class DocumentKind {
    @SerialName("pdf") PDF,
    @SerialName("docx") DOCX,
    @SerialName("txt") TXT,
}

@Serializable
data class Health(
    val status: String,
    val version: String,
    @SerialName("llm_provider") val llmProvider: String,
)

@Serializable
data class Me(@SerialName("user_id") val userId: String)

@Serializable
data class AnnotationContext(
    val surrounding: String? = null,
    val app: String? = null,
)

@Serializable
data class AnnotateRequest(
    val text: String,
    @SerialName("source_lang") val sourceLang: SourceLang = SourceLang.AUTO,
    @SerialName("target_lang") val targetLang: Lang,
    val depth: Depth = Depth.FULL,
    val context: AnnotationContext? = null,
)

@Serializable
data class Token(
    val text: String,
    val kind: TokenKind,
    val pinyin: String? = null,
    @SerialName("pinyin_numbered") val pinyinNumbered: String? = null,
    val ipa: String? = null,
    val gloss: String? = null,
)

@Serializable
data class Expression(
    val kind: ExpressionKind,
    val text: String,
    /** Inclusive start index into the owning annotation's tokens. */
    @SerialName("token_start") val tokenStart: Int,
    /** Exclusive end index. */
    @SerialName("token_end") val tokenEnd: Int,
    val pinyin: String? = null,
    val literal: String? = null,
    val meaning: String,
    val origin: String? = null,
    val register: String? = null,
    val equivalent: String? = null,
    val confidence: Double,
    val source: ExpressionSource,
)

@Serializable
data class Annotation(
    val id: String,
    val text: String,
    @SerialName("source_lang") val sourceLang: Lang,
    @SerialName("target_lang") val targetLang: Lang,
    val depth: Depth,
    /** Empty string means the language model was unavailable; show [note] and the dictionary. */
    val translation: String,
    val tokens: List<Token>,
    val expressions: List<Expression>,
    val note: String? = null,
    val cached: Boolean,
)

@Serializable
data class LineInput(val id: String, val text: String)

@Serializable
data class AnnotateLinesRequest(
    val lines: List<LineInput>,
    @SerialName("source_lang") val sourceLang: SourceLang = SourceLang.AUTO,
    @SerialName("target_lang") val targetLang: Lang,
    val context: AnnotationContext? = null,
)

@Serializable
data class LineAnnotation(
    val id: String,
    val text: String,
    @SerialName("source_lang") val sourceLang: Lang,
    val translation: String,
    val tokens: List<Token>,
    val expressions: List<Expression>,
)

@Serializable
data class AnnotateLinesResponse(
    @SerialName("target_lang") val targetLang: Lang,
    val lines: List<LineAnnotation>,
)

@Serializable
data class Document(
    val id: String,
    val title: String,
    val kind: DocumentKind,
    val status: DocumentStatus,
    val progress: Double,
    @SerialName("page_count") val pageCount: Int? = null,
    @SerialName("size_bytes") val sizeBytes: Long = 0,
    @SerialName("source_lang") val sourceLang: SourceLang,
    @SerialName("target_lang") val targetLang: Lang,
    val error: String? = null,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
)

@Serializable
data class DocumentList(val items: List<Document>)

@Serializable
data class DocumentLine(
    val id: String,
    val text: String,
    /** [x0, y0, x1, y1] normalized 0..1, origin top-left; null for reflowable documents. */
    val bbox: List<Float>? = null,
    val annotation: LineAnnotation,
)

@Serializable
data class DocumentBlock(
    val id: String,
    val bbox: List<Float>? = null,
    val lines: List<DocumentLine>,
)

@Serializable
data class DocumentPage(
    @SerialName("document_id") val documentId: String,
    val index: Int,
    val width: Double? = null,
    val height: Double? = null,
    val blocks: List<DocumentBlock>,
)

@Serializable
data class FeedbackRequest(
    val text: String,
    val translation: String? = null,
    @SerialName("target_lang") val targetLang: Lang,
    val comment: String? = null,
)

/** RFC 9457 problem+json body. */
@Serializable
data class Problem(
    val type: String = "about:blank",
    val title: String,
    val status: Int,
    val detail: String? = null,
    val instance: String? = null,
)
