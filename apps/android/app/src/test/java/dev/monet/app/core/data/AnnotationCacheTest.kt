package dev.monet.app.core.data

import dev.monet.app.core.network.AnnotateRequest
import dev.monet.app.core.network.Annotation
import dev.monet.app.core.network.ApiException
import dev.monet.app.core.network.Depth
import dev.monet.app.core.network.FakeMonetApi
import dev.monet.app.core.network.Lang
import dev.monet.app.core.network.MonetApi
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AnnotationCacheTest {
    @get:Rule val tmp = TemporaryFolder()

    private val sampleText = "他这样做简直是画蛇添足。"

    private val request = AnnotateRequest(text = sampleText, targetLang = Lang.EN, depth = Depth.FULL)

    private suspend fun sample(): Annotation = FakeMonetApi(latencyMs = 0).annotate(request)

    @Test
    fun `put then get returns the annotation marked cached`() = runBlocking {
        val cache = AnnotationCache(tmp.root)
        val annotation = sample()
        cache.put(sampleText, annotation)

        val hit = cache.get(sampleText, Lang.EN, Depth.FULL)

        assertNotNull(hit)
        assertEquals(annotation.translation, hit!!.annotation.translation)
        assertTrue(hit.annotation.cached)
        assertNull(cache.get(sampleText, Lang.ES, Depth.FULL))
    }

    @Test
    fun `annotation without translation is not stored`() = runBlocking {
        val cache = AnnotationCache(tmp.root)
        cache.put(sampleText, sample().copy(translation = ""))

        assertNull(cache.get(sampleText, Lang.EN, Depth.FULL))
    }

    @Test
    fun `oldest entry is evicted past maxEntries`() = runBlocking {
        var tick = 0
        val times = longArrayOf(1_000_000L, 2_000_000L, 3_000_000L)
        val cache = AnnotationCache(tmp.root, maxEntries = 2, clock = { times[tick++ % times.size] })
        val annotation = sample()

        cache.put("a", annotation)
        cache.put("b", annotation)
        cache.put("c", annotation)

        assertNull(cache.get("a", Lang.EN, Depth.FULL))
        assertNotNull(cache.get("b", Lang.EN, Depth.FULL))
        assertNotNull(cache.get("c", Lang.EN, Depth.FULL))
    }

    @Test
    fun `cache key ignores surrounding spaces but not target or depth`() {
        assertEquals(cacheKey(" abc ", Lang.ZH, Depth.FAST), cacheKey("abc", Lang.ZH, Depth.FAST))
        assertNotEquals(cacheKey("abc", Lang.ZH, Depth.FAST), cacheKey("abc", Lang.EN, Depth.FAST))
        assertNotEquals(cacheKey("abc", Lang.ZH, Depth.FAST), cacheKey("abc", Lang.ZH, Depth.FULL))
    }

    @Test
    fun `caching api answers a repeated request from the cache`() = runBlocking {
        var calls = 0
        val delegate = FakeMonetApi(latencyMs = 0)
        val counting = object : MonetApi by delegate {
            override suspend fun annotate(request: AnnotateRequest): Annotation {
                calls++
                return delegate.annotate(request)
            }
        }
        val api = CachingMonetApi(counting, AnnotationCache(tmp.root))

        val first = api.annotate(request)
        val second = api.annotate(request)

        assertEquals(1, calls)
        assertEquals(first.translation, second.translation)
        assertTrue(first.translation.isNotEmpty())
    }

    @Test
    fun `stale entry answers when the server goes offline`() = runBlocking {
        var now = 1_000_000L
        val cache = AnnotationCache(tmp.root, clock = { now })
        var calls = 0
        val failing = FakeMonetApi(latencyMs = 0)
        val delegate = object : MonetApi by failing {
            override suspend fun annotate(request: AnnotateRequest): Annotation {
                calls++
                if (calls > 1) throw ApiException(problem = null)
                return failing.annotate(request)
            }
        }
        val api = CachingMonetApi(delegate, cache, freshForMs = 500)

        val first = api.annotate(request)
        now += 10_000
        val second = api.annotate(request)

        assertEquals(first.translation, second.translation)
        assertTrue(second.translation.isNotEmpty())
    }

    @Test
    fun `offline delegate without a stored answer rethrows ApiException`() = runBlocking {
        val offline = object : MonetApi by FakeMonetApi(latencyMs = 0) {
            override suspend fun annotate(request: AnnotateRequest): Annotation =
                throw ApiException(problem = null)
        }
        val api = CachingMonetApi(offline, AnnotationCache(tmp.root))

        try {
            api.annotate(request)
            fail("expected ApiException")
        } catch (e: ApiException) {
            assertTrue(e.isOffline)
        }
    }
}
