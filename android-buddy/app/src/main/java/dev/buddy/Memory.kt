package dev.buddy

const val MAX_NOTES = 30
const val MAX_NOTE_CHARS = 120
const val MAX_ALIASES = 50
const val MAX_MEMORY_CONTEXT = 600

/** "shopping" means the app labelled "Daraz": a phrase the user says that the local router would not understand. */
data class Alias(val phrase: String, val app: String)

private val SENSITIVE = Regex(
    "pass(word|code|phrase)|\\botp\\b|one[- ]?time|\\bcvv\\b|\\bpin\\b|\\bssn\\b|secret|api[ -]?key|sk-ant|\\btoken\\b|\\bcard number\\b",
    RegexOption.IGNORE_CASE,
)
private val CARD_NUMBER = Regex("\\d(?:[ -]?\\d){12,18}")
private val CODE = Regex("(?<![\\d.,])\\d{4,8}(?![\\d.,])")   // a lone 4-8 digit number looks like an OTP or PIN

/** Notes about passwords, codes, card numbers and the like are never saved. */
fun looksSensitive(text: String) = SENSITIVE.containsMatchIn(text) || CARD_NUMBER.containsMatchIn(text) || CODE.containsMatchIn(text)

/** A short, single-line note, or null if it's empty, over [MAX_NOTE_CHARS] or sensitive. */
fun cleanNote(raw: String): String? {
    val note = raw.replace(Regex("\\s+"), " ").trim()
    return note.takeIf { it.isNotEmpty() && it.length <= MAX_NOTE_CHARS && !looksSensitive(it) }
}

/** Adds [note] (duplicates move to the end); at most [MAX_NOTES], the oldest dropped first. */
fun addNote(notes: List<String>, note: String): List<String> =
    (notes.filterNot { it.equals(note, ignoreCase = true) } + note).takeLast(MAX_NOTES)

fun addAlias(aliases: List<Alias>, phrase: String, app: String): List<Alias> {
    val p = normalizeCommand(phrase)
    if (p.isEmpty() || app.isBlank()) return aliases
    return (aliases.filterNot { it.phrase == p } + Alias(p, app)).takeLast(MAX_ALIASES)
}

fun aliasLookup(aliases: List<Alias>, phrase: String): String? = aliases.firstOrNull { it.phrase == normalizeCommand(phrase) }?.app

/** What Buddy has learned, for the start of a conversation (at most [MAX_MEMORY_CONTEXT] characters). */
fun memoryContext(notes: List<String>): String {
    if (notes.isEmpty()) return ""
    val text = "learned about the user (stored on their phone): " + notes.joinToString("; ") + "."
    return if (text.length <= MAX_MEMORY_CONTEXT) text else text.take(MAX_MEMORY_CONTEXT - 1) + "…"
}

fun encodeNotes(notes: List<String>) = notes.joinToString("\n")
fun decodeNotes(s: String) = s.split('\n').map { it.trim() }.filter { it.isNotEmpty() }.takeLast(MAX_NOTES)
fun encodeAliases(aliases: List<Alias>) = aliases.joinToString("\n") { "${it.phrase}\u0001${it.app}" }
fun decodeAliases(s: String) = s.split('\n').mapNotNull { line ->
    line.split('\u0001').takeIf { it.size == 2 && it[0].isNotBlank() && it[1].isNotBlank() }?.let { Alias(it[0], it[1]) }
}.takeLast(MAX_ALIASES)

private val CORRECTION = Regex("^(no|nope|not that|wrong|that's wrong|that was wrong|galat|होइन|गलत)\\b")

/** The next thing the user says undoes the last guess ("no, not that one"). */
fun isCorrection(transcript: String) = CORRECTION.containsMatchIn(normalizeCommand(transcript))

/** The app phrase in an "open X" command (English, or Nepali with the verb after or before), else null. */
fun appPhrase(transcript: String): String? {
    val t = normalizeCommand(transcript)
    val name = Regex("^(?:open|launch|start) (?:the )?(.+?)(?: app)?$").find(t)?.groupValues?.get(1)
        ?: (Regex("^(.+?) (?:$OPEN_WORDS)$").find(t) ?: Regex("^(?:$OPEN_WORDS) (.+)$").find(t))?.groupValues?.get(1)
            ?.removeSuffix(" app")?.removePrefix("app ")
    return name?.takeIf { it.isNotBlank() && it.split(' ').size <= 3 && it.split(' ').none { w -> w in TORCH_WORDS || w == "and" || w == "then" } }
}

/**
 * Whether a finished request's open_app guess should become an alias: the user's phrase is something the router could
 * not resolve on its own and differs from the app's name. Null if not.
 */
fun aliasToLearn(transcript: String, openedLabel: String, labels: List<String>): Alias? {
    val phrase = appPhrase(transcript) ?: return null
    if (norm(phrase) == norm(openedLabel) || confidentApp(phrase, labels) != null) return null
    return Alias(normalizeCommand(phrase), openedLabel)
}

/** A command that opens an app through a learned alias, or null. */
fun aliasCommand(transcript: String, aliases: List<Alias>, labels: () -> Collection<String>): LocalCommand.OpenApp? {
    if (aliases.isEmpty()) return null
    val phrase = appPhrase(transcript) ?: return null
    val app = aliasLookup(aliases, phrase) ?: return null
    return labels().firstOrNull { it == app }?.let { LocalCommand.OpenApp(it) }
}
