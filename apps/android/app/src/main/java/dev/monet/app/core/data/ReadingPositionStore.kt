package dev.monet.app.core.data

import dev.monet.app.core.network.MonetJson
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer

/** Where the reader was in a document: first visible list item and the scroll offset inside it. */
@Serializable
data class ReadingPosition(val index: Int, val offset: Int = 0)

/** Remembers the last reading position per document in a small JSON file. */
class ReadingPositionStore(private val file: File) {
    private val serializer = MapSerializer(String.serializer(), ReadingPosition.serializer())
    private var cache: MutableMap<String, ReadingPosition>? = null

    @Synchronized
    private fun map(): MutableMap<String, ReadingPosition> = cache ?: run {
        val loaded = runCatching { MonetJson.decodeFromString(serializer, file.readText()) }.getOrDefault(emptyMap())
        loaded.toMutableMap().also { cache = it }
    }

    @Synchronized
    fun get(documentId: String): ReadingPosition? = map()[documentId]

    @Synchronized
    fun save(documentId: String, position: ReadingPosition) {
        val m = map()
        if (m[documentId] == position) return
        m[documentId] = position
        write(m)
    }

    @Synchronized
    fun forget(documentId: String) {
        val m = map()
        if (m.remove(documentId) != null) write(m)
    }

    private fun write(m: Map<String, ReadingPosition>) {
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(MonetJson.encodeToString(serializer, m))
        }
    }
}
