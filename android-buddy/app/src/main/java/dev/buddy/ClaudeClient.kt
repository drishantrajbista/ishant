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
        transcript: String,
    ): Flow<String> {
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
        val body = JSONObject()
            .put("model", settings.claudeModel)
            .put("max_tokens", 1024)
            .put("stream", true)
            .put("system", systemPrompt)
            .put("messages", messages)

        val proxy = settings.proxyUrl.trimEnd('/')
        val request = Request.Builder()
            .url(if (proxy.isNotEmpty()) "$proxy/chat" else "https://api.anthropic.com/v1/messages")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .apply {
                if (proxy.isEmpty()) {
                    header("x-api-key", settings.claudeKey)
                    header("anthropic-version", "2023-06-01")
                }
            }
            .build()

        return flow {
            sseData(request).collect { data ->
                val json = JSONObject(data)
                when (json.optString("type")) {
                    "content_block_delta" -> json.optJSONObject("delta")
                        ?.takeIf { it.optString("type") == "text_delta" }
                        ?.let { emit(it.getString("text")) }
                    "error" -> throw IOException(json.optJSONObject("error")?.optString("message") ?: "stream error")
                }
            }
        }
    }
}
