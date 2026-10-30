package dev.buddy

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

enum class Provider { GEMINI, CLAUDE }

val CLAUDE_MODELS = listOf("claude-sonnet-5-5", "claude-opus-5-5")
const val DEFAULT_GEMINI_MODEL = "gemini-flash-latest"

/** Provider choice, per-provider keys and models, stored with EncryptedSharedPreferences. */
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
        get() = if (str("provider") == "CLAUDE") Provider.CLAUDE else Provider.GEMINI
        set(v) = put("provider", v.name)
    var geminiKey: String get() = str("gemini_key"); set(v) = put("gemini_key", v)
    var geminiModel: String get() = str("gemini_model", DEFAULT_GEMINI_MODEL); set(v) = put("gemini_model", v)
    var claudeKey: String get() = str("api_key"); set(v) = put("api_key", v)
    var claudeModel: String get() = str("model", CLAUDE_MODELS[0]); set(v) = put("model", v)
    /** Claude only; compatible with Clicky's worker (POST {proxy}/chat, no key header). */
    var proxyUrl: String get() = str("proxy_url"); set(v) = put("proxy_url", v)

    val isConfigured: Boolean
        get() = when (provider) {
            Provider.GEMINI -> geminiKey.isNotBlank()
            Provider.CLAUDE -> claudeKey.isNotBlank() || proxyUrl.isNotBlank()
        }

    fun createProvider(): AiProvider = if (provider == Provider.GEMINI) GeminiClient(this) else ClaudeClient(this)
}
