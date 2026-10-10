package dev.monet.app.core.network

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DtoParsingTest {

    private val annotationJson = """
        {
          "id": "abc123",
          "text": "他画蛇添足。",
          "source_lang": "zh",
          "target_lang": "en",
          "depth": "full",
          "translation": "He gilded the lily.",
          "tokens": [
            {"text": "他", "kind": "word", "pinyin": "tā", "pinyin_numbered": "ta1", "ipa": null, "gloss": "he"},
            {"text": "画蛇添足", "kind": "word", "pinyin": "huà shé tiān zú", "pinyin_numbered": "hua4 she2 tian1 zu2"},
            {"text": "。", "kind": "punct", "pinyin": null}
          ],
          "expressions": [
            {
              "kind": "chengyu", "text": "画蛇添足", "token_start": 1, "token_end": 2,
              "pinyin": "huà shé tiān zú", "literal": "draw a snake and add feet",
              "meaning": "Ruin something by overdoing it.", "origin": null, "register": null,
              "equivalent": "gild the lily", "confidence": 0.98, "source": "dictionary",
              "future_field": 1
            }
          ],
          "note": null,
          "cached": true
        }
    """.trimIndent()

    @Test
    fun `annotation parses snake_case, nulls and unknown keys`() {
        val a = MonetJson.decodeFromString(Annotation.serializer(), annotationJson)
        assertEquals(Lang.ZH, a.sourceLang)
        assertEquals(Depth.FULL, a.depth)
        assertEquals("ta1", a.tokens[0].pinyinNumbered)
        assertNull(a.tokens[0].ipa)
        assertNull(a.tokens[2].pinyin)
        assertEquals(TokenKind.PUNCT, a.tokens[2].kind)
        val e = a.expressions.single()
        assertEquals(ExpressionKind.CHENGYU, e.kind)
        assertEquals(1, e.tokenStart)
        assertEquals(2, e.tokenEnd)
        assertEquals(ExpressionSource.DICTIONARY, e.source)
        assertEquals(0.98, e.confidence, 1e-9)
        assertNull(a.note)
        assertTrue(a.cached)
        assertEquals(a.text, a.tokens.joinToString("") { it.text })
    }

    @Test
    fun `empty translation is preserved so the UI can detect unavailable model`() {
        val json = annotationJson.replace("\"He gilded the lily.\"", "\"\"")
            .replace("\"note\": null", "\"note\": \"Translation unavailable.\"")
        val a = MonetJson.decodeFromString(Annotation.serializer(), json)
        assertEquals("", a.translation)
        assertEquals("Translation unavailable.", a.note)
    }

    @Test
    fun `annotate request encodes snake_case and enum values`() {
        val body = MonetJson.encodeToString(
            AnnotateRequest.serializer(),
            AnnotateRequest(
                text = "hola",
                targetLang = Lang.EN,
                context = AnnotationContext(app = "weibo"),
            ),
        )
        val obj = Json.parseToJsonElement(body).jsonObject
        assertEquals("hola", obj["text"]!!.jsonPrimitive.content)
        assertEquals("auto", obj["source_lang"]!!.jsonPrimitive.content)
        assertEquals("en", obj["target_lang"]!!.jsonPrimitive.content)
        assertEquals("full", obj["depth"]!!.jsonPrimitive.content)
        assertEquals("weibo", obj["context"]!!.jsonObject["app"]!!.jsonPrimitive.content)
        assertTrue("null fields must be omitted", obj["context"]!!.jsonObject["surrounding"] == null)
    }

    @Test
    fun `lines response parses`() {
        val json = """
            {"target_lang": "es", "lines": [
              {"id": "l1", "text": "你好", "source_lang": "zh", "translation": "Hola",
               "tokens": [{"text": "你好", "kind": "word", "pinyin": "nǐ hǎo"}], "expressions": []}
            ]}
        """.trimIndent()
        val r = MonetJson.decodeFromString(AnnotateLinesResponse.serializer(), json)
        assertEquals(Lang.ES, r.targetLang)
        assertEquals("l1", r.lines.single().id)
    }

    @Test
    fun `document and page parse including null bbox for reflow`() {
        val doc = MonetJson.decodeFromString(
            Document.serializer(),
            """
            {"id": "5d1c0a52-0000-4000-8000-000000000001", "title": "Notas", "kind": "pdf",
             "status": "processing", "progress": 0.42, "page_count": null, "size_bytes": 1024,
             "source_lang": "auto", "target_lang": "en", "error": null,
             "created_at": "2026-10-10T10:00:00Z", "updated_at": "2026-10-10T10:01:00Z"}
            """.trimIndent(),
        )
        assertEquals(DocumentStatus.PROCESSING, doc.status)
        assertEquals(SourceLang.AUTO, doc.sourceLang)
        assertNull(doc.pageCount)

        val page = MonetJson.decodeFromString(
            DocumentPage.serializer(),
            """
            {"document_id": "5d1c0a52-0000-4000-8000-000000000001", "index": 0, "width": 595.0, "height": null,
             "blocks": [{"id": "b1", "bbox": [0.1, 0.1, 0.9, 0.2], "lines": [
               {"id": "l1", "text": "你好", "bbox": null,
                "annotation": {"id": "l1", "text": "你好", "source_lang": "zh", "translation": "Hello",
                               "tokens": [], "expressions": []}}]}]}
            """.trimIndent(),
        )
        val block = page.blocks.single()
        assertEquals(4, block.bbox!!.size)
        assertNull(block.lines.single().bbox)
        assertNull(page.height)
    }

    @Test
    fun `problem json parses with defaults`() {
        val p = MonetJson.decodeFromString(
            Problem.serializer(),
            """{"title": "Too Many Requests", "status": 429, "detail": "Slow down"}""",
        )
        assertEquals("about:blank", p.type)
        assertEquals(429, p.status)
    }

    @Test
    fun `fake api samples keep the token concatenation invariant`() = runTest {
        val api = FakeMonetApi(latencyMs = 0)
        FakeMonetApi.Samples.all.forEach { sample ->
            val a = api.annotate(AnnotateRequest(text = sample.text, targetLang = Lang.EN))
            assertEquals(sample.text, a.tokens.joinToString("") { it.text })
            a.expressions.forEach {
                assertTrue(it.tokenStart in 0 until it.tokenEnd)
                assertTrue(it.tokenEnd <= a.tokens.size)
            }
        }
    }

    @Test
    fun `fake api reports unavailable translation for unknown text`() = runTest {
        val a = FakeMonetApi(latencyMs = 0).annotate(AnnotateRequest(text = "algo desconocido", targetLang = Lang.EN))
        assertEquals("", a.translation)
        assertTrue(a.note != null)
    }

    @Test
    fun `fake lines echo ids and order`() = runTest {
        val r = FakeMonetApi(latencyMs = 0).annotateLines(
            AnnotateLinesRequest(
                lines = listOf(LineInput("a", "这首歌真的yyds！"), LineInput("b", "hello")),
                targetLang = Lang.EN,
            ),
        )
        assertEquals(listOf("a", "b"), r.lines.map { it.id })
    }
}
