package dev.monet.app.feature.lens.capture

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Display
import java.nio.ByteBuffer

/**
 * Holds one MediaProjection and one VirtualDisplay for the life of the lens.
 *
 * Android 14 forbids creating a second VirtualDisplay from the same projection, so the display is
 * long-lived and frames are pulled from an [ImageReader]. A static screen produces no new frames,
 * so the newest image is kept open: [grabFrame] always has something to return.
 *
 * @param onStopped called on the main thread when Android ends the projection (the user stopped
 *   sharing from the status bar, or the device was locked). The capturer is useless after that.
 */
class ScreenCapturer(
    context: Context,
    resultCode: Int,
    resultData: Intent,
    private val onStopped: () -> Unit,
) {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val frameThread = HandlerThread("monet-capture").apply { start() }
    private val frameHandler = Handler(frameThread.looper)
    private val lock = Any()

    private val projection: MediaProjection
    private val display: VirtualDisplay
    private var reader: ImageReader
    private var latest: Image? = null

    @Volatile private var released = false

    /** Called on a background thread with the signature of every new frame (see [FrameGate]). */
    @Volatile var onFrame: ((ByteArray) -> Unit)? = null

    var width: Int
        private set
    var height: Int
        private set

    init {
        val manager = appContext.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection = checkNotNull(manager.getMediaProjection(resultCode, resultData)) { "No media projection" }
        // Required before createVirtualDisplay on Android 14+.
        projection.registerCallback(
            object : MediaProjection.Callback() {
                override fun onStop() {
                    if (!released) onStopped()
                }
            },
            mainHandler,
        )
        val (w, h) = realDisplaySize(appContext)
        width = w
        height = h
        reader = newReader(w, h)
        display = projection.createVirtualDisplay(
            "monet-lens",
            w,
            h,
            appContext.resources.displayMetrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface,
            null,
            mainHandler,
        )
    }

    // Three images: the one kept as [latest] plus the two acquireLatestImage needs to drop stale ones.
    private fun newReader(w: Int, h: Int): ImageReader =
        ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 3).also { created ->
            created.setOnImageAvailableListener({ source ->
                val image = try {
                    source.acquireLatestImage()
                } catch (e: IllegalStateException) {
                    null
                } ?: return@setOnImageAvailableListener
                val signature: ByteArray
                synchronized(lock) {
                    if (released || source !== reader) {
                        image.close()
                        return@setOnImageAvailableListener
                    }
                    latest?.close()
                    latest = image
                    signature = signatureOf(image)
                }
                onFrame?.invoke(signature)
            }, frameHandler)
        }

    /** A [FrameGate.COLS] x [FrameGate.ROWS] grid of luma samples: enough to notice a scroll, cheap per frame. */
    private fun signatureOf(image: Image): ByteArray {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val out = ByteArray(FrameGate.COLS * FrameGate.ROWS)
        var i = 0
        for (row in 0 until FrameGate.ROWS) {
            val y = ((row + 0.5f) * image.height / FrameGate.ROWS).toInt().coerceAtMost(image.height - 1)
            for (col in 0 until FrameGate.COLS) {
                val x = ((col + 0.5f) * image.width / FrameGate.COLS).toInt().coerceAtMost(image.width - 1)
                val offset = y * plane.rowStride + x * plane.pixelStride
                val r = buffer.get(offset).toInt() and 0xFF
                val g = buffer.get(offset + 1).toInt() and 0xFF
                val b = buffer.get(offset + 2).toInt() and 0xFF
                out[i++] = ((r * 77 + g * 150 + b * 29) shr 8).toByte()
            }
        }
        return out
    }

    /** A copy of the newest frame, or null before the display has produced one. */
    fun grabFrame(): Bitmap? = synchronized(lock) {
        val image = latest ?: return null
        val plane = image.planes[0]
        val rowPixels = plane.rowStride / plane.pixelStride
        val needed = rowPixels * 4 * image.height
        val source = plane.buffer.duplicate().apply { rewind() }
        // The last row may stop at the image width, leaving the buffer shorter than stride x height.
        val pixels = if (source.remaining() >= needed) {
            source
        } else {
            ByteBuffer.allocate(needed).apply {
                put(source)
                rewind()
            }
        }
        val padded = Bitmap.createBitmap(rowPixels, image.height, Bitmap.Config.ARGB_8888)
        padded.copyPixelsFromBuffer(pixels)
        if (rowPixels == image.width) {
            padded
        } else {
            Bitmap.createBitmap(padded, 0, 0, image.width, image.height).also { padded.recycle() }
        }
    }

    /** Follows a rotation or a display size change. Returns true when the size changed. */
    fun resizeToDisplay(): Boolean {
        val (w, h) = realDisplaySize(appContext)
        synchronized(lock) {
            if (released || (w == width && h == height)) return false
            val old = reader
            latest?.close()
            latest = null
            width = w
            height = h
            reader = newReader(w, h)
            display.resize(w, h, appContext.resources.displayMetrics.densityDpi)
            display.surface = reader.surface
            old.close()
        }
        return true
    }

    fun release() {
        synchronized(lock) {
            if (released) return
            released = true
            latest?.close()
            latest = null
        }
        onFrame = null
        runCatching { display.release() }
        runCatching { reader.close() }
        runCatching { projection.stop() }
        frameThread.quitSafely()
    }
}

/** Full display size in pixels, including the status and navigation bars, in the current rotation. */
fun realDisplaySize(context: Context): Pair<Int, Int> {
    // The default Display follows rotations even when asked from a Service or Application context.
    val display = (context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager).getDisplay(Display.DEFAULT_DISPLAY)
    val metrics = DisplayMetrics()
    @Suppress("DEPRECATION")
    display.getRealMetrics(metrics)
    return metrics.widthPixels to metrics.heightPixels
}
