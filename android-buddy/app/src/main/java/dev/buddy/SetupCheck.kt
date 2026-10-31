package dev.buddy

import android.content.Context
import android.os.Build
import android.speech.SpeechRecognizer
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

enum class Fix { SWITCH_TO_API, OPEN_ACCESSIBILITY, ADD_CREDIT, ADD_KEY, NOTIFICATION_ACCESS, CALENDAR }

class CheckResult(val name: String, val ok: Boolean, val detail: String, val ms: Long? = null, val fix: Fix? = null)

/** What the screen-capture check can use, registered by the running service when it holds a MediaProjection. */
object ScreenProbe {
    @Volatile var projection: (suspend () -> android.graphics.Bitmap)? = null
}

/** The "Check setup" checklist: mode, key and network, accessibility, screen capture, voice. */
object SetupCheck {
    val results = MutableStateFlow<List<CheckResult>>(emptyList())
    val running = MutableStateFlow(false)

    suspend fun run(context: Context, settings: Settings): List<CheckResult> {
        running.value = true
        try {
            val list = listOf(mode(settings), claude(settings), accessibility(context), screen(), voice(context, settings), notifications(context), calendar(context))
            results.value = list
            return list
        } finally {
            running.value = false
        }
    }

    private fun mode(s: Settings) =
        if (s.provider == Provider.CLAUDE) CheckResult("Mode", true, "Claude API (Claude can control the phone)")
        else CheckResult("Mode", false, "Claude app mode can't control the phone", fix = Fix.SWITCH_TO_API)

    /** A tiny live request, with the same headers as the agent. */
    private suspend fun claude(s: Settings): CheckResult {
        val name = "Claude key and network"
        if (s.claudeKey.isBlank()) return CheckResult(name, false, "no API key yet", fix = Fix.ADD_KEY)
        val model = s.claudeModel
        val body = JSONObject().put("model", model).put("max_tokens", 16)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", "ping")))
        if (AgentJson.usesFallbacks(model)) body.put("fallbacks", "default")
        val t0 = System.nanoTime()
        fun ms() = (System.nanoTime() - t0) / 1_000_000
        return try {
            httpJson(messagesRequest(s.claudeKey, model, body))
            CheckResult(name, true, "ok, $model answered", ms())
        } catch (e: ApiException) {
            when {
                e.code == 401 || e.code == 403 -> CheckResult(name, false, "bad key: claude rejected it", ms())
                claudeErrorMessage(e.code, e.body) == "add credit at console.anthropic.com" ->
                    CheckResult(name, false, "add credit at console.anthropic.com", ms(), Fix.ADD_CREDIT)
                else -> CheckResult(name, false, e.message.orEmpty(), ms())
            }
        } catch (e: IOException) {
            CheckResult(name, false, "no internet (${e.javaClass.simpleName})", ms())
        }
    }

    private fun accessibility(context: Context): CheckResult {
        val enabled = BuddyAccessibilityService.isEnabled(context)
        val connected = BuddyAccessibilityService.instance != null
        return when (a11yProblem(enabled, connected)) {
            null -> CheckResult("Accessibility", true, "on and connected")
            else -> CheckResult("Accessibility", false, a11yProblem(enabled, connected).orEmpty(), fix = Fix.OPEN_ACCESSIBILITY)
        }
    }

    /** A test screenshot: through accessibility if it's connected (Android 11+), else the running service's projection. */
    private suspend fun screen(): CheckResult {
        val name = "Screen capture"
        val a11y = if (Build.VERSION.SDK_INT >= 30) BuddyAccessibilityService.instance else null
        val grab: (suspend () -> android.graphics.Bitmap)? = if (a11y != null) ({ a11y.frame() }) else ScreenProbe.projection
            ?: return CheckResult(name, false, "start Buddy (or turn on accessibility) to test this")
        val t0 = System.nanoTime()
        return try {
            val bmp = grab!!()
            val sample = IntArray(100) { bmp.getPixel((it % 10 * 2 + 1) * bmp.width / 20, (it / 10 * 2 + 1) * bmp.height / 20) }
            val ms = (System.nanoTime() - t0) / 1_000_000
            if (isBlankFrame(sample)) CheckResult(name, false, "${bmp.width}x${bmp.height} but blank: share the entire screen", ms)
            else CheckResult(name, true, "${bmp.width}x${bmp.height}, not blank, via ${if (a11y != null) "accessibility" else "screen sharing"}", ms)
        } catch (e: Exception) {
            CheckResult(name, false, e.message ?: e.javaClass.simpleName, (System.nanoTime() - t0) / 1_000_000)
        }
    }

    private fun notifications(context: Context) =
        if (context.notificationAccessEnabled()) CheckResult("Notification access", true, "allowed: Claude can read your last 30 notifications")
        else CheckResult("Notification access", false, "optional: off, so Claude can't read notifications", fix = Fix.NOTIFICATION_ACCESS)

    private fun calendar(context: Context) =
        if (context.hasCalendarPermission()) CheckResult("Calendar", true, "allowed: Claude can read upcoming events")
        else CheckResult("Calendar", false, "optional: not allowed, so Claude can't read your calendar", fix = Fix.CALENDAR)

    private fun voice(context: Context, s: Settings): CheckResult {
        val engine = s.voiceEngine
        return if (engine == VoiceEngine.GEMINI) {
            if (s.geminiKey.isBlank()) CheckResult("Voice", false, "Gemini voice needs a Gemini key (or switch to Phone)")
            else CheckResult("Voice", true, "Gemini (adds about 1-2 s)")
        } else if (SpeechRecognizer.isRecognitionAvailable(context)) CheckResult("Voice", true, "Phone speech recognition is available")
        else CheckResult("Voice", false, "this phone has no speech recognizer; use Gemini voice")
    }
}
