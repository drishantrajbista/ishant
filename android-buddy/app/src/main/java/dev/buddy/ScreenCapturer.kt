package dev.buddy

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Base64
import android.util.DisplayMetrics
import android.view.WindowManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

class Screenshot(
    val jpeg: ByteArray,
    /** Size of the JPEG (the coordinate space of POINT tags). */
    val width: Int,
    val height: Int,
    /** Real display size in pixels, used to scale pointer targets. */
    val displayWidth: Int,
    val displayHeight: Int,
) {
    val jpegBase64: String by lazy { Base64.encodeToString(jpeg, Base64.NO_WRAP) }
}

/**
 * One MediaProjection + VirtualDisplay + ImageReader for the service's lifetime. Android 14 allows a single
 * consent token per projection, so we never recreate the projection; on rotation we only resize the display.
 * Frames arrive on a background thread and all pixel work happens off the main thread.
 */
class ScreenCapturer(context: Context, private val projection: MediaProjection, onStopped: () -> Unit) {
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val thread = HandlerThread("buddy-capture").apply { start() }
    private val frameHandler = Handler(thread.looper)
    private val lock = Any()
    private var latest: Image? = null
    @Volatile private var reader: ImageReader
    private var display: VirtualDisplay
    private var width: Int
    private var height: Int
    private val dpi: Int

    private val callback = object : MediaProjection.Callback() {
        override fun onStop() = onStopped()
    }

    init {
        val m = realMetrics()
        width = m.widthPixels; height = m.heightPixels; dpi = m.densityDpi
        // Android 14 requires the callback to be registered before creating the VirtualDisplay.
        projection.registerCallback(callback, Handler(Looper.getMainLooper()))
        reader = newReader(width, height)
        display = projection.createVirtualDisplay(
            "buddy", width, height, dpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface, null, frameHandler,
        )
    }

    @Suppress("DEPRECATION")
    private fun realMetrics() = DisplayMetrics().also { windowManager.defaultDisplay.getRealMetrics(it) }

    private fun newReader(w: Int, h: Int) = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 3).also { r ->
        r.setOnImageAvailableListener({
            // A VirtualDisplay only emits frames when pixels change, so keep the newest frame around.
            val image = it.acquireLatestImage() ?: return@setOnImageAvailableListener
            if (it !== reader) return@setOnImageAvailableListener image.close()  // frame from a replaced reader
            synchronized(lock) { latest?.close(); latest = image }
        }, frameHandler)
    }

    private fun resizeIfRotated() {
        val m = realMetrics()
        if (m.widthPixels == width && m.heightPixels == height) return
        width = m.widthPixels; height = m.heightPixels
        val old = reader
        synchronized(lock) { latest?.close(); latest = null }
        reader = newReader(width, height)
        display.resize(width, height, dpi)
        display.surface = reader.surface
        old.close()
    }

    /** Grabs the newest frame, downscales it to fit [maxEdge] / [maxPixels] and JPEG-encodes it. */
    suspend fun capture(maxEdge: Int = 1280, quality: Int = 80, maxPixels: Int = Int.MAX_VALUE): Screenshot = withContext(Dispatchers.Default) {
        resizeIfRotated()
        val w = width
        val h = height
        val scale = scaleFor(w, h, maxEdge, maxPixels)
        var bitmap: Bitmap? = null
        var tries = 0
        while (bitmap == null && tries++ < 40) {
            // Copy while holding the lock: the frame thread closes the Image as soon as a newer one arrives.
            bitmap = synchronized(lock) { latest?.takeIf { it.width == w && it.height == h }?.toBitmap(scale) }
            if (bitmap == null) delay(50)
        }
        val scaled = bitmap ?: error("No screen frame available yet")
        // Android 14+ can share a single app; everything else is then captured as black.
        val sample = IntArray(100) { scaled.getPixel((it % 10 * 2 + 1) * scaled.width / 20, (it / 10 * 2 + 1) * scaled.height / 20) }
        if (isBlankFrame(sample)) {
            throw SpokenException("screen capture is blank. stop buddy, start it again, and choose share entire screen.")
        }
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, quality, out)
        Screenshot(out.toByteArray(), scaled.width, scaled.height, w, h)
    }

    /** Copies the frame (dropping row padding) and scales it in one pass. */
    private fun Image.toBitmap(scale: Float): Bitmap {
        val plane = planes[0]
        val rowPixels = plane.rowStride / plane.pixelStride
        val padded = Bitmap.createBitmap(rowPixels, height, Bitmap.Config.ARGB_8888)
        padded.copyPixelsFromBuffer(plane.buffer.also { it.rewind() })
        val matrix = Matrix().apply { setScale(scale, scale) }
        return Bitmap.createBitmap(padded, 0, 0, width, height, matrix, true).also { if (it !== padded) padded.recycle() }
    }

    fun release() {
        projection.unregisterCallback(callback)
        display.release()
        reader.setOnImageAvailableListener(null, null)
        synchronized(lock) { latest?.close(); latest = null }
        reader.close()
        thread.quitSafely()
        projection.stop()
    }
}

/** True if every sampled pixel is essentially black (luminance < 8): a capture that only shares a single app. */
fun isBlankFrame(pixels: IntArray): Boolean = pixels.isNotEmpty() && pixels.all {
    0.299 * (it shr 16 and 0xFF) + 0.587 * (it shr 8 and 0xFF) + 0.114 * (it and 0xFF) < 8
}
