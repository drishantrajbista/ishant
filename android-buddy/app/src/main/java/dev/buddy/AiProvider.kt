package dev.buddy

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
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

class ApiException(val code: Int, message: String, val body: String = "") : IOException(message)

/** An error whose message is shown in the bubble and also spoken. */
open class SpokenException(message: String) : IOException(message)

/** The model finished without saying anything; [reason] is its finish/stop/block reason. */
class EmptyReplyException(reason: String?) :
    IOException("no answer came back" + (reason?.let { " ($it)" } ?: ""))

/** Claude refused. */
class DeclinedException : SpokenException("claude declined to answer that")

/** Claude API mode: keeps the last ~10 turns (text only; the screenshot goes with the latest turn only). */
class Conversation {
    private val history = ArrayDeque<Pair<String, String>>()

    suspend fun ask(client: ClaudeClient, transcript: String, shot: Screenshot, onText: (String) -> Unit = {}): String {
        val full = StringBuilder()
        client.stream(SYSTEM_PROMPT, history.toList(), shot, transcript).collect {
            full.append(it)
            onText(full.toString())
        }
        val reply = full.toString()
        history.addLast(transcript to reply)
        while (history.size > MAX_TURNS) history.removeFirst()
        return reply
    }

    private companion object { const val MAX_TURNS = 10 }
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
internal fun sseData(request: Request) = channelFlow {
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
