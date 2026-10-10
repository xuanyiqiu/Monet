package dev.monet.app.core.network

import kotlinx.coroutines.delay

/**
 * Offline stand-in for the API with realistic data (成语, a meme, a Spanish idiom).
 * Token texts always concatenate back to the input, as the contract requires.
 */
class FakeMonetApi(private val latencyMs: Long = 250) : MonetApi {

    override suspend fun health() = Health(status = "ok", version = "fake", llmProvider = "fake")

    override suspend fun me() = Me(userId = "fake-user")

    override suspend fun annotate(request: AnnotateRequest): Annotation {
        delay(latencyMs)
        val sample = Samples.find(request.text)
        return if (sample != null) {
            sample.copy(targetLang = request.targetLang, depth = request.depth)
        } else {
            generic(request)
        }
    }

    override suspend fun annotateLines(request: AnnotateLinesRequest): AnnotateLinesResponse {
        delay(latencyMs)
        return AnnotateLinesResponse(
            targetLang = request.targetLang,
            lines = request.lines.map { line ->
                val a = Samples.find(line.text)
                    ?: generic(AnnotateRequest(text = line.text, targetLang = request.targetLang))
                LineAnnotation(line.id, a.text, a.sourceLang, a.translation, a.tokens, a.expressions)
            },
        )
    }

    override suspend fun sendFeedback(request: FeedbackRequest) = Unit

    // ---- Documents: an in-memory library so the Library and Reader work offline ----

    private val documents = mutableListOf<Document>()
    private val documentText = mutableMapOf<String, String>()

    init {
        val id = "00000000-0000-4000-8000-000000000001"
        val text = Samples.chengyu.text
        documents += sampleDocument(id, "Sample reading", DocumentKind.TXT, text.length.toLong())
        documentText[id] = text
    }

    private fun sampleDocument(id: String, title: String, kind: DocumentKind, size: Long) = Document(
        id = id,
        title = title,
        kind = kind,
        status = DocumentStatus.READY,
        progress = 1.0,
        pageCount = 1,
        sizeBytes = size,
        sourceLang = SourceLang.ZH,
        targetLang = Lang.EN,
        createdAt = "2026-10-10T10:00:00Z",
        updatedAt = "2026-10-10T10:00:00Z",
    )

    override suspend fun listDocuments() = DocumentList(documents.sortedByDescending { it.createdAt })

    override suspend fun getDocument(id: String): Document =
        documents.firstOrNull { it.id == id } ?: throw ApiException(Problem(title = "Not Found", status = 404))

    override suspend fun deleteDocument(id: String) {
        documents.removeAll { it.id == id }
        documentText.remove(id)
    }

    override suspend fun getDocumentPage(id: String, index: Int): DocumentPage {
        getDocument(id)
        if (index != 0) throw ApiException(Problem(title = "Not Found", status = 404))
        val a = Samples.chengyu
        val line = LineAnnotation(a.id, a.text, a.sourceLang, a.translation, a.tokens, a.expressions)
        val b = Samples.meme
        val line2 = LineAnnotation(b.id, b.text, b.sourceLang, b.translation, b.tokens, b.expressions)
        return DocumentPage(
            documentId = id,
            index = 0,
            blocks = listOf(
                DocumentBlock("b0", lines = listOf(DocumentLine("l0", a.text, annotation = line))),
                DocumentBlock("b1", lines = listOf(DocumentLine("l1", b.text, annotation = line2))),
            ),
        )
    }

    override suspend fun uploadDocument(
        fileName: String,
        mimeType: String,
        bytes: ByteArray,
        targetLang: Lang,
        sourceLang: SourceLang,
        title: String?,
    ): Document {
        delay(latencyMs)
        val kind = when {
            fileName.endsWith(".pdf", ignoreCase = true) -> DocumentKind.PDF
            fileName.endsWith(".docx", ignoreCase = true) -> DocumentKind.DOCX
            else -> DocumentKind.TXT
        }
        val doc = sampleDocument("00000000-0000-4000-8000-%012d".format(documents.size + 1), title ?: fileName.substringBeforeLast('.'), kind, bytes.size.toLong())
            .copy(targetLang = targetLang, sourceLang = sourceLang)
        documents += doc
        documentText[doc.id] = bytes.decodeToString()
        return doc
    }

    override suspend fun downloadDocument(id: String): ByteArray {
        getDocument(id)
        return (documentText[id] ?: "").encodeToByteArray()
    }

    private fun generic(request: AnnotateRequest) = Annotation(
        id = "fake-${request.text.hashCode().toUInt()}",
        text = request.text,
        sourceLang = Samples.guessLang(request.text),
        targetLang = request.targetLang,
        depth = request.depth,
        translation = "",
        tokens = listOf(Token(text = request.text, kind = TokenKind.WORD)),
        expressions = emptyList(),
        note = "Translation unavailable offline.",
        cached = false,
    )

    internal object Samples {
        val chengyu = Annotation(
            id = "fake-chengyu-1",
            text = "他这样做简直是画蛇添足。",
            sourceLang = Lang.ZH,
            targetLang = Lang.EN,
            depth = Depth.FULL,
            translation = "Doing it that way is simply gilding the lily.",
            tokens = listOf(
                Token("他", TokenKind.WORD, "tā", "ta1", gloss = "he"),
                Token("这样", TokenKind.WORD, "zhè yàng", "zhe4 yang4", gloss = "this way"),
                Token("做", TokenKind.WORD, "zuò", "zuo4", gloss = "do"),
                Token("简直", TokenKind.WORD, "jiǎn zhí", "jian3 zhi2", gloss = "simply"),
                Token("是", TokenKind.WORD, "shì", "shi4", gloss = "is"),
                Token("画蛇添足", TokenKind.WORD, "huà shé tiān zú", "hua4 she2 tian1 zu2", gloss = "overdo it"),
                Token("。", TokenKind.PUNCT),
            ),
            expressions = listOf(
                Expression(
                    kind = ExpressionKind.CHENGYU,
                    text = "画蛇添足",
                    tokenStart = 5,
                    tokenEnd = 6,
                    pinyin = "huà shé tiān zú",
                    literal = "draw a snake and add feet",
                    meaning = "To ruin something by adding unnecessary details.",
                    origin = "Warring States anecdote: a man won a snake-drawing contest, then added feet and lost.",
                    register = "neutral, written and spoken",
                    equivalent = "gild the lily",
                    confidence = 0.98,
                    source = ExpressionSource.DICTIONARY,
                ),
            ),
            note = null,
            cached = false,
        )

        val meme = Annotation(
            id = "fake-meme-1",
            text = "这首歌真的yyds！",
            sourceLang = Lang.ZH,
            targetLang = Lang.EN,
            depth = Depth.FULL,
            translation = "This song is seriously the GOAT!",
            tokens = listOf(
                Token("这", TokenKind.WORD, "zhè", "zhe4", gloss = "this"),
                Token("首", TokenKind.WORD, "shǒu", "shou3", gloss = "(measure word for songs)"),
                Token("歌", TokenKind.WORD, "gē", "ge1", gloss = "song"),
                Token("真的", TokenKind.WORD, "zhēn de", "zhen1 de5", gloss = "really"),
                Token("yyds", TokenKind.WORD, gloss = "GOAT"),
                Token("！", TokenKind.PUNCT),
            ),
            expressions = listOf(
                Expression(
                    kind = ExpressionKind.MEME,
                    text = "yyds",
                    tokenStart = 4,
                    tokenEnd = 5,
                    pinyin = "yǒng yuǎn de shén",
                    literal = "forever's god",
                    meaning = "Greatest of all time; used to praise a person or thing.",
                    origin = "Initialism of 永远的神, popularized on Chinese social media around 2020.",
                    register = "informal, internet slang, 2020+",
                    equivalent = "GOAT",
                    confidence = 0.95,
                    source = ExpressionSource.BOTH,
                ),
            ),
            note = null,
            cached = false,
        )

        val idiomEs = Annotation(
            id = "fake-idiom-es-1",
            text = "Ana está en las nubes.",
            sourceLang = Lang.ES,
            targetLang = Lang.EN,
            depth = Depth.FULL,
            translation = "Ana has her head in the clouds.",
            tokens = listOf(
                Token("Ana", TokenKind.WORD, ipa = "ˈa.na"),
                Token(" ", TokenKind.SPACE),
                Token("está", TokenKind.WORD, ipa = "esˈta", gloss = "is"),
                Token(" ", TokenKind.SPACE),
                Token("en", TokenKind.WORD, ipa = "en", gloss = "in"),
                Token(" ", TokenKind.SPACE),
                Token("las", TokenKind.WORD, ipa = "las", gloss = "the"),
                Token(" ", TokenKind.SPACE),
                Token("nubes", TokenKind.WORD, ipa = "ˈnu.βes", gloss = "clouds"),
                Token(".", TokenKind.PUNCT),
            ),
            expressions = listOf(
                Expression(
                    kind = ExpressionKind.IDIOM,
                    text = "está en las nubes",
                    tokenStart = 2,
                    tokenEnd = 9,
                    literal = "is in the clouds",
                    meaning = "To be distracted or daydreaming.",
                    register = "colloquial",
                    equivalent = "have one's head in the clouds",
                    confidence = 0.97,
                    source = ExpressionSource.DICTIONARY,
                ),
            ),
            note = null,
            cached = false,
        )

        val all = listOf(chengyu, meme, idiomEs)

        fun find(text: String): Annotation? = all.firstOrNull { it.text == text.trim() }

        fun guessLang(text: String): Lang = when {
            text.any { it.code in 0x4E00..0x9FFF } -> Lang.ZH
            text.any { it in "áéíóúñ¿¡" } -> Lang.ES
            else -> Lang.EN
        }
    }
}
