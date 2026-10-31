package dev.buddy

import android.util.Base64
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * Google Gemini (free tier from AI Studio): streaming chat with a screenshot, plus audio transcription. Requests go
 * through [ModelFallback], which copes with thinking configs and free-tier limits; [notify] receives the short
 * status lines it wants shown ("busy, retrying in 13s", "switched to ...") and null to clear them.
 */
class GeminiClient(
    private val settings: Settings,
    private val notify: (String?) -> Unit = {},
) : AiProvider {
    private fun fallback() = ModelFallback(settings, { cachedModels() }, notify)

    override fun stream(
        systemPrompt: String,
        history: List<Pair<String, String>>,
        screenshot: Screenshot,
        question: Question,
    ): Flow<String> {
        val contents = JSONArray()
        history.forEach { (user, assistant) ->
            contents.put(content("user", text(user)))
            contents.put(content("model", text(assistant)))
        }
        val ask = question.audio?.let { inlineData("audio/aac", it) } ?: text(question.transcript.orEmpty())
        contents.put(content("user", inlineData("image/jpeg", screenshot.jpeg), text(screenLabel(screenshot)), ask))
        val preferred = listOf(settings.geminiModel.removePrefix("models/"), LITE_LATEST)

        return flow {
            var emitted = false
            var finish: String? = null
            var blocked: String? = null
            fallback().run(preferred, started = { emitted }) { model, thinking ->
                val body = JSONObject()
                    .put("systemInstruction", JSONObject().put("parts", JSONArray().put(text(systemPrompt))))
                    .put("contents", contents)
                    .put("generationConfig", config(2048, thinking))
                sseData(request("streamGenerateContent", model, body, sse = true)).collect { data ->
                    val chunk = parseChunk(JSONObject(data))
                    chunk.finish?.let { finish = it }
                    chunk.blocked?.let { blocked = it }
                    if (chunk.text.isNotEmpty()) { emitted = true; emit(chunk.text) }
                }
            }
            if (!emitted) throw EmptyReplyException(blocked ?: finish)
        }
    }

    /**
     * One non-streaming call that turns an AAC clip into text, whatever language(s) it is in. Transcription doesn't
     * need a big model, so it starts on the Flash-Lite alias whatever the answer model is.
     */
    suspend fun transcribe(audio: ByteArray): String {
        val contents = JSONArray().put(content("user", text(TRANSCRIBE_PROMPT), inlineData("audio/aac", audio)))
        val json = fallback().run(listOf(LITE_LATEST)) { model, thinking ->
            val body = JSONObject().put("contents", contents).put("generationConfig", config(512, thinking))
            httpJson(request("generateContent", model, body))
        }
        val chunk = parseChunk(json)
        if (chunk.text.isBlank()) throw EmptyReplyException(chunk.blocked ?: chunk.finish)
        return chunk.text.trim()
    }

    private suspend fun cachedModels(): List<String> = listed ?: listModels(settings.geminiKey).also { listed = it }

    private fun request(method: String, model: String, body: JSONObject, sse: Boolean = false) = Request.Builder()
        .url("$BASE/models/$model:$method" + if (sse) "?alt=sse" else "")
        .header("x-goog-api-key", settings.geminiKey)
        .post(body.toString().toRequestBody("application/json".toMediaType()))
        .build()

    private fun config(maxOutputTokens: Int, thinking: String?) =
        JSONObject().put("maxOutputTokens", maxOutputTokens).apply { thinking?.let { put("thinkingConfig", JSONObject(it)) } }

    private fun content(role: String, vararg parts: JSONObject) = JSONObject().put("role", role).put("parts", JSONArray(parts.toList()))
    private fun text(t: String) = JSONObject().put("text", t)
    private fun inlineData(mime: String, bytes: ByteArray) = JSONObject().put(
        "inline_data",
        JSONObject().put("mime_type", mime).put("data", Base64.encodeToString(bytes, Base64.NO_WRAP)),
    )

    private class Chunk(val text: String, val finish: String?, val blocked: String?)

    private fun parseChunk(json: JSONObject): Chunk {
        val candidate = json.optJSONArray("candidates")?.optJSONObject(0)
        val parts = candidate?.optJSONObject("content")?.optJSONArray("parts")
        val text = StringBuilder()
        if (parts != null) for (i in 0 until parts.length()) {
            val part = parts.getJSONObject(i)
            if (!part.optBoolean("thought")) text.append(part.optString("text"))
        }
        return Chunk(text.toString(), candidate?.str("finishReason"), json.optJSONObject("promptFeedback")?.str("blockReason"))
    }

    companion object {
        private const val BASE = "https://generativelanguage.googleapis.com/v1beta"
        private const val TRANSCRIBE_PROMPT = "Transcribe this audio exactly as spoken. The speaker may use any language or " +
            "mix languages; write each word in its own language and normal script. Output only the transcript, nothing else."

        @Volatile private var listed: List<String>? = null

        /** Model ids (without the "models/" prefix) that support generateContent. */
        suspend fun listModels(apiKey: String): List<String> {
            val json = httpJson(
                Request.Builder().url("$BASE/models?pageSize=1000").header("x-goog-api-key", apiKey).build(),
            )
            val models = json.optJSONArray("models") ?: JSONArray()
            return (0 until models.length()).map { models.getJSONObject(it) }
                .filter { m ->
                    m.optJSONArray("supportedGenerationMethods")
                        ?.let { a -> (0 until a.length()).any { a.getString(it) == "generateContent" } } == true
                }
                .map { it.getString("name").removePrefix("models/") }
        }
    }
}
