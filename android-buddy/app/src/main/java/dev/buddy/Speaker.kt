package dev.buddy

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/**
 * Speaks a reply while it is still streaming in: complete sentences are queued as they arrive and the rest is queued
 * when the stream ends. [onFinished] fires on the main thread once the stream is done and everything queued has been
 * spoken. Chunks containing Devanagari are read with a Nepali (or Hindi) voice.
 */
class Speaker(context: Context, private val onFinished: () -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private var ready = false
    private var devanagari = false   // which voice is currently set
    private var utterances = 0
    private var spokenUpTo = 0       // how much of the reply has been queued already
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

    /**
     * [reply] is everything received so far. Queues the sentences that have completed since last time and returns
     * the part of the reply that is safe to show (everything before the first `[`, where the POINT tag begins).
     */
    fun onText(reply: String): String {
        val visible = reply.substringBefore('[')
        val end = sentenceEnd(visible, spokenUpTo)
        if (end > spokenUpTo) {
            enqueue(visible.substring(spokenUpTo, end))
            spokenUpTo = end
        }
        return visible
    }

    /** The stream is complete: speak what is left (POINT tag stripped) and finish once it has all been said. */
    fun end(reply: String) {
        enqueue(reply.substring(spokenUpTo.coerceAtMost(reply.length)))
        streamDone = true
        if (ready) finishIfIdle() else main.postDelayed({ finishIfIdle() }, READ_MS)  // no voice: leave the text up
    }

    fun stop() {
        main.removeCallbacksAndMessages(null)
        live.clear()
        spokenUpTo = 0
        streamDone = false
        tts?.stop()
    }

    fun shutdown() {
        stop()
        tts?.shutdown()
        tts = null
    }

    private fun enqueue(text: String) {
        val engine = tts ?: return
        if (!ready) return
        for (piece in stripMarkdown(removeTags(text)).chunked(TextToSpeech.getMaxSpeechInputLength() - 1)) {
            useVoiceFor(engine, piece)
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

/** Index just past the last complete sentence after [from]: `.`/`!`/`?` followed by whitespace, or a newline. */
internal fun sentenceEnd(text: String, from: Int): Int {
    var end = from
    for (i in from until text.length) {
        if (text[i] == '\n' || (i > from && text[i - 1] in ".!?" && text[i].isWhitespace())) end = i + 1
    }
    return end
}
