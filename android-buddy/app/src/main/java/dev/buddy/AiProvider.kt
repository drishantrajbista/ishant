package dev.buddy

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

const val NOT_HEARD = "i didn't catch that"

/** What the user asked: words (from the phone's recognizer or a transcription), or a raw AAC clip for Gemini. */
class Question(val transcript: String? = null, val audio: ByteArray? = null)

/** A vision-capable chat model that streams its reply as text chunks. */
interface AiProvider {
    /** [history] is (what the user said, what the assistant replied); images only go with the latest turn. */
    fun stream(
        systemPrompt: String,
        history: List<Pair<String, String>>,
        screenshot: Screenshot,
        question: Question,
    ): Flow<String>
}

fun screenLabel(shot: Screenshot) = "screen 1 (image dimensions: ${shot.width}x${shot.height} pixels)"

class ApiException(val code: Int, message: String, val body: String = "") : IOException(message)

/** An error whose message is shown in the bubble and also spoken. */
open class SpokenException(message: String) : IOException(message)

/** The model finished without saying anything; [reason] is its finish/stop/block reason. */
class EmptyReplyException(reason: String?) :
    IOException("no answer came back" + (reason?.let { " ($it)" } ?: ""))

/** Claude refused; this message is shown and spoken. */
class DeclinedException : SpokenException("claude declined to answer that")

/** The reply text with the `[HEARD: ...]` tag removed, plus what the model says it heard (voice requests). */
class Reply(val text: String, val heard: String?)

/** Keeps the last ~10 turns and hands them to whichever provider is active. Shared by both providers. */
class Conversation {
    private val history = ArrayDeque<Pair<String, String>>()

    suspend fun ask(
        provider: AiProvider,
        question: Question,
        shot: Screenshot,
        onHeard: (String) -> Unit = {},
        onText: (String) -> Unit = {},
    ): Reply {
        val voice = question.audio != null
        val parser = if (voice) HeardParser() else null
        val full = StringBuilder()
        var heardReported = false

        fun deliver(final: Boolean) {
            if (parser == null) return onText(full.toString())
            val body = parser.feed(full.toString(), final) ?: return
            parser.heard?.takeIf { !heardReported }?.let { heardReported = true; onHeard(it) }
            onText(body)
        }

        val system = if (voice) SYSTEM_PROMPT + AUDIO_ADDENDUM else SYSTEM_PROMPT
        provider.stream(system, history.toList(), shot, question).collect {
            full.append(it)
            deliver(final = false)
        }
        if (parser != null && !parser.decided) deliver(final = true)

        val raw = full.toString()
        // Keep the raw reply (tag included) so later turns stay in the same format.
        history.addLast((question.transcript ?: parser?.heard ?: "(voice message)") to raw)
        while (history.size > MAX_TURNS) history.removeFirst()
        return Reply(parser?.feed(raw, final = true) ?: raw, parser?.heard)
    }

    private companion object { const val MAX_TURNS = 10 }
}

/**
 * Splits a leading `[HEARD: ...]` tag off a streamed reply. Nothing is released until we know whether the reply
 * starts with the tag; once the closing bracket has arrived the rest of the reply flows through untouched.
 */
class HeardParser {
    var heard: String? = null
        private set
    private var bodyStart = -1
    val decided get() = bodyStart >= 0

    /** The reply without the tag, or null while it is still unclear whether a tag is coming. */
    fun feed(full: String, final: Boolean): String? {
        if (!decided) {
            val lead = full.length - full.trimStart().length
            val rest = full.substring(lead)
            when {
                rest.startsWith(TAG, ignoreCase = true) -> {
                    val close = closingBracket(rest)
                    when {
                        close >= 0 -> { heard = rest.substring(TAG.length, close).trim(); bodyStart = lead + close + 1 }
                        rest.length > MAX_TAG_CHARS || final -> bodyStart = 0   // never closed: treat as absent
                        else -> return null
                    }
                }
                !final && rest.length < TAG.length && TAG.startsWith(rest, ignoreCase = true) -> return null
                else -> bodyStart = 0
            }
        }
        return full.substring(bodyStart).trimStart()
    }

    private fun closingBracket(s: String): Int {
        var depth = 0
        for (i in s.indices) {
            if (s[i] == '[') depth++ else if (s[i] == ']' && --depth == 0) return i
        }
        return -1
    }

    private companion object {
        const val TAG = "[HEARD:"
        // The tag holds the whole question, which can be long; this only stops a never-closed tag from buffering forever.
        const val MAX_TAG_CHARS = 1200
    }
}

internal val sharedHttp: OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(20, TimeUnit.SECONDS)
    .readTimeout(90, TimeUnit.SECONDS)
    .build()

internal fun JSONObject.str(key: String): String? = if (isNull(key)) null else optString(key).takeIf { it.isNotEmpty() }

/** Keeps the provider's own message (first 150 chars) so the bubble shows what really went wrong. */
private fun apiError(r: Response): ApiException {
    val raw = r.body?.string().orEmpty()
    val detail = try { JSONObject(raw).getJSONObject("error").getString("message") } catch (e: Exception) { raw }
    return ApiException(r.code, "API error ${r.code}: ${detail.replace(Regex("\\s+"), " ").trim().take(150)}", raw)
}

/** Executes [request] and emits the payload of every SSE `data:` line. Cancelling the collector cancels the call. */
internal fun sseData(request: Request): Flow<String> = channelFlow {
    val call = sharedHttp.newCall(request)
    // A blocking socket read can't be cancelled by coroutines, so close the call the moment the collector goes away.
    val closer = launch(Dispatchers.IO) { try { awaitCancellation() } finally { call.cancel() } }
    try {
        withContext(Dispatchers.IO) {
            call.execute().use { response ->
                if (!response.isSuccessful) throw apiError(response)
                val source = response.body!!.source()
                while (true) {
                    val line = source.readUtf8Line() ?: break
                    if (line.startsWith("data:")) line.removePrefix("data:").trim().takeIf { it.isNotEmpty() }?.let { send(it) }
                }
            }
        }
    } finally {
        closer.cancel()
    }
}

/** A plain (non-streaming) request whose response is a JSON object. Cancellable. */
internal suspend fun httpJson(request: Request): JSONObject {
    val call = sharedHttp.newCall(request)
    val response = suspendCancellableCoroutine<Response> { cont ->
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onResponse(call: Call, response: Response) {
                if (cont.isActive) cont.resume(response) else response.close()
            }
            override fun onFailure(call: Call, e: IOException) = cont.resumeWithException(e)
        })
    }
    return withContext(Dispatchers.IO) {
        response.use {
            if (!it.isSuccessful) throw apiError(it)
            JSONObject(it.body!!.string())
        }
    }
}
