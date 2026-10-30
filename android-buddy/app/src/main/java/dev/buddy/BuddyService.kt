package dev.buddy

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.os.IBinder
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Orchestrates push-to-talk -> speech-to-text -> screenshot -> Claude -> TTS + pointer.
 * Runs as a foreground service (mediaProjection|microphone) so the capture session stays alive.
 */
class BuddyService : Service(), BubbleOverlay.Callbacks {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var settings: Settings
    private val conversation = Conversation()
    private var capturer: ScreenCapturer? = null
    private var bubble: BubbleOverlay? = null
    private var pointer: PointerOverlay? = null
    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    private var state = BuddyState.IDLE
    private var listening = false
    private var lastPartial = ""
    private var job: Job? = null
    private var textJob: Job? = null
    private var utteranceId = ""
    private var utteranceCount = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        val data = intent?.getParcelableExtra<Intent>(EXTRA_DATA)
        val code = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        if (capturer != null) return START_NOT_STICKY
        if (data == null || code == 0) { stopSelf(); return START_NOT_STICKY }

        // Must be foreground (with the mediaProjection type) before getMediaProjection on Android 14+.
        var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), type)

        settings = Settings(this)
        val projection = (getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager)
            .getMediaProjection(code, data)
        capturer = ScreenCapturer(this, projection) { stopSelf() }
        pointer = PointerOverlay(this).also { it.show() }
        bubble = BubbleOverlay(this, this).also { it.show() }
        tts = TextToSpeech(this) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            if (ttsReady) tts?.language = Locale.getDefault()
        }.also { engine ->
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) {}
                override fun onDone(id: String?) { scope.launch { speechFinished(id) } }
                @Deprecated("Deprecated in Java")
                override fun onError(id: String?) { scope.launch { speechFinished(id) } }
                override fun onStop(id: String?, interrupted: Boolean) { scope.launch { speechFinished(id) } }
            })
        }
        createRecognizer()
        running.value = true
        return START_NOT_STICKY
    }

    // region push-to-talk

    override fun onPressStart() {
        job?.cancel()
        textJob?.cancel()
        stopSpeech()
        pointer?.dismiss()
        bubble?.setText(null)
        val r = recognizer ?: return showMessage("speech recognition isn't available on this phone")
        lastPartial = ""
        listening = true
        setState(BuddyState.LISTENING)
        r.startListening(
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true),
        )
    }

    override fun onPressEnd() {
        if (listening) recognizer?.stopListening()  // the final result arrives in onResults
    }

    override fun onTap() { if (state == BuddyState.RESPONDING) { stopSpeech(); finishSpeaking() } }

    private fun createRecognizer() {
        recognizer?.destroy()
        recognizer = if (SpeechRecognizer.isRecognitionAvailable(this)) {
            SpeechRecognizer.createSpeechRecognizer(this).apply { setRecognitionListener(listener) }
        } else null
    }

    private val listener = object : RecognitionListener {
        override fun onRmsChanged(rmsdB: Float) { bubble?.setLevel((rmsdB + 2f) / 12f) }
        override fun onPartialResults(partialResults: Bundle?) {
            firstResult(partialResults)?.let { lastPartial = it; bubble?.setText(it) }
        }
        override fun onResults(results: Bundle?) = heard(firstResult(results) ?: lastPartial)
        override fun onError(error: Int) {
            if (!listening) return
            if (lastPartial.isNotBlank()) return heard(lastPartial)
            when (error) {
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
                    showMessage("i didn't catch that")
                else -> { createRecognizer(); showMessage("speech recognition failed (error $error)") }
            }
        }
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    private fun firstResult(b: Bundle?) =
        b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.takeIf { it.isNotBlank() }

    // endregion

    private fun heard(transcript: String) {
        listening = false
        bubble?.setLevel(0f)
        if (transcript.isBlank()) return showMessage("i didn't catch that")
        bubble?.setText(null)
        job = scope.launch {
            setState(BuddyState.PROCESSING)
            try {
                // Hide our own windows so they aren't in the shot; the capture needs a fresh frame afterwards.
                bubble?.setHiddenForCapture(true)
                pointer?.dismiss()
                delay(200)
                val shot = try { capturer!!.capture() } finally { bubble?.setHiddenForCapture(false) }
                val reply = conversation.ask(Settings(this@BuddyService).createProvider(), transcript, shot)
                val parsed = parsePointingCoordinates(reply)
                parsed.coordinate?.let { c ->
                    val target = android.graphics.PointF(
                        c.x * shot.displayWidth / shot.width, c.y * shot.displayHeight / shot.height,
                    )
                    pointer?.flyTo(bubble!!.center(), target, parsed.label)
                }
                speak(parsed.spokenText)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                showMessage(e.message ?: "something went wrong")
            }
        }
    }

    // region TTS

    private fun speak(text: String) {
        val engine = tts
        if (text.isBlank()) return setState(BuddyState.IDLE)
        setState(BuddyState.RESPONDING)
        bubble?.setText(text)
        if (engine == null || !ttsReady) {
            textJob = scope.launch { delay(6000); finishSpeaking() }
            return
        }
        utteranceId = "buddy-${++utteranceCount}"
        engine.speak(text.take(TextToSpeech.getMaxSpeechInputLength() - 1), TextToSpeech.QUEUE_FLUSH, null, utteranceId)
    }

    /** Stops TTS without triggering the normal "finished" handling. */
    private fun stopSpeech() {
        utteranceId = ""
        tts?.stop()
    }

    private fun speechFinished(id: String?) {
        if (id == null || id != utteranceId) return
        utteranceId = ""
        finishSpeaking()
    }

    private fun finishSpeaking() {
        textJob?.cancel()
        bubble?.setText(null)
        pointer?.speechEnded()
        setState(BuddyState.IDLE)
    }

    // endregion

    private fun setState(s: BuddyState) {
        state = s
        bubble?.setState(s)
        if (s != BuddyState.LISTENING) bubble?.setLevel(0f)
    }

    private fun showMessage(text: String) {
        listening = false
        setState(BuddyState.IDLE)
        bubble?.setText(text)
        textJob?.cancel()
        textJob = scope.launch { delay(4000); bubble?.setText(null) }
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Buddy", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(
            this, 0, Intent(this, BuddyService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_buddy)
            .setContentTitle("Buddy is running")
            .setContentText("Hold the floating bubble to talk")
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(0, "Stop", stop)
            .build()
    }

    override fun onDestroy() {
        running.value = false
        scope.cancel()
        recognizer?.destroy()
        tts?.shutdown()
        bubble?.remove()
        pointer?.remove()
        capturer?.release()
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "dev.buddy.STOP"
        private const val EXTRA_RESULT_CODE = "result_code"
        private const val EXTRA_DATA = "projection_data"
        private const val CHANNEL_ID = "buddy"
        private const val NOTIFICATION_ID = 1

        val running = MutableStateFlow(false)

        fun start(context: Context, resultCode: Int, data: Intent) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, BuddyService::class.java)
                    .putExtra(EXTRA_RESULT_CODE, resultCode)
                    .putExtra(EXTRA_DATA, data),
            )
        }

        fun stop(context: Context) {
            context.startService(Intent(context, BuddyService::class.java).setAction(ACTION_STOP))
        }
    }
}
