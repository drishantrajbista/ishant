package dev.buddy

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/** How questions get answered: a hand-off to the Claude app (chat), or the Claude API. Gemini only does voice. */
enum class Provider { CLAUDE_APP, CLAUDE }

/** How speech becomes text: Gemini listens to a recording Buddy makes, or the phone's own recognizer. */
enum class VoiceEngine { GEMINI, PHONE }

val CLAUDE_MODELS = listOf("claude-haiku-5-5", "claude-sonnet-5-5", "claude-opus-5-5")
/** Flash-Lite has far more free requests per day than the regular Flash models. */
const val DEFAULT_GEMINI_MODEL = LITE_LATEST

private const val OLD_DEFAULT_GEMINI_MODEL = "gemini-flash-latest"

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
class Settings(context: Context) : QuotaStore {
    private val prefs = EncryptedSharedPreferences.create(
        context,
        "buddy_secure_prefs",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    init {
        // 1.2.1: the proxy field is gone; a key pasted into it becomes the key. The field is always dropped.
        prefs.getString("proxy_url", null)?.let { old ->
            keyFromOldProxyField(old, claudeKey)?.let { claudeKey = it }
            prefs.edit().remove("proxy_url").apply()
        }
        // 1.1.1: the old default (regular Flash) only gets ~20 free requests a day; move people to Flash-Lite once.
        if (!prefs.getBoolean("model_default_migrated", false)) {
            if (str("gemini_model") == OLD_DEFAULT_GEMINI_MODEL) put("gemini_model", DEFAULT_GEMINI_MODEL)
            prefs.edit().putBoolean("model_default_migrated", true).apply()
        }
    }

    private fun str(key: String, default: String = "") = prefs.getString(key, default).orEmpty().ifBlank { default }
    private fun put(key: String, value: String) = prefs.edit().putString(key, value.trim()).apply()

    var provider: Provider
        get() = if (str("provider") == "CLAUDE") Provider.CLAUDE else Provider.CLAUDE_APP   // old "GEMINI" -> Claude app
        set(v) = put("provider", v.name)

    /** The engine the user picked, or null to follow the default. */
    var voiceChoice: VoiceEngine?
        get() = VoiceEngine.entries.firstOrNull { it.name == str("voice") }
        set(v) = put("voice", v?.name.orEmpty())

    /**
     * The phone's recognizer is the default (it is the fastest); Gemini voice is an explicit choice. If Gemini ran out
     * of free quota today the phone's recognizer is used until the next Pacific midnight, then it switches back.
     */
    val voiceEngine: VoiceEngine
        get() = when {
            str("voice_out_day") == pacificDay() -> VoiceEngine.PHONE
            else -> voiceChoice ?: VoiceEngine.PHONE
        }

    fun markGeminiVoiceOutToday() = put("voice_out_day", pacificDay())

    /** Claude app mode: ask Claude to point and draw (adds the tag instructions to the question). */
    var drawTags: Boolean
        get() = str("draw_tags", "1") == "1"
        set(v) = put("draw_tags", if (v) "1" else "0")

    var onboarded: Boolean
        get() = str("onboarded") == "1"
        set(v) = put("onboarded", if (v) "1" else "0")

    /** How fast replies are spoken, 0.8x to 1.5x. */
    var speechRate: Float
        get() = str("speech_rate", "1.0").toFloatOrNull()?.coerceIn(0.8f, 1.5f) ?: 1f
        set(v) = put("speech_rate", v.coerceIn(0.8f, 1.5f).toString())

    var speechLanguage: String get() = str("speech_language"); set(v) = put("speech_language", v)
    var geminiKey: String get() = str("gemini_key"); set(v) = put("gemini_key", v)
    var geminiModel: String get() = str("gemini_model", DEFAULT_GEMINI_MODEL); set(v) = put("gemini_model", v)
    var claudeKey: String get() = str("api_key"); set(v) = put("api_key", cleanKey(v))
    var claudeModel: String get() = str("model", CLAUDE_MODELS[0]); set(v) = put("model", v)

    val isConfigured: Boolean
        get() = provider == Provider.CLAUDE_APP || claudeKey.isNotBlank()

    /** Gemini voice needs a Gemini key whatever the mode is. */
    val voiceReady: Boolean get() = voiceEngine == VoiceEngine.PHONE || geminiKey.isNotBlank()

    // --- QuotaStore: Gemini free-tier bookkeeping, keyed by Pacific date ("day|value") ---

    private fun forDay(key: String, day: String): String? =
        str(key).takeIf { it.substringBefore('|') == day }?.substringAfter('|')

    override fun exhausted(day: String): Set<String> =
        forDay("quota_exhausted", day)?.split(',')?.filter { it.isNotBlank() }?.toSet().orEmpty()

    override fun markExhausted(day: String, model: String) =
        put("quota_exhausted", "$day|" + (exhausted(day) + model).joinToString(","))

    override fun lastNotified(day: String): String? = forDay("quota_notified", day)?.takeIf { it.isNotBlank() }

    override fun setNotified(day: String, model: String) = put("quota_notified", "$day|$model")

    override fun thinkingVariant(model: String): Int? = prefs.getInt("thinking_$model", -1).takeIf { it >= 0 }

    override fun setThinkingVariant(model: String, variant: Int) =
        prefs.edit().putInt("thinking_$model", variant).apply()
}
