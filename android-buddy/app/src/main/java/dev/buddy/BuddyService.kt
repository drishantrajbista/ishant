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
import android.graphics.Bitmap
import android.graphics.PointF
import android.os.Build
import android.media.projection.MediaProjectionManager
import android.os.IBinder
import android.widget.Toast
import android.util.DisplayMetrics
import android.view.WindowManager
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
 * Orchestrates push-to-talk -> speech-to-text -> screenshot -> answer. Claude app mode hands the screenshot to the
 * Claude app and later reads its answer back (clipboard or share) to speak it and draw on the screen; Claude API mode
 * streams the reply, speaking it sentence by sentence. Runs as a foreground service (mediaProjection|microphone) so
 * the capture session stays alive.
 */
class BuddyService : Service(), BubbleOverlay.Callbacks, PhoneListener.Events {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var settings: Settings
    private lateinit var speaker: Speaker
    private lateinit var phone: PhoneListener
    private lateinit var agent: ClaudeAgent
    private lateinit var control: PhoneControl
    private var ready = false
    private var capturer: ScreenCapturer? = null
    private var bubble: BubbleOverlay? = null
    private var drawing: DrawingOverlay? = null

    private var state = BuddyState.IDLE
    private var recording: VoiceRecording? = null        // Gemini voice: the clip for the press in progress
    private var job: Job? = null                         // the request in flight: answer -> speech
    private var shotJob: Deferred<Observation>? = null    // the screenshot for the current press
    private var textJob: Job? = null

    /** What the Claude app was sent, kept so its answer can be drawn onto the screen later. */
    private class Handoff(
        val shotW: Int, val shotH: Int, val displayW: Int, val displayH: Int,
        val question: String?, val copied: String?,
    )
    private var handoff: Handoff? = null    // non-null while an answer from the Claude app is awaited
    private var waitJob: Job? = null
    private var stopping = false   // the user pressed Stop, so a projection that ends is not news

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stopping = true; stopSelf(); return START_NOT_STICKY }
            ACTION_ANSWER_TEXT -> {
                if (ready) onAnswerText(intent.getStringExtra(EXTRA_TEXT).orEmpty())
                return START_NOT_STICKY
            }
        }
        val data = intent?.getParcelableExtra<Intent>(EXTRA_DATA)
        val code = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        if (ready) return START_NOT_STICKY
        // With the accessibility service on (Android 11+) screenshots come from it and no screen-recording consent is needed.
        val projectionNeeded = !(Build.VERSION.SDK_INT >= 30 && BuddyAccessibilityService.instance != null)
        if (projectionNeeded && (data == null || code == 0)) { stopSelf(); return START_NOT_STICKY }

        // Must be foreground (with the mediaProjection type, when used) before getMediaProjection on Android 14+.
        var type = 0
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        }
        if (projectionNeeded) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        if (type == 0) { stopSelf(); return START_NOT_STICKY }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), type)

        settings = Settings(this)
        if (projectionNeeded) {
            val projection = (getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager)
                .getMediaProjection(code, data!!)
            capturer = ScreenCapturer(this, projection) {
                if (!stopping) notifyProjectionEnded()
                stopSelf()
            }
        }
        agent = ClaudeAgent(settings)
        control = PhoneControl(this, stage)
        drawing = DrawingOverlay(this).also { it.show() }
        bubble = BubbleOverlay(this, this).also { it.show() }
        speaker = Speaker(this) { finishSpeaking() }
        phone = PhoneListener(this, this)
        ready = true
        running.value = true
        return START_NOT_STICKY
    }

    // region touch and push-to-talk

    override fun onTouchDown() {
        warmUp()   // DNS and TLS are done by the time the question is ready
        if (settings.voiceEngine != VoiceEngine.GEMINI) return
        // Gemini voice records from the first instant so the first word isn't clipped; a tap or drag discards it.
        recording?.discard()
        recording = VoiceRecording(this, { bubble?.setLevel(it) }, { onPressEnd() }).takeIf { it.start() }
    }

    override fun onTouchAbort() { discardRecording() }

    override fun onClose() {
        Toast.makeText(this, "Buddy closed", Toast.LENGTH_SHORT).show()
        stopping = true
        stopSelf()
    }

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
            // Stop everything: the request, the speech, the drawing.
            state == BuddyState.PROCESSING || state == BuddyState.RESPONDING -> { cancelWork(); finishSpeaking() }
            drawing?.hasContent == true -> drawing?.clear()
            handoff != null -> showWaitingAnswer()
            // Claude app mode, nothing waiting: share just the screenshot.
            state == BuddyState.IDLE && settings.provider == Provider.CLAUDE_APP -> {
                cancelWork()
                setState(BuddyState.PROCESSING)
                startCapture()
                val shot = shotJob!!
                launchWork { handOffToClaude(shot.await().shot, null) }
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
        launchWork { answer(shot.await(), transcript) }
    }

    /** Gemini voice: the clip is transcribed (the screenshot is being taken meanwhile), then answered as usual. */
    private suspend fun handleAudio(clip: VoiceRecording, shot: Deferred<Observation>) {
        val audio = clip.finish() ?: throw IOException(NOT_HEARD)
        val transcript = try {
            GeminiClient(settings) { bubble?.setText(it) }.transcribe(audio)
        } catch (e: EmptyReplyException) {
            throw IOException(NOT_HEARD)
        } catch (e: GeminiExhaustedException) {
            settings.markGeminiVoiceOutToday()   // phone voice until the next Pacific midnight, then back to Gemini
            throw e
        }
        bubble?.setText("heard: ${transcript.take(100)}")
        answer(shot.await(), transcript)
    }

    private suspend fun answer(obs: Observation, transcript: String) {
        if (settings.provider == Provider.CLAUDE_APP) {
            if (looksLikeAction(transcript)) return say("screen control needs claude api mode")
            handOffToClaude(obs.shot, transcript)
        } else act(obs, transcript)
    }

    /** Claude API mode: let Claude look, tap, type and scroll until the request is done, then speak its answer. */
    private suspend fun act(obs: Observation, transcript: String) {
        val text = agent.run(transcript, obs, control) { bubble?.setText(it) }
        drawing?.clear()
        present(text, control.map)
    }

    // region screenshot

    /** Starts looking at the screen for this press (once), while speech recognition is still finishing. */
    private fun startCapture() {
        if (shotJob != null) return
        val forClaudeApp = settings.provider == Provider.CLAUDE_APP
        shotJob = scope.async {
            if (forClaudeApp) {
                // Claude's image pipeline downsizes anything over ~1456 px / ~1.15 MP, which would shift coordinates;
                // sending exactly that size keeps the pixels Claude points at the pixels we draw at.
                Observation(hiddenCapture { captureScreen(1456, 85, 1_150_000) }, "")
            } else control.observe(settle = false)
        }
    }

    /** Hides Buddy's own windows while [block] takes a screenshot. */
    private suspend fun <T> hiddenCapture(block: suspend () -> T): T {
        bubble?.setHiddenForCapture(true)
        drawing?.setSuppressed(true)
        try {
            delay(CAPTURE_DELAY_MS)
            return block()
        } finally {
            bubble?.setHiddenForCapture(false)
            drawing?.setSuppressed(false)
        }
    }

    /** The screen as a JPEG: through accessibility (Android 11+) when it's on, else the MediaProjection. */
    private suspend fun captureScreen(maxEdge: Int, quality: Int, maxPixels: Int = Int.MAX_VALUE): Screenshot {
        val a11y = if (Build.VERSION.SDK_INT >= 30) BuddyAccessibilityService.instance else null
        if (a11y != null) return encodeScreenshot(a11y.frame(), maxEdge, quality, maxPixels)
        return capturer?.capture(maxEdge, quality, maxPixels) ?: throw SpokenException(NO_SCREEN)
    }

    private suspend fun fullFrame(): Bitmap {
        val a11y = if (Build.VERSION.SDK_INT >= 30) BuddyAccessibilityService.instance else null
        return a11y?.frame() ?: capturer?.frame() ?: throw SpokenException(NO_SCREEN)
    }

    /** What the agent's tools need from the overlays. */
    private val stage = object : Stage {
        override suspend fun frame(): Bitmap = hiddenCapture { fullFrame() }
        override suspend fun glide(x: Float, y: Float) { drawing?.glideTo(PointF(x, y), bubble!!.center()) }
        override fun status(text: String) { bubble?.setText(text) }
        override fun bubbleTouchable(on: Boolean) { bubble?.setTouchable(on) }
    }

    // endregion

    // region answers

    /** Claude app mode: open the Claude app with the screenshot (and question) and wait for the answer to come back. */
    private suspend fun handOffToClaude(shot: Screenshot, question: String?) {
        setState(BuddyState.IDLE)
        // Android 15 only lets SYSTEM_ALERT_WINDOW apps start activities from the background while one of their
        // overlay windows is visible, so make sure the bubble is back on screen first.
        delay(OVERLAY_VISIBLE_MS)
        val text = question?.let { handoffText(it, shot.width, shot.height, settings.drawTags) }
        try {
            shareToClaudeApp(shot.jpeg, text)
            handoff = Handoff(shot.width, shot.height, shot.displayWidth, shot.displayHeight, question, text)
            startWaiting()
            showMessage("opened claude. tap send, then tap copy under its answer and tap me", 8000)
        } catch (e: ActivityNotFoundException) {
            if (isClaudeAppInstalled()) {
                showMessage("this version of the claude app can't take shared screenshots")
            } else {
                openClaudeInPlayStore()
                showMessage("install the Claude app first")
            }
        }
    }

    /** The bubble was tapped while an answer is awaited: use a shared answer, else read the clipboard (see ClipboardActivity). */
    private fun showWaitingAnswer() {
        AnswerInbox.take()?.let { return onAnswerText(it) }
        startActivity(
            Intent(this, ClipboardActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION),
        )
    }

    private fun onAnswerText(text: String) {
        val h = handoff
        if (isOwnText(text, h?.copied, h?.question)) {
            return showMessage("tap copy under claude's answer first, then tap me", 6000)
        }
        cancelWork()
        endWaiting()
        present(text, h?.let { ScreenMap(it.shotW, it.shotH, it.displayW, it.displayH) })
    }

    /** Speaks Claude's answer (tags and markdown removed), shows it, and draws what it asked for. */
    private fun present(raw: String, map: ScreenMap?) {
        val answer = parseAnswer(raw)
        var spoken = stripMarkdown(answer.spoken)
        if (answer.shapes.isNotEmpty() && map != null) {
            val (w, h) = displaySize()
            if (map.matches(w, h)) drawing?.play(answer.shapes.map { map.toDisplay(it) }, bubble!!.center())
            else spoken = "$spoken the screen rotated since then, so i can't draw on it.".trim()
        }
        if (spoken.isBlank()) showMessage("no answer came back") else say(spoken)
    }

    private fun startWaiting() {
        bubble?.setWaiting(true)
        waitJob?.cancel()
        waitJob = scope.launch { delay(WAIT_MS); endWaiting() }
    }

    private fun endWaiting() {
        waitJob?.cancel()
        handoff = null
        bubble?.setWaiting(false)
    }

    private fun displaySize(): Pair<Int, Int> {
        val m = DisplayMetrics()
        @Suppress("DEPRECATION")
        (getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(m)
        return m.widthPixels to m.heightPixels
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
        drawing?.clear()
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
        drawing?.speechEnded()
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
            if (settings.provider == Provider.CLAUDE) add(CLAUDE_URL)
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
        // Straight into the invisible clipboard activity (a service trampoline from a notification would be blocked).
        val showAnswer = PendingIntent.getActivity(
            this, 1,
            Intent(this, ClipboardActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_buddy)
            .setContentTitle("Buddy is running")
            .setContentText("Hold the floating bubble to talk")
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(0, "Show Claude's answer", showAnswer)
            .addAction(0, "Stop", stop)
            .build()
    }

    /** The screen share ended on its own (the user ended it, or the system did): say so, since Buddy can't see now. */
    private fun notifyProjectionEnded() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(STOPPED_CHANNEL_ID, "Buddy stopped", NotificationManager.IMPORTANCE_DEFAULT))
        nm.notify(
            STOPPED_NOTIFICATION_ID,
            NotificationCompat.Builder(this, STOPPED_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_buddy)
                .setContentTitle("Buddy stopped: screen sharing ended")
                .setContentText("Tap to start again")
                .setContentIntent(PendingIntent.getActivity(this, 2, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
                .setAutoCancel(true)
                .build(),
        )
    }

    override fun onDestroy() {
        stopping = true
        running.value = false
        scope.cancel()
        recording?.discard()
        if (::phone.isInitialized) phone.destroy()
        if (::speaker.isInitialized) speaker.shutdown()
        bubble?.remove()
        drawing?.remove()
        capturer?.release()
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "dev.buddy.STOP"
        const val ACTION_ANSWER_TEXT = "dev.buddy.ANSWER_TEXT"
        const val EXTRA_TEXT = "text"
        private const val EXTRA_RESULT_CODE = "result_code"
        private const val EXTRA_DATA = "projection_data"
        private const val CHANNEL_ID = "buddy"
        private const val NOTIFICATION_ID = 1
        private const val STOPPED_CHANNEL_ID = "buddy_stopped"
        private const val STOPPED_NOTIFICATION_ID = 2
        private const val WAIT_MS = 10 * 60_000L
        private const val NO_SCREEN = "turn on buddy in accessibility settings, or restart buddy and allow screen sharing"
        private const val CAPTURE_DELAY_MS = 100L
        private const val OVERLAY_VISIBLE_MS = 150L
        private const val GEMINI_URL = "https://generativelanguage.googleapis.com/"
        private const val CLAUDE_URL = "https://api.anthropic.com/"

        val running = MutableStateFlow(false)

        /** [data] is the screen-recording consent; not needed when the accessibility service provides screenshots. */
        fun start(context: Context, resultCode: Int = 0, data: Intent? = null) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, BuddyService::class.java)
                    .putExtra(EXTRA_RESULT_CODE, resultCode)
                    .apply { if (data != null) putExtra(EXTRA_DATA, data) },
            )
        }

        fun stop(context: Context) {
            context.startService(Intent(context, BuddyService::class.java).setAction(ACTION_STOP))
        }
    }
}
