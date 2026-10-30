package dev.buddy

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/** Google Gemini (free tier from AI Studio) via streamGenerateContent with SSE. */
class GeminiClient(private val settings: Settings) : AiProvider {
    override fun stream(
        systemPrompt: String,
        history: List<Pair<String, String>>,
        screenshot: Screenshot,
        transcript: String,
    ): Flow<String> {
        fun content(role: String, vararg parts: JSONObject) =
            JSONObject().put("role", role).put("parts", JSONArray(parts.toList()))
        fun text(t: String) = JSONObject().put("text", t)

        val contents = JSONArray()
        history.forEach { (user, assistant) ->
            contents.put(content("user", text(user)))
            contents.put(content("model", text(assistant)))
        }
        contents.put(
            content(
                "user",
                JSONObject().put(
                    "inline_data",
                    JSONObject().put("mime_type", "image/jpeg").put("data", screenshot.jpegBase64),
                ),
                text(screenLabel(screenshot)),
                text(transcript),
            ),
        )
        val body = JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(text(systemPrompt))))
            .put("contents", contents)
            .put("generationConfig", JSONObject().put("maxOutputTokens", 1024))

        val request = Request.Builder()
            .url("$BASE/models/${settings.geminiModel.removePrefix("models/")}:streamGenerateContent?alt=sse")
            .header("x-goog-api-key", settings.geminiKey)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        return flow {
            sseData(request, RATE_LIMIT_MESSAGE).collect { data ->
                val parts = JSONObject(data).optJSONArray("candidates")?.optJSONObject(0)
                    ?.optJSONObject("content")?.optJSONArray("parts") ?: return@collect
                for (i in 0 until parts.length()) parts.getJSONObject(i).optString("text").takeIf { it.isNotEmpty() }?.let { emit(it) }
            }
        }
    }

    companion object {
        private const val BASE = "https://generativelanguage.googleapis.com/v1beta"
        const val RATE_LIMIT_MESSAGE = "free tier limit hit, try again in a minute"

        /** Model ids (without the "models/" prefix) that support generateContent. */
        suspend fun listModels(apiKey: String): List<String> = withContext(Dispatchers.IO) {
            val request = Request.Builder().url("$BASE/models?pageSize=1000").header("x-goog-api-key", apiKey).build()
            sharedHttp.newCall(request).execute().use { r ->
                if (!r.isSuccessful) throw IOException("Gemini API ${r.code}: ${r.body?.string()?.take(200)}")
                val models = JSONObject(r.body!!.string()).optJSONArray("models") ?: JSONArray()
                (0 until models.length()).map { models.getJSONObject(it) }
                    .filter { m -> m.optJSONArray("supportedGenerationMethods")?.let { a -> (0 until a.length()).any { a.getString(it) == "generateContent" } } == true }
                    .map { it.getString("name").removePrefix("models/") }
            }
        }
    }
}
