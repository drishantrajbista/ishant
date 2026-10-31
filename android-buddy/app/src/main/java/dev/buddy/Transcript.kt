package dev.buddy

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** One thing the user said and what Buddy answered, with the mode that handled it ("claude api", "claude app", "local"). */
data class Exchange(val at: Long, val user: String, val reply: String, val mode: String)

const val MAX_EXCHANGES = 300
const val RECENT_EXCHANGES = 10
const val RECENT_ITEM_CHARS = 300
const val RECENT_CONTEXT_CHARS = 1500
private const val STORED_USER_CHARS = 500
private const val STORED_REPLY_CHARS = 1000

private fun String.oneLine(max: Int): String {
    val t = replace(Regex("\\s+"), " ").trim()
    return if (t.length <= max) t else t.take(max - 1) + "…"
}

/** [list] plus [ex] (clipped to a storable size), keeping the newest [MAX_EXCHANGES]. */
fun addExchange(list: List<Exchange>, ex: Exchange): List<Exchange> =
    (list + ex.copy(user = ex.user.oneLine(STORED_USER_CHARS), reply = ex.reply.oneLine(STORED_REPLY_CHARS))).takeLast(MAX_EXCHANGES)

/**
 * The "recent conversation" text block for a fresh Claude conversation: the last [RECENT_EXCHANGES] exchanges, each side
 * clipped to [RECENT_ITEM_CHARS], the oldest dropped first to stay within [limit] characters. Empty if there are none.
 */
fun recentContext(list: List<Exchange>, limit: Int = RECENT_CONTEXT_CHARS): String {
    val head = "recent conversation (oldest first), for follow-ups like \"do that again\":"
    val lines = ArrayDeque<String>()
    var used = head.length
    for (ex in list.takeLast(RECENT_EXCHANGES).asReversed()) {
        val pair = "user: ${ex.user.oneLine(RECENT_ITEM_CHARS)}\nbuddy: ${ex.reply.oneLine(RECENT_ITEM_CHARS)}"
        if (used + pair.length + 1 > limit) break
        lines.addFirst(pair); used += pair.length + 1
    }
    return if (lines.isEmpty()) "" else head + "\n" + lines.joinToString("\n")
}

/** A one-line version of the last [n] exchanges for the Claude app hand-off text. */
fun recentLine(list: List<Exchange>, n: Int = 3, limit: Int = 500): String {
    val parts = list.takeLast(n).map { "i said \"${it.user.oneLine(100)}\", you said \"${it.reply.oneLine(100)}\"" }
    if (parts.isEmpty()) return ""
    val text = "earlier: " + parts.joinToString("; ")
    return if (text.length <= limit) text else text.take(limit - 1) + "…"
}

/** Newest first, grouped by calendar day (newest day first). */
fun groupByDay(list: List<Exchange>, zone: ZoneId = ZoneId.systemDefault()): List<Pair<LocalDate, List<Exchange>>> =
    list.sortedByDescending { it.at }.groupBy { Instant.ofEpochMilli(it.at).atZone(zone).toLocalDate() }.toList()

/** The entries whose text contains [query] (case-insensitive); all of them if the query is blank. */
fun searchExchanges(list: List<Exchange>, query: String): List<Exchange> {
    val q = query.trim()
    return if (q.isEmpty()) list else list.filter { it.user.contains(q, true) || it.reply.contains(q, true) }
}

fun encodeExchanges(list: List<Exchange>): String = JSONArray().also { a ->
    list.forEach { a.put(JSONObject().put("t", it.at).put("u", it.user).put("r", it.reply).put("m", it.mode)) }
}.toString()

fun decodeExchanges(s: String): List<Exchange> = try {
    val a = JSONArray(s)
    (0 until a.length()).map { a.getJSONObject(it) }.map { Exchange(it.optLong("t"), it.optString("u"), it.optString("r"), it.optString("m")) }.takeLast(MAX_EXCHANGES)
} catch (e: Exception) { emptyList() }

/** The on-device transcript: a private file in app storage (never synced; backups are off). */
object TranscriptStore {
    private var cache: List<Exchange>? = null
    private fun file(c: Context) = File(c.applicationContext.filesDir, "transcript.json")

    @Synchronized fun entries(c: Context): List<Exchange> =
        cache ?: (file(c).takeIf { it.exists() }?.let { runCatching { decodeExchanges(it.readText()) }.getOrNull() } ?: emptyList()).also { cache = it }

    @Synchronized fun add(c: Context, ex: Exchange) = save(c, addExchange(entries(c), ex))
    @Synchronized fun delete(c: Context, ex: Exchange) = save(c, entries(c) - ex)
    @Synchronized fun clear(c: Context) = save(c, emptyList())

    private fun save(c: Context, list: List<Exchange>) {
        cache = list
        runCatching { file(c).writeText(encodeExchanges(list)) }
    }
}
