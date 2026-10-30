package dev.buddy

import android.util.Base64
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/** Google Gemini (free tier from AI Studio): streaming chat with a screenshot, plus audio transcription. */
class GeminiClient(private val settings: Settings) : AiProvider {
    private val model get() = settings.geminiModel.removePrefix("models/")

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
        val limitMessage = if (question.audio != null) VOICE_LIMIT_MESSAGE else RATE_LIMIT_MESSAGE
        val model = this.model

        return flow {
            var emitted = false
            var finish: String? = null
            var blocked: String? = null
            thinkingFallback(model, started = { emitted }) { thinking ->
                val body = JSONObject()
                    .put("systemInstruction", JSONObject().put("parts", JSONArray().put(text(systemPrompt))))
                    .put("contents", contents)
                    .put("generationConfig", config(2048, thinking))
                sseData(request("streamGenerateContent", model, body, sse = true), limitMessage).collect { data ->
                    val chunk = parseChunk(JSONObject(data))
                    chunk.finish?.let { finish = it }
                    chunk.blocked?.let { blocked = it }
                    if (chunk.text.isNotEmpty()) { emitted = true; emit(chunk.text) }
                }
            }
            if (!emitted) throw EmptyReplyException(blocked ?: finish)
        }
    }

    /** One non-streaming call that turns an AAC clip into text, whatever language(s) it is in. */
    suspend fun transcribe(audio: ByteArray): String {
        val model = this.model
        val contents = JSONArray().put(content("user", text(TRANSCRIBE_PROMPT), inlineData("audio/aac", audio)))
        val chunk = parseChunk(thinkingFallback(model) { thinking ->
            val body = JSONObject().put("contents", contents).put("generationConfig", config(512, thinking))
            httpJson(request("generateContent", model, body), VOICE_LIMIT_MESSAGE)
        })
        if (chunk.text.isBlank()) throw EmptyReplyException(chunk.blocked ?: chunk.finish)
        return chunk.text.trim()
    }

    private fun request(method: String, model: String, body: JSONObject, sse: Boolean = false) = Request.Builder()
        .url("$BASE/models/$model:$method" + if (sse) "?alt=sse" else "")
        .header("x-goog-api-key", settings.geminiKey)
        .post(body.toString().toRequestBody("application/json".toMediaType()))
        .build()

    private fun config(maxOutputTokens: Int, thinking: JSONObject?) =
        JSONObject().put("maxOutputTokens", maxOutputTokens).apply { thinking?.let { put("thinkingConfig", it) } }

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

    /**
     * Thinking tokens count against maxOutputTokens and slow replies down, so try to switch thinking off. Models
     * disagree on how: on HTTP 400 (before any text) fall back to the next variant. The one that works is remembered.
     */
    private suspend fun <T> thinkingFallback(
        model: String,
        started: () -> Boolean = { false },
        attempt: suspend (JSONObject?) -> T,
    ): T {
        var variant = workingVariant[model] ?: 0
        while (true) {
            try {
                return attempt(THINKING[variant]?.let(::JSONObject)).also { workingVariant[model] = variant }
            } catch (e: ApiException) {
                if (e.code != 400 || started() || variant >= THINKING.lastIndex) throw e
                variant++
            }
        }
    }

    companion object {
        private const val BASE = "https://generativelanguage.googleapis.com/v1beta"
        const val RATE_LIMIT_MESSAGE = "free tier limit hit, try again in a minute"
        const val VOICE_LIMIT_MESSAGE = "gemini voice limit hit, try again in a minute (or switch voice to Phone in settings)"
        private const val TRANSCRIBE_PROMPT = "Transcribe this audio exactly as spoken. The speaker may use any language or " +
            "mix languages; write each word in its own language and normal script. Output only the transcript, nothing else."

        private val THINKING = listOf("""{"thinkingBudget":0}""", """{"thinkingLevel":"low"}""", null)
        private val workingVariant = ConcurrentHashMap<String, Int>()

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
