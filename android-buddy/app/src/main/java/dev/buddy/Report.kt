package dev.buddy

/** Only the last 4 characters of a key ever leave the app, in a report. */
fun maskKey(key: String): String = if (key.isBlank()) "(none)" else "…" + key.takeLast(4)

class ReportInfo(
    val version: String,
    val device: String,
    val android: String,
    val mode: String,
    val model: String,
    val voice: String,
    val keyMasked: String,
    val checks: List<CheckResult>,
    val timing: List<TimingEntry>,
    val lastError: String?,
    /** The text of crash.log, if any. */
    val crash: String? = null,
    /** The full key, only used to scrub it out of free text; never printed. */
    val secretToScrub: String = "",
    /** How many exchanges the transcript holds (a count only, never the contents). */
    val transcriptCount: Int = 0,
)

/** Plain text for "Copy report". Contains no API key: just its last 4 characters. */
fun buildReport(i: ReportInfo): String {
    fun scrub(s: String) = scrubSecrets(if (i.secretToScrub.length >= 8) s.replace(i.secretToScrub, maskKey(i.secretToScrub)) else s)
    return scrub(buildString {
        appendLine("Buddy report")
        appendLine("App version: ${i.version}")
        appendLine("Device: ${i.device}")
        appendLine("Android: ${i.android}")
        appendLine("Mode: ${i.mode}")
        appendLine("Model: ${i.model}")
        appendLine("Voice engine: ${i.voice}")
        appendLine("Claude key: ${i.keyMasked}")
        appendLine("Transcript: ${i.transcriptCount} saved (contents not included)")
        appendLine()
        appendLine("Checks:")
        if (i.checks.isEmpty()) appendLine("(not run yet)")
        i.checks.forEach { appendLine("${if (it.ok) "OK  " else "FAIL"} ${it.name}: ${it.detail}" + (it.ms?.let { m -> " [$m ms]" } ?: "")) }
        appendLine()
        appendLine("Last error: ${i.lastError ?: "(none)"}")
        appendLine()
        appendLine("Timing (last ${i.timing.size}):")
        appendLine(if (i.timing.isEmpty()) "(nothing yet)" else formatEntries(i.timing))
        if (!i.crash.isNullOrBlank()) { appendLine(); appendLine("Last crashes:"); append(i.crash) }
    })
}
