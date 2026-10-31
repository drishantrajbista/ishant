package dev.buddy

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/**
 * Speaks a reply. [onFinished] fires on the main thread once everything queued has been spoken. Text containing
 * Devanagari is read with a Nepali (or Hindi) voice.
 */
class Speaker(context: Context, private val rate: () -> Float, private val onFinished: () -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private var ready = false
    private var devanagari = false   // which voice is currently set
    private var utterances = 0
    private var streamDone = false
    private val live = HashSet<String>()   // queued utterance ids that haven't finished

    private val listener = object : UtteranceProgressListener() {
        override fun onStart(id: String?) {}
        override fun onDone(id: String?) = ended(id)
        @Deprecated("Deprecated in Java")
        override fun onError(id: String?) = ended(id)
        override fun onStop(id: String?, interrupted: Boolean) = ended(id)
    }

    init {
        tts = TextToSpeech(context) { status ->
            ready = status == TextToSpeech.SUCCESS
            if (ready) tts?.language = Locale.getDefault()
        }.also { it.setOnUtteranceProgressListener(listener) }
    }

    /** Begins a new reply, dropping whatever was still being spoken. */
    fun start() = stop()

    /** Speaks [reply] (tags and markdown removed) and finishes once it has all been said. */
    fun end(reply: String) {
        enqueue(reply)
        streamDone = true
        if (ready) finishIfIdle() else main.postDelayed({ finishIfIdle() }, READ_MS)  // no voice: leave the text up
    }

    fun stop() {
        main.removeCallbacksAndMessages(null)
        live.clear()
        streamDone = false
        try { tts?.stop() } catch (e: RuntimeException) { /* engine gone */ }
    }

    fun shutdown() {
        stop()
        try { tts?.shutdown() } catch (e: RuntimeException) { /* engine gone */ }
        tts = null
    }

    private fun enqueue(text: String) {
        val engine = tts ?: return
        if (!ready) return
        try { speakPieces(engine, text) } catch (e: RuntimeException) { android.util.Log.w("Buddy", "tts", e) }
    }

    private fun speakPieces(engine: TextToSpeech, text: String) {
        for (piece in stripMarkdown(removeTags(text)).chunked(TextToSpeech.getMaxSpeechInputLength() - 1)) {
            useVoiceFor(engine, piece)
            engine.setSpeechRate(rate())
            val id = "buddy-${utterances++}"
            if (engine.speak(piece, TextToSpeech.QUEUE_ADD, null, id) == TextToSpeech.SUCCESS) live.add(id)
        }
    }

    /** Devanagari text gets a Nepali voice (Hindi if that isn't installed); everything else the device default. */
    private fun useVoiceFor(engine: TextToSpeech, text: String) {
        val wantDevanagari = text.any { it in 'ऀ'..'ॿ' }
        if (wantDevanagari == devanagari) return
        devanagari = wantDevanagari
        if (!wantDevanagari) {
            engine.language = Locale.getDefault()
        } else if (engine.setLanguage(Locale.forLanguageTag("ne-NP")) < TextToSpeech.LANG_AVAILABLE) {
            engine.setLanguage(Locale.forLanguageTag("hi-IN"))
        }
    }

    private fun ended(id: String?) {
        main.post { if (id != null && live.remove(id)) finishIfIdle() }
    }

    private fun finishIfIdle() {
        if (streamDone && live.isEmpty()) {
            streamDone = false
            onFinished()
        }
    }

    private companion object {
        const val READ_MS = 6000L
    }
}

