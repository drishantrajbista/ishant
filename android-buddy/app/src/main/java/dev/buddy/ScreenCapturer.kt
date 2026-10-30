package dev.buddy

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.DisplayMetrics
import android.view.WindowManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

class Screenshot(
    val jpegBase64: String,
    /** Size of the JPEG sent to Claude (coordinate space of POINT tags). */
    val width: Int,
    val height: Int,
    /** Real display size in pixels, used to scale pointer targets. */
    val displayWidth: Int,
    val displayHeight: Int,
)

/**
 * One MediaProjection + VirtualDisplay + ImageReader for the service's lifetime. Android 14 allows a single
 * consent token per projection, so we never recreate the projection; on rotation we only resize the display.
 */
class ScreenCapturer(context: Context, private val projection: MediaProjection, onStopped: () -> Unit) {
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val handler = Handler(Looper.getMainLooper())
    private val lock = Any()
    private var latest: Image? = null
    private var reader: ImageReader
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
        projection.registerCallback(callback, handler)
        reader = newReader(width, height)
        display = projection.createVirtualDisplay(
            "buddy", width, height, dpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface, null, handler,
        )
    }

    @Suppress("DEPRECATION")
    private fun realMetrics() = DisplayMetrics().also { windowManager.defaultDisplay.getRealMetrics(it) }

    private fun newReader(w: Int, h: Int) = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 3).also { r ->
        r.setOnImageAvailableListener({
            // A VirtualDisplay only emits frames when pixels change, so keep the newest frame around.
            val image = it.acquireLatestImage() ?: return@setOnImageAvailableListener
            synchronized(lock) { latest?.close(); latest = image }
        }, handler)
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

    /** Grabs the newest frame, downscales so the long edge is <= 1280 and JPEG-encodes it (quality 80). */
    suspend fun capture(): Screenshot {
        resizeIfRotated()
        var frame: Bitmap? = null
        var tries = 0
        while (frame == null && tries++ < 40) {
            frame = synchronized(lock) { latest?.toBitmap() }
            if (frame == null) delay(50)
        }
        val full = frame ?: error("No screen frame available yet")
        return withContext(Dispatchers.Default) {
            val scale = MAX_EDGE.toFloat() / max(full.width, full.height)
            val scaled = if (scale < 1f) {
                Bitmap.createScaledBitmap(full, (full.width * scale).roundToInt(), (full.height * scale).roundToInt(), true)
            } else full
            val out = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, 80, out)
            Screenshot(
                Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP),
                scaled.width, scaled.height, full.width, full.height,
            )
        }
    }

    private fun Image.toBitmap(): Bitmap {
        val plane = planes[0]
        val rowPixels = plane.rowStride / plane.pixelStride
        val padded = Bitmap.createBitmap(rowPixels, height, Bitmap.Config.ARGB_8888)
        padded.copyPixelsFromBuffer(plane.buffer.also { it.rewind() })
        return if (rowPixels == width) padded else Bitmap.createBitmap(padded, 0, 0, width, height)
    }

    fun release() {
        projection.unregisterCallback(callback)
        display.release()
        synchronized(lock) { latest?.close(); latest = null }
        reader.close()
        projection.stop()
    }

    private companion object {
        const val MAX_EDGE = 1280
    }
}
