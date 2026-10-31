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
import android.os.SystemClock
import android.widget.Toast
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
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
import kotlinx.coroutines.withContext
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
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + CoroutineExceptionHandler { _, e -> reportUncaught(e) })
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
    private val followUp = FollowUp()                // the hands-free follow-up listening window
    private var followJob: Job? = null
    private var replyEligible = false                // the reply being spoken may be followed by a follow-up window
    private var lastLoudAt = 0L
    private var pressEndAt = 0L   // when the finger lifted: the start of the clock for a request
    private var stopping = false   // the user pressed Stop, so a projection that ends is not news

    override fun onBind(intent: Intent?): IBinder? = null

    /** Anything that goes wrong while starting must end in a message and a clean stop, never a crashed process. */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int =
        try { startUp(intent) } catch (e: Throwable) { startFailed(e); START_NOT_STICKY }

    private fun startFailed(e: Throwable) {
        android.util.Log.e("Buddy", "start failed", e)
        TimingLog.lastError = "start failed: ${e.javaClass.simpleName}: ${e.message}".take(200)
        runCatching { CrashLog.write(this, Thread.currentThread(), e) }
        runCatching { Toast.makeText(this, "Buddy couldn't start: ${e.message ?: e.javaClass.simpleName}", Toast.LENGTH_LONG).show() }
        stopping = true
        runCatching { stopSelf() }
    }

    /** A coroutine that ended with an exception nobody caught: say so in the bubble and log it; never kill the process. */
    private fun reportUncaught(e: Throwable) {
        android.util.Log.e("Buddy", "uncaught in a job", e)
        TimingLog.lastError = "${e.javaClass.simpleName}: ${e.message}".take(200)
        runCatching { CrashLog.write(this, Thread.currentThread(), e) }
        runCatching { showMessage("something went wrong: ${e.message ?: e.javaClass.simpleName}".take(120)) }
    }

    private fun startUp(intent: Intent?): Int {
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
            val projection = try {
                (getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager).getMediaProjection(code, data!!)
            } catch (e: RuntimeException) {
                throw IllegalStateException("screen sharing couldn't start (${e.message ?: e.javaClass.simpleName}). start Buddy again")
            }
            capturer = ScreenCapturer(this, projection) {
                if (!stopping) notifyProjectionEnded()
                stopSelf()
            }
        }
        agent = ClaudeAgent(settings)
        control = PhoneControl(this, stage)
        drawing = DrawingOverlay(this).also { it.show() }
        bubble = BubbleOverlay(this, this).also {
            check(it.show()) { "couldn't draw the bubble. allow 'draw over other apps' in settings" }
        }
        speaker = Speaker(this, { settings.speechRate }, { settings.voiceAccent }) { finishSpeaking() }
        phone = PhoneListener(this, this)
        if (projectionNeeded) ScreenProbe.projection = { capturer!!.frame() }
        ready = true
        running.value = true
        // The first few times: say how to talk and how to close, so exiting never depends on discovering the drag.
        if (shouldShowHint(settings.hintsShown)) {
            settings.hintsShown += 1
            showMessage(BUBBLE_HINT, 5000)
        }
        // Once after Start in Claude API mode: check key, accessibility, screen capture and voice, and say what's wrong.
        if (settings.provider == Provider.CLAUDE) scope.launch {
            delay(1500)
            SetupCheck.run(this@BuddyService, settings).firstOrNull { !it.ok }?.let {
                bubble?.setText("setup check: ${it.name.lowercase()}: ${it.detail}")
                textJob?.cancel(); textJob = scope.launch { delay(8000); bubble?.setText(null) }
            }
        }
        return START_NOT_STICKY
    }

    // region touch and push-to-talk

    override fun onTouchDown() {
        if (followUp.isOpen) endFollowUp()   // touching the bubble ends the follow-up window
        warmUp()   // DNS and TLS are done by the time the question is ready
        if (settings.voiceEngine != VoiceEngine.GEMINI) return
        // Gemini voice records from the first instant so the first word isn't clipped; a tap or drag discards it.
        recording?.discard()
        recording = VoiceRecording(this, { bubble?.setLevel(it) }, { onPressEnd() }).takeIf { it.start() }
    }

    override fun onTouchAbort() { discardRecording() }

    override fun onPressCancel() {
        discardRecording()
        cancelWork()   // also stops the phone recognizer
        bubble?.setText(null)
        setState(BuddyState.IDLE)
    }

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
        pressEndAt = SystemClock.elapsedRealtime()
        val clip = recording
        when {
            clip != null -> { recording = null; submitClip(clip) }
            phone.isActive -> {
                setState(BuddyState.PROCESSING)
                startCapture()   // grab the screen while the recognizer finishes
                phone.release()
            }
        }
    }

    /** Sends a recorded clip off: transcribe it while the screen is captured. */
    private fun submitClip(clip: VoiceRecording) {
        setState(BuddyState.PROCESSING)
        startCapture()   // grab the screen while the clip's last word is still being recorded
        val shot = shotJob!!
        launchWork { handleAudio(clip, shot) }
    }

    // region follow-up window (conversation mode)

    /** After a spoken answer: listen ~6 s for a follow-up without the bubble being held. Silence or a tap ends it. */
    private fun openFollowUp() {
        if (!settings.conversationMode || state != BuddyState.IDLE || followUp.isOpen) return
        followUp.open()
        setState(BuddyState.LISTENING)
        bubble?.setText("go ahead…")
        when (settings.voiceEngine) {
            VoiceEngine.PHONE -> if (!phone.start(settings.speechLanguage, handsFree = true)) return endFollowUp()
            VoiceEngine.GEMINI -> {
                if (settings.geminiKey.isBlank()) return endFollowUp()
                lastLoudAt = 0L
                recording = VoiceRecording(this, ::followLevel, { submitFollowUp() }).takeIf { it.start() } ?: return endFollowUp()
            }
        }
        followJob = scope.launch { delay(FOLLOW_UP_LISTEN_MS); if (followUp.timeout()) endFollowUp() }
    }

    /** Gemini voice has no recognizer to say when you're done, so the mic level decides: 1.2 s of quiet after speech. */
    private fun followLevel(level: Float) {
        bubble?.setLevel(level)
        if (!followUp.isOpen) return
        val now = SystemClock.elapsedRealtime()
        if (level > 0.2f) { followUp.speechStarted(); lastLoudAt = now }
        else if (followUp.state == FollowUpState.HEARING && now - lastLoudAt > 1200) submitFollowUp()
    }

    private fun submitFollowUp() {
        val clip = recording ?: return
        recording = null
        followJob?.cancel(); followUp.close()
        pressEndAt = SystemClock.elapsedRealtime()
        submitClip(clip)
    }

    private fun endFollowUp() {
        followJob?.cancel(); followUp.close()
        phone.cancel(); discardRecording()
        if (state == BuddyState.LISTENING) { bubble?.setText(null); setState(BuddyState.IDLE) }
    }

    // endregion

    override fun onTap() {
        when {
            // Stop everything: the request, the speech, the drawing.
            state != BuddyState.IDLE && state != BuddyState.LISTENING -> { cancelWork(); finishSpeaking() }
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
    override fun onReady() { if (!followUp.isOpen) bubble?.buzz() }
    override fun onSpeechStart() = followUp.speechStarted()
    override fun onResult(text: String) {
        if (followUp.isOpen) {
            followJob?.cancel(); followUp.close()
            if (text.isBlank()) { bubble?.setText(null); setState(BuddyState.IDLE); return }   // silence: close quietly
            pressEndAt = SystemClock.elapsedRealtime()
        }
        heard(text)
    }
    override fun onError(message: String) {
        if (followUp.isOpen) { followJob?.cancel(); followUp.close(); bubble?.setText(null); setState(BuddyState.IDLE) }
        else showMessage(message)
    }

    // endregion

    private fun heard(transcript: String) {
        bubble?.setLevel(0f)
        if (transcript.isBlank()) return showMessage(NOT_HEARD)
        bubble?.setText(null)
        setState(BuddyState.PROCESSING)
        TimingLog.record("voice", SystemClock.elapsedRealtime() - pressEndAt, "phone")
        startCapture()   // already running if the finger lifted before the recognizer finished
        val shot = shotJob!!
        launchWork { if (!tryLocal(transcript)) answer(shot.await(), transcript) }
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
        TimingLog.record("voice", SystemClock.elapsedRealtime() - pressEndAt, "gemini")
        if (tryLocal(transcript)) return
        bubble?.setText("heard: ${transcript.take(100)}")
        answer(shot.await(), transcript)
    }

    private suspend fun answer(obs: Observation, transcript: String) {
        try {
            if (settings.provider == Provider.CLAUDE_APP) {
                if (looksLikeAction(transcript)) return say("screen control needs claude api mode. switch it in buddy's settings")
                handOffToClaude(obs.shot, transcript)
            } else act(obs, transcript)
        } finally {
            TimingLog.record("total", SystemClock.elapsedRealtime() - pressEndAt, settings.provider.name)
        }
    }

    /** "What's my day": a short spoken summary from the clock, calendar and notifications that are allowed. */
    private suspend fun speakBriefing(t0: Long) {
        shotJob?.cancel(); shotJob = null
        val events = if (hasCalendarPermission()) withContext(Dispatchers.IO) { runCatching { upcomingEvents(10) }.getOrNull() } else null
        val notifications = if (notificationAccessEnabled()) NotificationStore.entries() else null
        val (battery, charging) = batteryInfo()
        val text = composeBriefing(java.time.ZonedDateTime.now(), battery, charging, events, notifications)
        TimingLog.record("local", SystemClock.elapsedRealtime() - t0, "briefing")
        TimingLog.record("total", SystemClock.elapsedRealtime() - pressEndAt, "local")
        replyEligible = settings.conversationMode
        say(text)
    }

    /** Simple commands ("open YouTube", "flashlight on", "volume up", "go home") run right here, with no Claude call. */
    private suspend fun tryLocal(transcript: String): Boolean {
        val t0 = SystemClock.elapsedRealtime()
        val apps by lazy { launchableApps() }
        val command = routeLocal(transcript) { apps.keys.toList() } ?: return false
        if (command is LocalCommand.Briefing) { speakBriefing(t0); return true }
        val done = when (command) {
            is LocalCommand.Briefing -> null
            is LocalCommand.Alarm -> try { setAlarm(command.hour, command.minute, null); "alarm set for ${clockText(command.hour, command.minute)}" } catch (e: ToolError) { null }
            is LocalCommand.Timer -> try { setTimer(command.seconds, null); "timer set for ${durationText(command.seconds)}" } catch (e: ToolError) { null }
            is LocalCommand.Media -> if (mediaKey(command.action)) "media ${command.action}" else null
            is LocalCommand.OpenApp -> try { launchApp(apps.getValue(command.label)); "opening ${command.label}" } catch (e: RuntimeException) { null }
            is LocalCommand.Nav -> BuddyAccessibilityService.instance?.takeIf { it.nav(command.action) }?.let { command.action.replace('_', ' ') }
            is LocalCommand.Torch -> if (setTorch(command.on)) "flashlight ${if (command.on) "on" else "off"}" else null
            is LocalCommand.Volume -> if (changeVolume(command.action)) "volume ${command.action}" else null
            is LocalCommand.OpenSettings -> try {
                startActivity(Intent(settingsAction(command.page)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); "${command.page} settings"
            } catch (e: Exception) { null }
        } ?: return false   // can't do it here (e.g. accessibility is off): let Claude explain
        TimingLog.record("local", SystemClock.elapsedRealtime() - t0, done)
        TimingLog.record("total", SystemClock.elapsedRealtime() - pressEndAt, "local")
        bubble?.buzz()
        showMessage(done, 1500)
        return true
    }

    /** Claude API mode: let Claude look, tap, type and scroll until the request is done, then speak its answer. */
    private suspend fun act(obs: Observation, transcript: String) {
        val text = agent.run(transcript, obs, control, this::agentStatus)
        drawing?.clear()
        // Open a follow-up window after a spoken answer, not after "done" for an action (no hot-mic surprises).
        replyEligible = shouldOpenFollowUp(settings.conversationMode, true, agent.acted, text)
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
                Observation(TimingLog.time("screen") { hiddenCapture { captureScreen(1456, 85, 1_150_000) } }, "")
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
        override fun status(text: String) = agentStatus(text)
        override fun bubbleTouchable(on: Boolean) { bubble?.setTouchable(on) }
        override fun needAccessibility(message: String) = notifyAccessibilityNeeded(message)
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
        } catch (e: RuntimeException) {
            if (e !is ActivityNotFoundException) { showMessage("couldn't open claude"); return }
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
        try {
            startActivity(
                Intent(this, ClipboardActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION),
            )
        } catch (e: RuntimeException) {
            showMessage("couldn't read the clipboard. tap copy under claude's answer, then try again")
        }
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
                TimingLog.lastError = friendly(e)
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
        followJob?.cancel(); followUp.close(); replyEligible = false
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
        if (replyEligible) { replyEligible = false; openFollowUp() }
    }

    /** What the agent is doing right now: shown in the bubble and on the home screen. */
    private fun agentStatus(text: String) {
        if (text == "thinking") { setState(BuddyState.PROCESSING); bubble?.setText("thinking…") }
        else { setState(BuddyState.ACTING); live.value = "Doing: $text"; bubble?.setText(text) }
    }

    private fun setState(s: BuddyState) {
        state = s
        live.value = when (s) {
            BuddyState.IDLE -> "Ready"
            BuddyState.LISTENING -> "Listening…"
            BuddyState.PROCESSING -> "Thinking…"
            BuddyState.RESPONDING -> "Speaking…"
            BuddyState.ACTING -> live.value
        }
        bubble?.setState(s)
        if (s != BuddyState.LISTENING) bubble?.setLevel(0f)
    }

    private fun showMessage(text: String, ms: Long = 4000) {
        replyEligible = false
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
            .addAction(0, "Close Buddy", stop)
            .build()
    }

    /** The agent needs the accessibility service and it's off or disconnected: bubble text, plus a notification that opens its settings. */
    private fun notifyAccessibilityNeeded(message: String) = runCatching {
        bubble?.setText(message)
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(A11Y_CHANNEL_ID, "Accessibility needed", NotificationManager.IMPORTANCE_DEFAULT))
        nm.notify(
            A11Y_NOTIFICATION_ID,
            NotificationCompat.Builder(this, A11Y_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_buddy)
                .setContentTitle("Buddy can't control the screen")
                .setContentText(message)
                .setContentIntent(PendingIntent.getActivity(this, 3, Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS), PendingIntent.FLAG_IMMUTABLE))
                .setAutoCancel(true)
                .build(),
        )
    }.let { }

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
        runCatching { ScreenProbe.projection = null }
        runCatching { scope.cancel() }
        runCatching { recording?.discard() }
        runCatching { if (::phone.isInitialized) phone.destroy() }
        runCatching { if (::speaker.isInitialized) speaker.shutdown() }
        runCatching { bubble?.remove() }
        runCatching { drawing?.remove() }
        runCatching { capturer?.release() }
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
        private const val A11Y_CHANNEL_ID = "buddy_a11y"
        private const val A11Y_NOTIFICATION_ID = 3
        private const val STOPPED_CHANNEL_ID = "buddy_stopped"
        private const val STOPPED_NOTIFICATION_ID = 2
        private const val WAIT_MS = 10 * 60_000L
        private const val NO_SCREEN = "turn on buddy in accessibility settings, or restart buddy and allow screen sharing"
        private const val FOLLOW_UP_LISTEN_MS = 6500L
        private const val CAPTURE_DELAY_MS = 100L
        private const val OVERLAY_VISIBLE_MS = 150L
        private const val GEMINI_URL = "https://generativelanguage.googleapis.com/"
        private const val CLAUDE_URL = "https://api.anthropic.com/"

        val running = MutableStateFlow(false)

        /** A short live status for the home screen ("Listening…", "Doing: opening YouTube"). */
        val live = MutableStateFlow("Ready")

        /** [data] is the screen-recording consent; not needed when the accessibility service provides screenshots. */
        fun start(context: Context, resultCode: Int = 0, data: Intent? = null) {
            try {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, BuddyService::class.java)
                        .putExtra(EXTRA_RESULT_CODE, resultCode)
                        .apply { if (data != null) putExtra(EXTRA_DATA, data) },
                )
            } catch (e: RuntimeException) {   // e.g. the system refused a foreground service start
                Toast.makeText(context, "Buddy couldn't start: ${e.message ?: e.javaClass.simpleName}", Toast.LENGTH_LONG).show()
            }
        }

        fun stop(context: Context) {
            try { context.startService(Intent(context, BuddyService::class.java).setAction(ACTION_STOP)) } catch (e: RuntimeException) { /* not running */ }
        }
    }
}
