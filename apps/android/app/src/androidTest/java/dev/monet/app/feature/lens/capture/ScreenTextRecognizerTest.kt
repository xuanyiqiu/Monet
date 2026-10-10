package dev.monet.app.feature.lens.capture

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test

/** Runs real ML Kit OCR on bitmaps drawn here, so no binary fixtures are needed. */
class ScreenTextRecognizerTest {
    private val recognizer = ScreenTextRecognizer()

    @After
    fun tearDown() = recognizer.close()

    private fun render(vararg lines: String): Bitmap {
        val bitmap = Bitmap.createBitmap(1000, 160 * lines.size + 80, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = 72f
            typeface = Typeface.SANS_SERIF
        }
        lines.forEachIndexed { i, text -> canvas.drawText(text, 40f, 120f + 160f * i, paint) }
        return bitmap
    }

    @Test
    fun recognizesChineseLine() = runBlocking {
        val lines = recognizer.recognize(render("你好，世界"))
        val text = lines.joinToString("") { it.text }
        assertTrue("got: $text", text.contains("你好") && text.contains("世界"))
    }

    @Test
    fun recognizesSpanishLineWithAccents() = runBlocking {
        val lines = recognizer.recognize(render("Hola, ¿cómo estás?"))
        val text = lines.joinToString(" ") { it.text }.lowercase()
        assertTrue("got: $text", text.contains("hola") && text.contains("estás".take(3)))
    }

    @Test
    fun mixedImageGroupsIntoOrderedBlocks() = runBlocking {
        val lines = recognizer.recognize(render("这首歌真的好听", "This song is great"))
        val blocks = groupIntoBlocks(lines)
        assertTrue("blocks: ${blocks.map { it.text }}", blocks.isNotEmpty())
        val text = blocks.joinToString("\n") { it.text }
        assertTrue("got: $text", text.contains("好听") && text.contains("great", ignoreCase = true))
        assertTrue(blocks.all { it.bottom > it.top && it.right > it.left })
    }

    @Test
    fun blankImageYieldsNoLines() = runBlocking {
        val blank = Bitmap.createBitmap(400, 400, Bitmap.Config.ARGB_8888).also { Canvas(it).drawColor(Color.WHITE) }
        assertTrue(recognizer.recognize(blank).isEmpty())
    }
}
