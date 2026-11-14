package dev.monet.app.core.data

import dev.monet.app.core.network.AnnotateRequest
import dev.monet.app.core.network.Annotation
import dev.monet.app.core.network.ApiException
import dev.monet.app.core.network.Depth
import dev.monet.app.core.network.Lang
import dev.monet.app.core.network.MonetApi
import dev.monet.app.core.network.MonetJson
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * On-device tier of the annotation cache (ADR-006): one small JSON file per explained text, the
 * oldest dropped past [maxEntries]. A repeated text is instant and still works without a network.
 */
class AnnotationCache(
    private val dir: File,
    private val maxEntries: Int = 300,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    data class Hit(val annotation: Annotation, val ageMs: Long)

    suspend fun get(text: String, target: Lang, depth: Depth): Hit? = withContext(Dispatchers.IO) {
        val file = fileFor(text, target, depth)
        if (!file.isFile) return@withContext null
        try {
            val annotation = MonetJson.decodeFromString(Annotation.serializer(), file.readText())
            Hit(annotation.copy(cached = true), (clock() - file.lastModified()).coerceAtLeast(0))
        } catch (e: Exception) {
            // A half-written or outdated entry is worthless; drop it rather than fail the lookup.
            file.delete()
            null
        }
    }

    /** Stores a complete annotation. One without a translation is a degraded answer and is not kept. */
    suspend fun put(text: String, annotation: Annotation) = withContext(Dispatchers.IO) {
        if (annotation.translation.isEmpty()) return@withContext
        dir.mkdirs()
        val file = fileFor(text, annotation.targetLang, annotation.depth)
        val temp = File(dir, file.name + ".tmp")
        temp.writeText(MonetJson.encodeToString(Annotation.serializer(), annotation))
        if (!temp.renameTo(file)) {
            file.delete()
            temp.renameTo(file)
        }
        file.setLastModified(clock())
        trim()
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        dir.listFiles()?.forEach { it.delete() }
        Unit
    }

    private fun trim() {
        val files = dir.listFiles { f -> f.name.endsWith(".json") } ?: return
        if (files.size <= maxEntries) return
        files.sortedBy { it.lastModified() }.take(files.size - maxEntries).forEach { it.delete() }
    }

    private fun fileFor(text: String, target: Lang, depth: Depth): File = File(dir, cacheKey(text, target, depth) + ".json")
}

/** Stable key for a text in one target language and depth. */
fun cacheKey(text: String, target: Lang, depth: Depth): String {
    val digest = MessageDigest.getInstance("SHA-256").digest("v1|${depth.name}|${target.name}|${text.trim()}".toByteArray())
    return digest.joinToString("") { "%02x".format(it) }.take(40)
}

/**
 * Puts [cache] in front of [delegate] for `annotate`: a fresh entry answers without a request, and
 * a stale one still answers when the server cannot be reached or has no translation to give.
 */
class CachingMonetApi(
    private val delegate: MonetApi,
    private val cache: AnnotationCache,
    private val freshForMs: Long = 7L * 24 * 60 * 60 * 1000,
) : MonetApi by delegate {

    override suspend fun annotate(request: AnnotateRequest): Annotation {
        val hit = cache.get(request.text, request.targetLang, request.depth)
        if (hit != null && hit.ageMs <= freshForMs) return hit.annotation
        val fresh = try {
            delegate.annotate(request)
        } catch (e: ApiException) {
            return hit?.annotation ?: throw e
        }
        if (fresh.translation.isEmpty()) return hit?.annotation ?: fresh
        cache.put(request.text, fresh)
        return fresh
    }
}
