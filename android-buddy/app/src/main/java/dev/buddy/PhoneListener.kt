package dev.buddy

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * The phone's own recognizer for one push-to-talk press. Recognizers end a session at the first pause, so while
 * the finger is still down we restart listening and keep the segments; the final text is all of them joined.
 */
class PhoneListener(private val context: Context, private val events: Events) {
    interface Events {
        fun onLevel(level: Float)
        /** Everything heard so far (partial), for the bubble. */
        fun onText(text: String)
        /** The recognizer is listening (first time per press): the user can start talking. */
        fun onReady()
        /** The user began talking (used to give a hands-free follow-up window its full length). */
        fun onSpeechStart() {}
        /** The press is over. [text] is everything heard, "" if nothing. */
        fun onResult(text: String)
        fun onError(message: String)
    }

    private val handler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var language = ""
    private var fingerDown = false
    private var running = false    // the recognizer has an open session
    private var buzzed = false
    private val segments = mutableListOf<String>()
    private var partial = ""

    /** True from [start] until the result (or an error) has been delivered. */
    var isActive = false
        private set

    /** [handsFree]: no finger is held, so the first recognizer session is the whole utterance. */
    fun start(language: String, handsFree: Boolean = false): Boolean {
        val r = recognizer ?: create() ?: return false
        this.language = language
        isActive = true; fingerDown = !handsFree; buzzed = false
        segments.clear()
        listen(r)
        return true
    }

    /** The finger lifted: let the recognizer finish; the joined text arrives via [Events.onResult]. */
    fun release() {
        if (!isActive) return
        fingerDown = false
        if (running) {
            recognizer?.stopListening()
            handler.postDelayed(giveUp, FINISH_TIMEOUT_MS)
        } else finish()
    }

    fun cancel() {
        handler.removeCallbacksAndMessages(null)
        if (running) recognizer?.cancel()
        isActive = false; running = false; partial = ""
    }

    fun destroy() {
        cancel()
        recognizer?.destroy()
        recognizer = null
    }

    private fun create(): SpeechRecognizer? {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) return null
        return SpeechRecognizer.createSpeechRecognizer(context).also {
            it.setRecognitionListener(listener)
            recognizer = it
        }
    }

    private fun listen(r: SpeechRecognizer) {
        partial = ""
        running = true
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            // Hints to wait longer before deciding the sentence is over (not every recognizer honours them).
            .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 3000L)
            .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 3000L)
        val tag = recognizerLanguage(language)
        if (tag.isNotBlank()) {
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, tag).putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, tag)
        }
        if (language == AUTO_LANGUAGE && Build.VERSION.SDK_INT >= 34) {
            // Mixed Nepali/Hindi/English: let the recognizer detect and follow the language as you switch.
            intent.putExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_DETECTION, true)
                .putStringArrayListExtra(RecognizerIntent.EXTRA_LANGUAGE_DETECTION_ALLOWED_LANGUAGES, ArrayList(AUTO_LANGUAGES))
                .putExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_SWITCH, RecognizerIntent.LANGUAGE_SWITCH_BALANCED)
        }
        r.startListening(intent)
    }

    /** A recognizer session ended with [text] (possibly blank). Keep it and carry on, or finish if the finger is up. */
    private fun sessionEnded(text: String) {
        running = false
        if (!isActive) return
        if (text.isNotBlank()) segments += text.trim()
        partial = ""
        if (fingerDown) {
            handler.postDelayed({ if (isActive && fingerDown) recognizer?.let(::listen) }, RESTART_DELAY_MS)
        } else finish()
    }

    private fun finish() {
        handler.removeCallbacksAndMessages(null)
        isActive = false
        events.onResult(segments.joinToString(" "))
    }

    private val giveUp = Runnable {
        if (!isActive) return@Runnable
        recognizer?.cancel()
        running = false
        if (partial.isNotBlank()) segments += partial.trim()
        finish()
    }

    private fun joined(last: String) = (segments + last).filter { it.isNotBlank() }.joinToString(" ")

    private fun first(b: Bundle?) =
        b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.takeIf { it.isNotBlank() }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            if (!buzzed) { buzzed = true; events.onReady() }
        }
        override fun onRmsChanged(rmsdB: Float) = events.onLevel((rmsdB + 2f) / 12f)
        override fun onPartialResults(partialResults: Bundle?) {
            first(partialResults)?.let { partial = it; events.onText(joined(it)) }
        }
        override fun onResults(results: Bundle?) = sessionEnded(first(results) ?: partial)
        override fun onError(error: Int) {
            when (error) {
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> sessionEnded(partial)
                else -> {
                    running = false
                    if (!isActive) return
                    recognizer?.destroy(); recognizer = null   // a broken session: start fresh next time
                    if (segments.isNotEmpty() || partial.isNotBlank()) {
                        if (partial.isNotBlank()) segments += partial.trim()
                        finish()
                    } else {
                        isActive = false
                        events.onError("speech recognition failed (error $error)")
                    }
                }
            }
        }
        override fun onBeginningOfSpeech() = events.onSpeechStart()
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    private companion object {
        const val RESTART_DELAY_MS = 100L
        const val FINISH_TIMEOUT_MS = 5000L
    }
}
