package dev.buddy

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.PointF
import android.media.projection.MediaProjectionManager
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * Orchestrates push-to-talk -> speech-to-text -> screenshot -> answer (Claude app hand-off, or streamed Gemini/Claude
 * reply spoken sentence by sentence + pointer). Runs as a foreground service (mediaProjection|microphone) so the
 * capture session stays alive.
 */
class BuddyService : Service(), BubbleOverlay.Callbacks, PhoneListener.Events {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var settings: Settings
    private lateinit var speaker: Speaker
    private lateinit var phone: PhoneListener
    private val conversation = Conversation()
    private var capturer: ScreenCapturer? = null
    private var bubble: BubbleOverlay? = null
    private var pointer: PointerOverlay? = null

    private var state = BuddyState.IDLE
    private var recording: VoiceRecording? = null        // Gemini voice: the clip for the press in progress
    private var job: Job? = null                         // the request in flight: answer -> speech
    private var shotJob: Deferred<Screenshot>? = null    // the screenshot for the current press
    private var textJob: Job? = null

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
        speaker = Speaker(this) { finishSpeaking() }
        phone = PhoneListener(this, this)
        running.value = true
        return START_NOT_STICKY
    }

    // region touch and push-to-talk

    override fun onTouchDown() {
        if (settings.voiceEngine != VoiceEngine.GEMINI) return
        // Gemini voice records from the first instant so the first word isn't clipped; a tap or drag discards it.
        recording?.discard()
        recording = VoiceRecording(this, { bubble?.setLevel(it) }, { onPressEnd() }).takeIf { it.start() }
    }

    override fun onTouchAbort() { discardRecording() }

    override fun onPressStart() {
        cancelWork()
        bubble?.setText(null)
        when (settings.voiceEngine) {
            VoiceEngine.GEMINI -> when {
                settings.geminiKey.isBlank() -> return fail("add a gemini key in buddy's settings, or switch voice to phone")
                recording == null -> return fail("couldn't open the microphone")
            }
            VoiceEngine.PHONE -> if (!phone.start(settings.speechLanguage)) {
                return fail("speech recognition isn't available on this phone")
            }
        }
        setState(BuddyState.LISTENING)
        warmUp()
    }

    override fun onPressEnd() {
        val clip = recording
        when {
            clip != null -> {
                recording = null
                setState(BuddyState.PROCESSING)
                startCapture()   // grab the screen while the clip's last word is still being recorded
                val shot = shotJob!!
                launchWork { handleAudio(clip, shot) }
            }
            phone.isActive -> {
                setState(BuddyState.PROCESSING)
                startCapture()   // grab the screen while the recognizer finishes
                phone.release()
            }
        }
    }

    override fun onTap() {
        when {
            // Stop everything: the request, any actions, the speech.
            state == BuddyState.PROCESSING || state == BuddyState.RESPONDING -> { cancelWork(); finishSpeaking() }
            // Claude app mode: a tap on the idle bubble shares just the screenshot.
            state == BuddyState.IDLE && settings.provider == Provider.CLAUDE_APP -> {
                cancelWork()
                setState(BuddyState.PROCESSING)
                startCapture()
                val shot = shotJob!!
                launchWork { handOffToClaude(shot.await(), null) }
            }
        }
    }

    // PhoneListener.Events
    override fun onLevel(level: Float) { bubble?.setLevel(level) }
    override fun onText(text: String) { bubble?.setText(text) }
    override fun onReady() { bubble?.buzz() }
    override fun onResult(text: String) = heard(text)
    override fun onError(message: String) = showMessage(message)

    // endregion

    private fun heard(transcript: String) {
        bubble?.setLevel(0f)
        if (transcript.isBlank()) return showMessage(NOT_HEARD)
        bubble?.setText(null)
        setState(BuddyState.PROCESSING)
        startCapture()   // already running if the finger lifted before the recognizer finished
        val shot = shotJob!!
        launchWork { answer(shot.await(), Question(transcript = transcript)) }
    }

    /** Gemini voice: the clip either goes straight into the Gemini request, or is transcribed for the Claude modes. */
    private suspend fun handleAudio(clip: VoiceRecording, shot: Deferred<Screenshot>) {
        val audio = clip.finish() ?: throw IOException(NOT_HEARD)
        if (settings.provider == Provider.GEMINI) return respond(shot.await(), Question(audio = audio))
        val transcript = try {
            GeminiClient(settings) { bubble?.setText(it) }.transcribe(audio)   // the screenshot is being taken meanwhile
        } catch (e: EmptyReplyException) {
            throw IOException(NOT_HEARD)
        } catch (e: GeminiExhaustedException) {
            settings.markGeminiVoiceOutToday()   // phone voice until the next Pacific midnight, then back to Gemini
            throw SpokenException(VOICE_OUT_MESSAGE)
        }
        bubble?.setText("heard: ${transcript.take(100)}")
        answer(shot.await(), Question(transcript = transcript))
    }

    private suspend fun answer(shot: Screenshot, question: Question) {
        if (settings.provider == Provider.CLAUDE_APP) handOffToClaude(shot, question.transcript) else respond(shot, question)
    }

    // region screenshot

    /** Starts the capture for this press (once): hide our windows, let the screen settle, grab the frame, restore. */
    private fun startCapture() {
        if (shotJob != null) return
        val forClaudeApp = settings.provider == Provider.CLAUDE_APP
        shotJob = scope.async {
            bubble?.setHiddenForCapture(true)
            pointer?.dismiss()
            try {
                delay(CAPTURE_DELAY_MS)
                // The Claude app gets a sharper image than the ones we stream to a model.
                if (forClaudeApp) capturer!!.capture(2400, 90) else capturer!!.capture()
            } finally {
                bubble?.setHiddenForCapture(false)
            }
        }
    }

    // endregion

    // region answers

    /** Streams the reply from Gemini/Claude, speaking it sentence by sentence as it arrives. */
    private suspend fun respond(shot: Screenshot, question: Question) {
        speaker.start()
        var heardLine: String? = null
        var noticeLine: String? = null   // e.g. "busy, retrying in 13s" or "switched to ..." from the Gemini client
        fun show(text: String) = bubble?.setText(
            listOfNotNull(noticeLine, heardLine, text.takeIf { it.isNotBlank() }).joinToString("\n\n").ifBlank { null },
        )

        val reply = conversation.ask(
            settings.createProvider { noticeLine = it; show("") }, question, shot,
            onHeard = { heardLine = "heard: ${it.take(100)}"; show("") },
            onText = { soFar ->
                if (state != BuddyState.RESPONDING) setState(BuddyState.RESPONDING)
                show(speaker.onText(soFar).trim())
            },
        )
        val parsed = parsePointingCoordinates(reply.text)
        parsed.coordinate?.let { c ->
            val target = PointF(c.x * shot.displayWidth / shot.width, c.y * shot.displayHeight / shot.height)
            pointer?.flyTo(bubble!!.center(), target, parsed.label)
        }
        if (parsed.spokenText.isBlank()) showMessage("no answer came back") else speaker.end(reply.text)
    }

    /** Claude app mode: open the Claude app with the screenshot (and question) and let the user tap send. */
    private suspend fun handOffToClaude(shot: Screenshot, question: String?) {
        setState(BuddyState.IDLE)
        // Android 15 only lets SYSTEM_ALERT_WINDOW apps start activities from the background while one of their
        // overlay windows is visible, so make sure the bubble is back on screen first.
        delay(OVERLAY_VISIBLE_MS)
        try {
            shareToClaudeApp(shot.jpeg, question)
            showMessage(
                if (question != null) "opened claude, tap send (question also copied)" else "opened claude, tap send",
                6000,
            )
        } catch (e: ActivityNotFoundException) {
            if (isClaudeAppInstalled()) {
                showMessage("this version of the claude app can't take shared screenshots")
            } else {
                openClaudeInPlayStore()
                showMessage("install the Claude app first")
            }
        }
    }

    private fun launchWork(block: suspend () -> Unit) {
        job = scope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: SpokenException) {
                say(e.message.orEmpty())
            } catch (e: Exception) {
                speaker.stop()
                showMessage(friendly(e))
            }
        }
    }

    private fun friendly(e: Exception) = when (e) {
        is UnknownHostException, is ConnectException -> "can't reach the network"
        is SocketTimeoutException -> "the request timed out"
        else -> e.message ?: e.javaClass.simpleName
    }

    // endregion

    // region state

    /** Stops whatever is going on: listening, the request, speech, the pointer. (A recording in progress is kept.) */
    private fun cancelWork() {
        phone.cancel()
        job?.cancel()
        shotJob?.cancel(); shotJob = null
        textJob?.cancel()
        speaker.stop()
        pointer?.dismiss()
    }

    private fun discardRecording() { recording?.discard(); recording = null }

    private fun fail(message: String) { discardRecording(); showMessage(message) }

    /** Shows and speaks a short canned message. */
    private fun say(text: String) {
        speaker.start()
        setState(BuddyState.RESPONDING)
        bubble?.setText(text)
        speaker.end(text)
    }

    private fun finishSpeaking() {
        textJob?.cancel()
        bubble?.setText(null)
        pointer?.speechEnded()
        setState(BuddyState.IDLE)
    }

    private fun setState(s: BuddyState) {
        state = s
        bubble?.setState(s)
        if (s != BuddyState.LISTENING) bubble?.setLevel(0f)
    }

    private fun showMessage(text: String, ms: Long = 4000) {
        shotJob?.cancel(); shotJob = null
        setState(BuddyState.IDLE)
        bubble?.setText(text)
        textJob?.cancel()
        textJob = scope.launch { delay(ms); bubble?.setText(null) }
    }

    // endregion

    /** Fire-and-forget GETs so DNS and TLS are already done when the real request goes out. */
    private fun warmUp() {
        val urls = buildSet {
            when (settings.provider) {
                Provider.GEMINI -> add(GEMINI_URL)
                Provider.CLAUDE -> add(settings.proxyUrl.ifBlank { CLAUDE_URL })
                Provider.CLAUDE_APP -> {}
            }
            if (settings.voiceEngine == VoiceEngine.GEMINI) add(GEMINI_URL)
        }
        for (url in urls) runCatching {
            sharedHttp.newCall(Request.Builder().url(url).build()).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {}
                override fun onResponse(call: Call, response: Response) { response.close() }
            })
        }
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
        recording?.discard()
        if (::phone.isInitialized) phone.destroy()
        if (::speaker.isInitialized) speaker.shutdown()
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
        private const val VOICE_OUT_MESSAGE = "gemini voice is out for today, switched to phone voice. please ask again."
        private const val CAPTURE_DELAY_MS = 150L
        private const val OVERLAY_VISIBLE_MS = 150L
        private const val GEMINI_URL = "https://generativelanguage.googleapis.com/"
        private const val CLAUDE_URL = "https://api.anthropic.com/"

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
