package dev.monet.app.feature.lens.capture

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * On-device OCR. ML Kit's Chinese model also reads Latin script, so one recognizer covers
 * zh, es and en without shipping a second model.
 */
class ScreenTextRecognizer {
    private val recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())

    suspend fun recognize(bitmap: Bitmap): List<ScreenLine> = suspendCancellableCoroutine { cont ->
        recognizer.process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener { result ->
                val lines = result.textBlocks
                    .flatMap { it.lines }
                    .mapNotNull { line ->
                        val box = line.boundingBox ?: return@mapNotNull null
                        ScreenLine(line.text, box.left, box.top, box.right, box.bottom)
                    }
                cont.resume(lines)
            }
            .addOnFailureListener { cont.resumeWithException(it) }
    }

    fun close() = recognizer.close()
}
