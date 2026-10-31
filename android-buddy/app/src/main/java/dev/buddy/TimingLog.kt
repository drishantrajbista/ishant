package dev.buddy

import kotlinx.coroutines.flow.MutableStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** One timed step of a request. Steps: voice, screen, claude, action, settle, total. */
data class TimingEntry(val at: Long, val step: String, val ms: Long, val detail: String = "")

private fun secs(ms: Long) = String.format(Locale.US, "%.1f s", ms / 1000.0)

/** "Last answer: 6.2 s (Claude 4.1 s, voice 1.5 s, screen 0.6 s)" for the most recent request that finished, or null. */
fun summarize(entries: List<TimingEntry>): String? {
    val end = entries.indexOfLast { it.step == "total" }
    if (end < 0) return null
    val start = entries.subList(0, end).indexOfLast { it.step == "total" } + 1
    val group = entries.subList(start, end + 1)
    fun sum(step: String) = group.filter { it.step == step }.sumOf { it.ms }
    val parts = listOf("Claude" to "claude", "voice" to "voice", "screen" to "screen")
        .map { it.first to sum(it.second) }.filter { it.second > 0 }
        .joinToString(", ") { "${it.first} ${secs(it.second)}" }
    return "Last answer: ${secs(entries[end].ms)}" + if (parts.isNotEmpty()) " ($parts)" else ""
}

/** One line per entry: `12:03:45 claude 4123 ms (38 KB, HTTP 200)`. */
fun formatEntries(entries: List<TimingEntry>): String {
    val clock = SimpleDateFormat("HH:mm:ss", Locale.US)
    return entries.joinToString("\n") {
        "${clock.format(Date(it.at))} ${it.step} ${it.ms} ms" + if (it.detail.isNotEmpty()) " (${it.detail})" else ""
    }
}

/** A small in-memory ring buffer of the last ~30 timed steps; nothing is stored on disk. */
object TimingLog {
    private const val MAX = 30
    private val ring = ArrayDeque<TimingEntry>()

    /** The last finished request, for the home screen. */
    val summary = MutableStateFlow<String?>(null)

    /** The last error message shown to the user, for the report. */
    @Volatile var lastError: String? = null

    @Synchronized fun record(step: String, ms: Long, detail: String = "") {
        ring.addLast(TimingEntry(System.currentTimeMillis(), step, ms, detail))
        while (ring.size > MAX) ring.removeFirst()
        if (step == "total") summary.value = summarize(ring.toList())
    }

    @Synchronized fun entries(): List<TimingEntry> = ring.toList()

    inline fun <T> time(step: String, detail: String = "", block: () -> T): T {
        val t0 = System.nanoTime()
        try {
            return block()
        } finally {
            record(step, (System.nanoTime() - t0) / 1_000_000, detail)
        }
    }
}
