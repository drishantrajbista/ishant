package dev.buddy

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import kotlin.math.ceil

/** The Flash-Lite alias: the model with the most free requests per day. */
const val LITE_LATEST = "gemini-flash-lite-latest"

/** Every model we could try has used up its free quota: voice falls back to the phone until the next Pacific midnight. */
class GeminiExhaustedException : SpokenException("gemini voice is out for today, switched to phone voice. please ask again.")

/** What a Google 429 body says about the quota that was hit. */
class QuotaError(
    /** A per-minute quota (requests or tokens): usually clears within [retryAfterSec]. */
    val perMinute: Boolean,
    /** Won't clear soon: a per-day quota, or a limit of zero (no free quota at all on this model). */
    val daily: Boolean,
    val retryAfterSec: Double?,
)

/** Reads `error.details[]` (`QuotaFailure.violations[].quotaId/quotaValue` and `RetryInfo.retryDelay`) of a 429 body. */
fun parseQuotaError(body: String): QuotaError {
    var perMinute = false
    var daily = false
    var retry: Double? = null
    val error = try { JSONObject(body).optJSONObject("error") } catch (e: Exception) { null }
    val details = error?.optJSONArray("details")
    for (i in 0 until (details?.length() ?: 0)) {
        val detail = details?.optJSONObject(i) ?: continue
        when (detail.optString("@type").substringAfterLast('.')) {
            "QuotaFailure" -> {
                val violations = detail.optJSONArray("violations")
                for (j in 0 until (violations?.length() ?: 0)) {
                    val v = violations?.optJSONObject(j) ?: continue
                    val id = v.optString("quotaId")
                    when {
                        "PerDay" in id -> daily = true
                        "PerMinute" in id -> perMinute = true
                    }
                    if (v.optString("quotaValue") == "0") daily = true
                }
            }
            "RetryInfo" -> retry = detail.optString("retryDelay").removeSuffix("s").toDoubleOrNull()
        }
    }
    val message = error?.optString("message").orEmpty()
    if (retry == null) retry = Regex("""retry in ([0-9.]+)s""").find(message)?.groupValues?.get(1)?.toDoubleOrNull()
    if (Regex("""limit: 0\b""").containsMatchIn(message)) daily = true
    return QuotaError(perMinute, daily, retry)
}

// Models we can't use for "look at a screenshot and answer": image/audio generators, live/embedding models, etc.
private val UNUSABLE = Regex("image|tts|live|audio|embed|robotics|computer")

private fun versionOf(id: String): List<Int> =
    Regex("""gemini-(\d+(?:\.\d+)*)""").find(id)?.groupValues?.get(1)?.split('.')?.map { it.toInt() }
        ?: listOf(Int.MAX_VALUE)   // aliases such as gemini-flash-latest count as the newest

private fun compareVersions(a: List<Int>, b: List<Int>): Int {
    for (i in 0 until maxOf(a.size, b.size)) {
        val c = a.getOrElse(i) { 0 }.compareTo(b.getOrElse(i) { 0 })
        if (c != 0) return c
    }
    return 0
}

/** Newest first: higher version, then stable before preview/experimental, then by name. */
fun newestFirst(ids: Collection<String>): List<String> = ids.distinct().sortedWith(
    Comparator<String> { a, b -> compareVersions(versionOf(b), versionOf(a)) }
        .thenBy { if ("preview" in it || "exp" in it) 1 else 0 }
        .thenByDescending { it },
)

/** The order of models to try: [first], then the other Flash-Lite models, then the other Flash ones. */
fun fallbackOrder(first: List<String>, listed: List<String>, skip: Set<String> = emptySet()): List<String> {
    val usable = listed.filter { !UNUSABLE.containsMatchIn(it) }
    val lite = newestFirst(usable.filter { "flash-lite" in it })
    val flash = newestFirst(usable.filter { "flash" in it && "flash-lite" !in it })
    return (first + lite + flash).distinct().filter { it !in skip }
}

/** The model list for the picker in setup: Flash-Lite first (the most free requests per day), then the rest. */
fun pickerOrder(ids: List<String>): List<String> {
    val lite = newestFirst(ids.filter { "flash-lite" in it })
    return lite + newestFirst(ids.filter { it !in lite })
}

/** Google resets free quotas at midnight Pacific time, so "today" is the Pacific date. */
fun pacificDay(now: Instant = Instant.now()): String =
    now.atZone(ZoneId.of("America/Los_Angeles")).toLocalDate().toString()

/** Where the quota bookkeeping lives: EncryptedSharedPreferences in the app, a fake in tests. */
interface QuotaStore {
    /** Models whose daily quota ran out on [day] (a Pacific date). */
    fun exhausted(day: String): Set<String>
    fun markExhausted(day: String, model: String)
    /** The model we last told the user we had switched to on [day]. */
    fun lastNotified(day: String): String?
    fun setNotified(day: String, model: String)
    /** Index into [ModelFallback.THINKING] that [model] accepts; remembered for good. */
    fun thinkingVariant(model: String): Int?
    fun setThinkingVariant(model: String, variant: Int)
}

/**
 * Runs a Gemini request, coping with the free tier:
 *  - HTTP 400 means the thinking config isn't supported: try the next variant. Never a reason to
 *    change model; the variant that works is remembered per model.
 *  - HTTP 429 per-minute with a short retry delay: say so, wait, retry once.
 *  - HTTP 429 otherwise (per-day, limit 0, unknown), or 404: move on to the next model, remembering daily exhaustion
 *    until midnight Pacific so it isn't retried pointlessly.
 *  - If nothing is left: [GeminiExhaustedException].
 */
class ModelFallback(
    private val store: QuotaStore,
    private val listModels: suspend () -> List<String>,
    private val notify: (String?) -> Unit = {},
    private val today: () -> String = { pacificDay() },
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) {
    /** [first] is the preferred order (its head is the "requested" model); [attempt] gets (model, thinkingConfig JSON). */
    suspend fun <T> run(first: List<String>, attempt: suspend (model: String, thinking: String?) -> T): T {
        val day = today()
        val requested = first.first()
        val exhausted = store.exhausted(day).toMutableSet()
        val tried = mutableSetOf<String>()
        val queue = ArrayDeque(fallbackOrder(first, emptyList(), exhausted))
        var extended = false
        var why = if (requested in exhausted) "free limit on $requested used up" else ""
        var quotaFailures = 0
        var lastError: ApiException? = null

        while (true) {
            val model = queue.removeFirstOrNull()
            if (model == null) {
                if (extended) break
                extended = true   // the known candidates are used up: look at what else the key can use
                queue.addAll(fallbackOrder(first, safeListModels(), exhausted + tried).filter { it !in tried })
                continue
            }
            if (!tried.add(model)) continue
            if (model != requested && store.lastNotified(day) != model) {
                notify("switched to $model ($why)")
                store.setNotified(day, model)
            }
            try {
                return attemptModel(model, attempt)
            } catch (e: ApiException) {
                lastError = e
                when (e.code) {
                    429 -> {
                        quotaFailures++
                        if (parseQuotaError(e.body).daily) { store.markExhausted(day, model); exhausted += model }
                        why = "free limit on $model used up"
                    }
                    404 -> { store.markExhausted(day, model); exhausted += model; why = "$model isn't available" }
                    else -> throw e
                }
            }
        }
        if (quotaFailures > 0 || lastError == null) throw GeminiExhaustedException()
        throw lastError
    }

    /** One model: a per-minute 429 with a short retry delay is waited out once. */
    private suspend fun <T> attemptModel(model: String, attempt: suspend (String, String?) -> T): T {
        var retried = false
        while (true) {
            try {
                return withThinking(model) { thinking -> attempt(model, thinking) }
            } catch (e: ApiException) {
                if (e.code != 429 || retried) throw e
                val quota = parseQuotaError(e.body)
                val wait = quota.retryAfterSec
                if (!quota.perMinute || quota.daily || wait == null || wait > MAX_WAIT_SEC) throw e
                retried = true
                notify("busy, retrying in ${ceil(wait).toInt()}s")
                sleep((wait * 1000).toLong() + 300)
                notify(null)
            }
        }
    }

    /** Thinking tokens eat the output budget and slow replies, so try to switch thinking off; models disagree on how. */
    private suspend fun <T> withThinking(model: String, attempt: suspend (String?) -> T): T {
        var variant = store.thinkingVariant(model) ?: 0
        while (true) {
            try {
                val result = attempt(THINKING[variant])
                if (store.thinkingVariant(model) != variant) store.setThinkingVariant(model, variant)
                return result
            } catch (e: ApiException) {
                if (e.code != 400 || variant >= THINKING.lastIndex) throw e
                variant++
            }
        }
    }

    private suspend fun safeListModels(): List<String> = try {
        listModels()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        emptyList()
    }

    companion object {
        const val MAX_WAIT_SEC = 5.0
        val THINKING = listOf("""{"thinkingBudget":0}""", """{"thinkingLevel":"low"}""", null)
    }
}
