package dev.buddy

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/** Anthropic Messages API with SSE streaming, directly or through a Clicky-style proxy. */
class ClaudeClient(private val settings: Settings) : AiProvider {
    override fun stream(
        systemPrompt: String,
        history: List<Pair<String, String>>,
        screenshot: Screenshot,
        question: Question,
    ): Flow<String> {
        val transcript = requireNotNull(question.transcript) { "Claude takes text questions only" }
        val messages = JSONArray()
        history.forEach { (user, assistant) ->
            messages.put(JSONObject().put("role", "user").put("content", user))
            messages.put(JSONObject().put("role", "assistant").put("content", assistant))
        }
        val image = JSONObject().put("type", "image").put(
            "source",
            JSONObject().put("type", "base64").put("media_type", "image/jpeg").put("data", screenshot.jpegBase64),
        )
        messages.put(
            JSONObject().put("role", "user").put(
                "content",
                JSONArray().put(image)
                    .put(JSONObject().put("type", "text").put("text", screenLabel(screenshot)))
                    .put(JSONObject().put("type", "text").put("text", transcript)),
            ),
        )
        val model = settings.claudeModel
        val proxy = settings.proxyUrl.trimEnd('/')
        val direct = proxy.isEmpty()
        // Thinking tokens count against max_tokens, so leave room; "low" effort keeps the thinking short.
        // (No thinking/temperature/top_p/top_k: thinking can't be switched off on Sonnet or Opus without a 400.)
        val body = JSONObject()
            .put("model", model)
            .put("max_tokens", 4096)
            .put("stream", true)
            .put("output_config", JSONObject().put("effort", "low"))
            .put("system", systemPrompt)
            .put("messages", messages)
        // Server-side fallback: direct mode only (Clicky's worker doesn't forward beta headers), not on Haiku.
        val serverFallback = direct && model in FALLBACK_MODELS
        if (serverFallback) body.put("fallbacks", "default")

        val request = Request.Builder()
            .url(if (direct) "https://api.anthropic.com/v1/messages" else "$proxy/chat")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .apply {
                if (direct) {
                    header("x-api-key", settings.claudeKey)
                    header("anthropic-version", "2023-06-01")
                    if (serverFallback) header("anthropic-beta", "server-side-fallback-2026-07-01")
                }
            }
            .build()

        return flow {
            var emitted = false
            var stopReason: String? = null
            sseData(request).collect { data ->
                val json = JSONObject(data)
                when (json.optString("type")) {
                    // Only text_delta is spoken; thinking_delta blocks are ignored.
                    "content_block_delta" -> json.optJSONObject("delta")
                        ?.takeIf { it.optString("type") == "text_delta" }
                        ?.let { emitted = true; emit(it.getString("text")) }
                    "message_delta" -> json.optJSONObject("delta")?.str("stop_reason")?.let { stopReason = it }
                    "error" -> throw IOException(json.optJSONObject("error")?.optString("message") ?: "stream error")
                }
            }
            if (stopReason == "refusal") throw DeclinedException()
            if (!emitted) throw EmptyReplyException(stopReason)
        }
    }

    private companion object {
        val FALLBACK_MODELS = setOf("claude-sonnet-5-5", "claude-opus-5-5")
    }
}
