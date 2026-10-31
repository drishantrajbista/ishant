package dev.buddy

import android.os.SystemClock
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
}

/**
 * Claude API mode as an agent: a non-streaming Messages API loop with the official computer toolset plus open_app and
 * android_nav. The message list is strictly append-only (every response's `content` is echoed back untouched, thinking
 * blocks and signatures included) and survives between holds until it is 10 minutes idle or 40 messages long.
 */
class ClaudeAgent(private val settings: Settings, private val phoneContext: () -> String = { "" }) {
    private var messages = JSONArray()
    private var lastAt = 0L
    private var effort = "low"   // chosen when a conversation starts and kept for all of its requests

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

        repeat(turnCap(settings.claudeModel)) {
            status("thinking")
            val response = post()
            val reply = response.getJSONArray("content")
            messages.put(JSONObject().put("role", "assistant").put("content", reply))
            lastAt = SystemClock.elapsedRealtime()
            val stop = response.str("stop_reason")
            if (stop == "refusal") throw DeclinedException()
            val uses = AgentJson.toolUses(reply)
            if (uses.isEmpty()) {
                return AgentJson.textOf(reply).ifBlank { throw EmptyReplyException(stop) }
            }
            val results = JSONArray()
            var failed = false
            for (use in uses) {
                if (failed) { results.put(AgentJson.result(use, null, AgentJson.HALT)); continue }
                try {
                    if (isActionTool(use.name)) acted = true
                    results.put(AgentJson.result(use, phone.run(use, status), null))
                } catch (e: ToolError) {
                    failed = true
                    results.put(AgentJson.result(use, null, e.message ?: "failed"))
                }
            }
            if (!failed) phone.afterBatch(uses.last())?.let { fresh ->
                // Saves a screenshot round trip: the last result also carries the screen's new element list.
                results.getJSONObject(results.length() - 1).getJSONArray("content").put(AgentJson.text(fresh))
            }
            messages.put(AgentJson.user(results))
        }
        throw SpokenException("that took too many steps, so i stopped. tell me where to pick it up")
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
