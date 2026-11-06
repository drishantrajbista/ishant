package dev.buddy

import android.os.SystemClock
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.buffer
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/** What Claude sees at the start of a request and after each `screenshot`: the image and the element list. */
class Observation(val shot: Screenshot, val elements: String, val cropped: Boolean = false)

/** The phone as the agent sees it. Throws [ToolError] when an action can't be done. */
interface Phone {
    /** Looks at the screen now; with [settle], first waits for it to stop changing. */
    suspend fun observe(settle: Boolean): Observation
    /** Runs one tool call and returns the blocks of its result (an image + text for screenshots, else just "OK"). */
    suspend fun run(use: ToolUse, status: (String) -> Unit): List<JSONObject>
    /** Text to append to the batch's last result (the screen's new element list), when the batch changed the screen. */
    suspend fun afterBatch(last: ToolUse): String?
    /** The screen in front, in a few words (the app's name), for saying where a stopped task ended. */
    suspend fun currentScreen(): String? = null
}

/**
 * Claude API mode as an agent: a streaming Messages API loop with the official computer toolset plus Buddy's own tools.
 * Each tool call starts as soon as its block has streamed in, while later blocks are still arriving. The message list is
 * strictly append-only (every response's `content`, rebuilt from the stream exactly as a plain response carries it, is echoed
 * back untouched, thinking blocks and signatures included) and survives between holds until it is 10 minutes idle or 40
 * messages long. If a stream turns out to be unreadable before any tool ran, the turn is repeated as a plain request and plain
 * requests are used from then on.
 */
class ClaudeAgent(private val settings: Settings, private val phoneContext: () -> String = { "" }) {
    private var messages = JSONArray()
    private var lastAt = 0L
    private var effort = "low"   // chosen when a conversation starts and kept for all of its requests
    private var streaming = true  // false after a stream we couldn't read: plain requests from then on

    /** True if the last request ran anything but looking tools: an action, not just an answer. */
    var acted = false
        private set

    suspend fun run(transcript: String, first: Observation, phone: Phone, status: (String) -> Unit): String {
        val now = SystemClock.elapsedRealtime()
        // History is append-only, so every request re-sends every old screenshot: only a quick follow-up keeps it.
        if (!keepContext(lastAt, now, messages.length())) messages = JSONArray()
        lastAt = now
        acted = false
        effort = effortFor(messages.length() == 0, effort, transcript, settings.thinkDeeper)

        val content = JSONArray()
        // The first message of a conversation says what phone this is, what's in front and which default apps to use.
        if (messages.length() == 0) phoneContext().takeIf { it.isNotBlank() }?.let { content.put(AgentJson.text(it)) }
        // A cancelled request can leave tool calls unanswered; they must be answered before anything else.
        AgentJson.pendingToolUses(messages).forEach {
            content.put(AgentJson.result(it, null, "Cancelled: the user stopped the previous request."))
        }
        // Instruction text before the screenshot, as the docs recommend.
        content.put(AgentJson.text(screenText(first)))
            .put(AgentJson.text(transcript))
            .put(AgentJson.image(first.shot.jpeg))
        messages.put(AgentJson.user(content))

        // Where it stopped if the turn cap is hit: the last thing it was doing (never the "thinking" between steps).
        var lastDoing: String? = null
        val track: (String) -> Unit = { if (it != "thinking") lastDoing = it; status(it) }

        repeat(turnCap(settings.claudeModel)) {
            status("thinking")
            val turn = takeTurn(phone, track)
            messages.put(JSONObject().put("role", "assistant").put("content", turn.content))
            lastAt = SystemClock.elapsedRealtime()
            if (turn.acted) acted = true
            if (turn.stopReason == "refusal") throw DeclinedException()
            if (turn.uses.isEmpty()) {
                return AgentJson.textOf(turn.content).ifBlank { throw EmptyReplyException(turn.stopReason) }
            }
            if (!turn.failed) phone.afterBatch(turn.uses.last())?.let { fresh ->
                // Saves a screenshot round trip: the last result also carries the screen's new element list.
                turn.results.getJSONObject(turn.results.length() - 1).getJSONArray("content").put(AgentJson.text(fresh))
            }
            messages.put(AgentJson.user(turn.results))
        }
        throw SpokenException(turnCapMessage(runCatching { phone.currentScreen() }.getOrNull(), lastDoing))
    }

    /** One assistant turn: streamed, with tools running as their blocks complete; a plain request if streaming doesn't work. */
    private suspend fun takeTurn(phone: Phone, status: (String) -> Unit): Turn {
        val model = settings.claudeModel
        if (streaming) {
            val body = AgentJson.request(model, AGENT_PROMPT, messages, effort, stream = true).toString()
            val progress = TurnProgress()
            val t0 = System.nanoTime()
            var toolNanos = 0L
            var code = "200"
            try {
                val events = streamEvents(sseData(messagesRequest(settings.claudeKey, model, body)).buffer(Channel.UNLIMITED))
                return consumeTurn(events, phone, status, progress).also { toolNanos = it.toolNanos }
            } catch (e: StreamFormatException) {
                code = "unreadable stream"
                TimingLog.lastError = "stream: ${e.message}"
                // Tools that already ran can't be replayed; otherwise read plain replies from now on and repeat this turn.
                if (progress.toolsRun > 0) throw SpokenException("i couldn't read claude's reply, so i stopped. check the screen and ask again")
                streaming = false
            } catch (e: ApiException) {
                code = e.code.toString()
                claudeErrorMessage(e.code, e.body)?.let { throw SpokenException(it) }
                throw e
            } catch (e: java.io.IOException) {
                code = e.javaClass.simpleName
                throw e
            } finally {
                TimingLog.record("claude", (System.nanoTime() - t0 - toolNanos) / 1_000_000, requestDetail(body.length / 1024, effort, code, "stream"))
            }
        }
        return consumeTurn(messageEvents(post()), phone, status)
    }

    private suspend fun post(): JSONObject {
        val model = settings.claudeModel
        val text = AgentJson.request(model, AGENT_PROMPT, messages, effort).toString()
        val request = messagesRequest(settings.claudeKey, model, text)
        val t0 = System.nanoTime()
        var status = "200"
        try {
            return httpJson(request)
        } catch (e: ApiException) {
            status = e.code.toString()
            claudeErrorMessage(e.code, e.body)?.let { throw SpokenException(it) }
            throw e
        } catch (e: java.io.IOException) {
            status = e.javaClass.simpleName
            throw e
        } finally {
            TimingLog.record("claude", (System.nanoTime() - t0) / 1_000_000, requestDetail(text.length / 1024, effort, status))
        }
    }

    private fun screenText(o: Observation) = if (o.cropped) {
        "image 1 is a cropped region of the screen that the user chose (${o.shot.width}x${o.shot.height} pixels), not the whole screen. " +
            "its coordinates do not match the display: answer questions about it, and call screenshot before tapping or pointing."
    } else
        "screen 1 (image dimensions: ${o.shot.width}x${o.shot.height} pixels). screen elements, coordinates in " +
            "screenshot pixels:\n${o.elements}"

}

/** The Messages API request, with the headers the agent uses (and the ping in Check setup). */
internal fun messagesRequest(key: String, model: String, body: JSONObject) = messagesRequest(key, model, body.toString())

internal fun messagesRequest(key: String, model: String, body: String): Request = Request.Builder()
    .url("https://api.anthropic.com/v1/messages")
    .post(body.toRequestBody("application/json".toMediaType()))
    .header("x-api-key", key)
    .header("anthropic-version", "2023-06-01")
    .apply { if (AgentJson.usesFallbacks(model)) header("anthropic-beta", "server-side-fallback-2026-07-01") }
    .build()
