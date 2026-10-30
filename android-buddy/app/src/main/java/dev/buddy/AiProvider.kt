package dev.buddy

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.job
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

/** A vision-capable chat model that streams its reply as text chunks. */
interface AiProvider {
    /** [history] is (what the user said, what the assistant replied); images only go with the latest turn. */
    fun stream(
        systemPrompt: String,
        history: List<Pair<String, String>>,
        screenshot: Screenshot,
        transcript: String,
    ): Flow<String>
}

fun screenLabel(shot: Screenshot) = "screen 1 (image dimensions: ${shot.width}x${shot.height} pixels)"

/** Keeps the last ~10 turns and hands them to whichever provider is active. Shared by both providers. */
class Conversation {
    private val history = ArrayDeque<Pair<String, String>>()

    suspend fun ask(
        provider: AiProvider,
        transcript: String,
        shot: Screenshot,
        onText: (String) -> Unit = {},
    ): String {
        val full = StringBuilder()
        provider.stream(SYSTEM_PROMPT, history.toList(), shot, transcript).collect {
            full.append(it); onText(full.toString())
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

/** Executes [request] and emits the payload of every SSE `data:` line. Cancelling the collector cancels the call. */
internal fun sseData(request: Request, rateLimitMessage: String? = null): Flow<String> = flow {
    val call = sharedHttp.newCall(request)
    coroutineContext.job.invokeOnCompletion { if (it != null) call.cancel() }
    call.execute().use { response ->
        if (!response.isSuccessful) {
            if (response.code == 429 && rateLimitMessage != null) throw IOException(rateLimitMessage)
            throw IOException("API error ${response.code}: ${response.body?.string()?.take(300)}")
        }
        val source = response.body!!.source()
        while (true) {
            val line = source.readUtf8Line() ?: break
            if (line.startsWith("data:")) line.removePrefix("data:").trim().takeIf { it.isNotEmpty() }?.let { emit(it) }
        }
    }
}.flowOn(Dispatchers.IO)
