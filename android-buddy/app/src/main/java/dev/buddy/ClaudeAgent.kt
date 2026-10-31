package dev.buddy

import android.os.SystemClock
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/** What Claude sees at the start of a request and after each `screenshot`: the image and the element list. */
class Observation(val shot: Screenshot, val elements: String)

/** The phone as the agent sees it. Throws [ToolError] when an action can't be done. */
interface Phone {
    /** Looks at the screen now; with [settle], first waits for it to stop changing. */
    suspend fun observe(settle: Boolean): Observation
    /** Runs one tool call and returns the blocks of its result (an image + text for screenshots, else just "OK"). */
    suspend fun run(use: ToolUse, status: (String) -> Unit): List<JSONObject>
}

/**
 * Claude API mode as an agent: a non-streaming Messages API loop with the official computer toolset plus open_app and
 * android_nav. The message list is strictly append-only (every response's `content` is echoed back untouched, thinking
 * blocks and signatures included) and survives between holds until it is 10 minutes idle or 40 messages long.
 */
class ClaudeAgent(private val settings: Settings) {
    private var messages = JSONArray()
    private var lastAt = 0L

    suspend fun run(transcript: String, first: Observation, phone: Phone, status: (String) -> Unit): String {
        val now = SystemClock.elapsedRealtime()
        if (now - lastAt > IDLE_MS || messages.length() >= MAX_MESSAGES) messages = JSONArray()
        lastAt = now

        val content = JSONArray()
        // A cancelled request can leave tool calls unanswered; they must be answered before anything else.
        AgentJson.pendingToolUses(messages).forEach {
            content.put(AgentJson.result(it, null, "Cancelled: the user stopped the previous request."))
        }
        content.put(AgentJson.image(first.shot.jpeg))
            .put(AgentJson.text(screenText(first)))
            .put(AgentJson.text(transcript))
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
                    results.put(AgentJson.result(use, phone.run(use, status), null))
                } catch (e: ToolError) {
                    failed = true
                    results.put(AgentJson.result(use, null, e.message ?: "failed"))
                }
            }
            messages.put(AgentJson.user(results))
        }
        throw SpokenException("that took too many steps, so i stopped. tell me where to pick it up")
    }

    private suspend fun post(): JSONObject {
        val model = settings.claudeModel
        val body = AgentJson.request(model, AGENT_PROMPT, messages)
        val request = Request.Builder()
            .url("https://api.anthropic.com/v1/messages")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .header("x-api-key", settings.claudeKey)
            .header("anthropic-version", "2023-06-01")
            .apply { if (AgentJson.usesFallbacks(model)) header("anthropic-beta", "server-side-fallback-2026-07-01") }
            .build()
        try {
            return httpJson(request)
        } catch (e: ApiException) {
            claudeErrorMessage(e.code, e.body)?.let { throw SpokenException(it) }
            throw e
        }
    }

    private fun screenText(o: Observation) =
        "screen 1 (image dimensions: ${o.shot.width}x${o.shot.height} pixels). screen elements, coordinates in " +
            "screenshot pixels:\n${o.elements}"

    private companion object {
        const val MAX_MESSAGES = 40
        const val IDLE_MS = 10 * 60_000L
    }
}
