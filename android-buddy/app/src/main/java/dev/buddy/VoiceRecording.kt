package dev.buddy

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.delay
import java.io.File
import kotlin.math.sqrt

/**
 * One push-to-talk clip recorded by Buddy itself (for Gemini voice): AAC, 16 kHz mono, about 32 kbps, so the
 * upload stays small. Single-use: [start], then [finish] (keeps the last word) or [discard].
 */
class VoiceRecording(
    private val context: Context,
    private val onLevel: (Float) -> Unit,
    private val onLimit: () -> Unit,
) {
    private val file = File.createTempFile("voice", ".aac", context.cacheDir)
    private val handler = Handler(Looper.getMainLooper())
    private var recorder: MediaRecorder? = null

    private val poll = object : Runnable {
        override fun run() {
            val r = recorder ?: return
            onLevel(sqrt(r.maxAmplitude / 16000f).coerceIn(0f, 1f))
            handler.postDelayed(this, LEVEL_POLL_MS)
        }
    }

    /** Starts recording right away; false if the microphone couldn't be opened. */
    fun start(): Boolean {
        @Suppress("DEPRECATION")
        val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else MediaRecorder()
        try {
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.AAC_ADTS)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioChannels(1)
            r.setAudioSamplingRate(16_000)
            r.setAudioEncodingBitRate(32_000)
            r.setMaxDuration(MAX_MS)
            r.setOnInfoListener { _, what, _ ->
                if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) onLimit()
            }
            r.setOutputFile(file.absolutePath)
            r.prepare()
            r.start()
        } catch (e: Exception) {
            r.release()
            file.delete()
            return false
        }
        recorder = r
        handler.post(poll)
        return true
    }

    /** Keeps recording a moment longer so the last word isn't clipped, then returns the clip (null if empty). */
    suspend fun finish(): ByteArray? = try {
        delay(TAIL_MS)
        stop()
    } finally {
        stop()  // only does anything if we were cancelled during the tail
    }

    fun discard() { stop() }

    private fun stop(): ByteArray? {
        val r = recorder ?: return null
        recorder = null
        handler.removeCallbacks(poll)
        // stop() throws when almost nothing was recorded.
        val ok = try { r.stop(); true } catch (e: RuntimeException) { false }
        r.release()
        val bytes = if (ok) file.readBytes() else null
        file.delete()
        return bytes?.takeIf { it.size > MIN_BYTES }
    }

    private companion object {
        const val MAX_MS = 60_000
        const val TAIL_MS = 300L
        const val LEVEL_POLL_MS = 50L
        const val MIN_BYTES = 1000
    }
}
