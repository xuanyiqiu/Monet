package dev.monet.app.feature.lens.capture

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.Looper

/**
 * Holds one MediaProjection and one VirtualDisplay for the life of the lens.
 * Android 14 forbids creating a second VirtualDisplay from the same projection, so frames are
 * pulled from a single long-lived [ImageReader] instead of re-creating the display per capture.
 */
class ScreenCapturer(context: Context, resultCode: Int, resultData: Intent) {
    private val projection: MediaProjection
    private val reader: ImageReader
    private val display: VirtualDisplay
    private val handler = Handler(Looper.getMainLooper())

    val width: Int
    val height: Int

    init {
        val manager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection = manager.getMediaProjection(resultCode, resultData)
        // Required before createVirtualDisplay on Android 14+.
        projection.registerCallback(object : MediaProjection.Callback() {}, handler)

        val metrics = context.resources.displayMetrics
        val size = realDisplaySize(context)
        width = size.first
        height = size.second
        reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        display = projection.createVirtualDisplay(
            "monet-lens",
            width,
            height,
            metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface,
            null,
            handler,
        )
    }

    /** The most recent frame, or null if the display has not produced one yet. */
    fun grabFrame(): Bitmap? {
        val image = reader.acquireLatestImage() ?: return null
        try {
            val plane = image.planes[0]
            val rowPadding = plane.rowStride - plane.pixelStride * width
            val padded = Bitmap.createBitmap(width + rowPadding / plane.pixelStride, height, Bitmap.Config.ARGB_8888)
            padded.copyPixelsFromBuffer(plane.buffer)
            return if (padded.width == width) padded else Bitmap.createBitmap(padded, 0, 0, width, height).also { padded.recycle() }
        } finally {
            image.close()
        }
    }

    fun release() {
        display.release()
        reader.close()
        projection.stop()
    }
}

/** Full display size in pixels, including the status and navigation bars. */
fun realDisplaySize(context: Context): Pair<Int, Int> {
    val wm = context.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
    return if (android.os.Build.VERSION.SDK_INT >= 30) {
        wm.maximumWindowMetrics.bounds.let { it.width() to it.height() }
    } else {
        val m = android.util.DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(m)
        m.widthPixels to m.heightPixels
    }
}
