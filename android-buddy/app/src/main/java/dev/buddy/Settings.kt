package dev.buddy

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/** How questions get answered: hand-off to the Claude app, Gemini API, or Claude API. */
enum class Provider { CLAUDE_APP, GEMINI, CLAUDE }

/** How speech becomes text: Gemini listens to a recording Buddy makes, or the phone's own recognizer. */
enum class VoiceEngine { GEMINI, PHONE }

val CLAUDE_MODELS = listOf("claude-haiku-5-5", "claude-sonnet-5-5", "claude-opus-5-5")
const val DEFAULT_GEMINI_MODEL = "gemini-flash-latest"

/** (BCP-47 tag, or "" for the device default) to label, for the phone recognizer. */
val SPEECH_LANGUAGES = listOf(
    "" to "Device default",
    "en-US" to "English (US) en-US",
    "en-GB" to "English (UK) en-GB",
    "en-IN" to "English (India) en-IN",
    "ne-NP" to "Nepali ne-NP",
    "hi-IN" to "Hindi hi-IN",
)

/** Mode, voice engine, per-provider keys and models, stored with EncryptedSharedPreferences. */
class Settings(context: Context) {
    private val prefs = EncryptedSharedPreferences.create(
        context,
        "buddy_secure_prefs",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    private fun str(key: String, default: String = "") = prefs.getString(key, default).orEmpty().ifBlank { default }
    private fun put(key: String, value: String) = prefs.edit().putString(key, value.trim()).apply()

    var provider: Provider
        get() = when (str("provider")) {
            "GEMINI" -> Provider.GEMINI
            "CLAUDE" -> Provider.CLAUDE
            else -> Provider.CLAUDE_APP
        }
        set(v) = put("provider", v.name)

    /** The engine the user picked, or null to follow the default. */
    var voiceChoice: VoiceEngine?
        get() = VoiceEngine.entries.firstOrNull { it.name == str("voice") }
        set(v) = put("voice", v?.name.orEmpty())

    /** Gemini voice is the default whenever a Gemini key is saved; otherwise the phone's recognizer. */
    val voiceEngine: VoiceEngine
        get() = voiceChoice ?: if (geminiKey.isNotBlank()) VoiceEngine.GEMINI else VoiceEngine.PHONE

    var speechLanguage: String get() = str("speech_language"); set(v) = put("speech_language", v)
    var geminiKey: String get() = str("gemini_key"); set(v) = put("gemini_key", v)
    var geminiModel: String get() = str("gemini_model", DEFAULT_GEMINI_MODEL); set(v) = put("gemini_model", v)
    var claudeKey: String get() = str("api_key"); set(v) = put("api_key", v)
    var claudeModel: String get() = str("model", CLAUDE_MODELS[0]); set(v) = put("model", v)
    /** Claude API only; compatible with Clicky's worker (POST {proxy}/chat, no key header). */
    var proxyUrl: String get() = str("proxy_url"); set(v) = put("proxy_url", v)

    val isConfigured: Boolean
        get() = when (provider) {
            Provider.CLAUDE_APP -> true
            Provider.GEMINI -> geminiKey.isNotBlank()
            Provider.CLAUDE -> claudeKey.isNotBlank() || proxyUrl.isNotBlank()
        }

    /** Gemini voice needs a Gemini key whatever the mode is. */
    val voiceReady: Boolean get() = voiceEngine == VoiceEngine.PHONE || geminiKey.isNotBlank()

    /** The chat model for the API modes (not used by the Claude app hand-off). */
    fun createProvider(): AiProvider = if (provider == Provider.CLAUDE) ClaudeClient(this) else GeminiClient(this)
}
