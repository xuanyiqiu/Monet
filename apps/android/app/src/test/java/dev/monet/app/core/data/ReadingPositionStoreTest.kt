package dev.monet.app.core.data

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ReadingPositionStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun file() = File(tmp.root, "sub/positions.json")

    @Test
    fun `saved position survives a new store instance`() {
        ReadingPositionStore(file()).save("doc-1", ReadingPosition(7, 120))
        assertEquals(ReadingPosition(7, 120), ReadingPositionStore(file()).get("doc-1"))
    }

    @Test
    fun `unknown document has no position`() {
        val store = ReadingPositionStore(file())
        store.save("a", ReadingPosition(1))
        assertNull(store.get("b"))
    }

    @Test
    fun `saving again overwrites and documents are independent`() {
        val store = ReadingPositionStore(file())
        store.save("a", ReadingPosition(1, 5))
        store.save("b", ReadingPosition(9, 0))
        store.save("a", ReadingPosition(3, 2))
        val reloaded = ReadingPositionStore(file())
        assertEquals(ReadingPosition(3, 2), reloaded.get("a"))
        assertEquals(ReadingPosition(9, 0), reloaded.get("b"))
    }

    @Test
    fun `corrupt file is treated as empty and can be rewritten`() {
        file().apply { parentFile!!.mkdirs(); writeText("{not json") }
        val store = ReadingPositionStore(file())
        assertNull(store.get("a"))
        store.save("a", ReadingPosition(2))
        assertEquals(ReadingPosition(2), ReadingPositionStore(file()).get("a"))
    }

    @Test
    fun `forget removes a document`() {
        val store = ReadingPositionStore(file())
        store.save("a", ReadingPosition(2))
        store.forget("a")
        assertNull(ReadingPositionStore(file()).get("a"))
    }
}
