package dev.buddy

import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.hypot

/**
 * One thing on screen worth telling Claude about; x,y is its centre in screenshot pixels. [state] lists what is set, comma
 * separated (checked or unchecked, disabled, selected, focused); [scrollable] says it scrolls; [pkg] is the app it belongs to.
 */
data class UiElement(
    val role: String, val label: String, val x: Int, val y: Int,
    val state: String? = null, val scrollable: Boolean = false, val pkg: String? = null,
)

const val MAX_ELEMENTS = 60
private const val MAX_LABEL = 40

/**
 * `[7] switch "Wi-Fi" (checked, focused) at (540,410) scrollable pkg=com.android.settings`, one element per line (at most
 * [MAX_ELEMENTS], labels 40 characters). The package is only written when it changes from the line above.
 */
fun formatElements(elements: List<UiElement>): String =
    if (elements.isEmpty()) "(no readable elements)"
    else {
        var lastPkg: String? = null
        elements.take(MAX_ELEMENTS).mapIndexed { i, e ->
            val label = e.label.replace(Regex("\\s+"), " ").replace('"', '\'').trim()
                .let { if (it.length > MAX_LABEL) it.take(MAX_LABEL) + "…" else it }
            buildString {
                append("[${i + 1}] ${e.role}")
                if (label.isNotEmpty()) append(" \"$label\"")
                e.state?.let { append(" ($it)") }
                append(" at (${e.x},${e.y})")
                if (e.scrollable && e.role != "scrollable") append(" scrollable")
                if (e.pkg != null && e.pkg != lastPkg) append(" pkg=${e.pkg}")
                if (e.pkg != null) lastPkg = e.pkg
            }
        }.joinToString("\n")
    }

fun roleOf(
    className: String?, clickable: Boolean, editable: Boolean, scrollable: Boolean, checkable: Boolean, password: Boolean,
    link: Boolean = false,
): String {
    val c = className.orEmpty()
    return when {
        password -> "password field"
        editable -> "input"
        checkable -> if (c.contains("CheckBox")) "checkbox" else "switch"
        link -> "link"
        c.endsWith("Button") || clickable -> "button"
        scrollable -> "scrollable"
        c.endsWith("ImageView") -> "image"
        else -> "text"
    }
}

/** The label of the element nearest to (x,y) in screenshot pixels, for status lines like "tapping Display". */
fun nearestLabel(elements: List<UiElement>, x: Float, y: Float, within: Float = 90f): String? =
    elements.filter { it.label.isNotBlank() }.minByOrNull { hypot(it.x - x, it.y - y) }
        ?.takeIf { hypot(it.x - x, it.y - y) <= within }?.label?.take(MAX_LABEL)

/** Finger path (x1,y1,x2,y2) in display pixels for "scroll [direction] by [amount]" at (x,y): the opposite swipe. */
fun scrollSwipe(direction: String, amount: Int, x: Int, y: Int, w: Int, h: Int): IntArray {
    val (dx, dy) = when (direction.lowercase()) {
        "down" -> 0 to -1   // content moves up, so the finger does
        "up" -> 0 to 1
        "right" -> -1 to 0
        "left" -> 1 to 0
        else -> 0 to -1
    }
    val horizontal = dx != 0
    val span = if (horizontal) w else h
    var dist = (amount * 0.15f * h).coerceIn(0.10f * span, 0.80f * span)
    val margin = 0.01f * span
    // Keep the whole swipe on screen by shifting the start rather than shortening the swipe.
    var sx = x.toFloat(); var sy = y.toFloat()
    if (horizontal) { sx = sx.coerceIn(margin + if (dx < 0) dist else 0f, w - margin - if (dx > 0) dist else 0f) }
    else { sy = sy.coerceIn(margin + if (dy < 0) dist else 0f, h - margin - if (dy > 0) dist else 0f) }
    return intArrayOf(sx.toInt(), sy.toInt(), (sx + dx * dist).toInt(), (sy + dy * dist).toInt())
}

enum class KeyAction { ENTER, BACKSPACE, BACK, HOME, SELECT_ALL }

const val SUPPORTED_KEYS = "Return/Enter, BackSpace, Escape (back), Home or super (home screen), ctrl+a (select all)"

fun parseKey(text: String): KeyAction? = when (text.trim().lowercase().replace(" ", "")) {
    "return", "enter" -> KeyAction.ENTER
    "backspace" -> KeyAction.BACKSPACE
    "escape", "esc" -> KeyAction.BACK
    "home", "super" -> KeyAction.HOME
    "ctrl+a", "control+a" -> KeyAction.SELECT_ALL
    else -> null
}

class AppMatch(val best: String?, val candidates: List<String>)

internal fun norm(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

internal fun edit(a: String, b: String): Int {
    var prev = IntArray(b.length + 1) { it }
    for (i in 1..a.length) {
        val cur = IntArray(b.length + 1).also { it[0] = i }
        for (j in 1..b.length) cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
        prev = cur
    }
    return prev[b.length]
}

private fun appScore(q: String, label: String): Int {
    val l = norm(label)
    return when {
        l == q -> 100
        l.startsWith(q) -> 80
        l.contains(q) -> 60
        q.contains(l) && l.length >= 3 -> 50
        q.length >= 4 && edit(q, l) <= 2 -> 40
        else -> 0
    }
}

/** Fuzzy-matches an app name Claude gave to the labels of the launchable apps. */
fun matchApps(query: String, labels: List<String>): AppMatch {
    val q = norm(query)
    if (q.isEmpty()) return AppMatch(null, emptyList())
    val scored = labels.distinct().map { it to appScore(q, it) }
    val best = scored.maxByOrNull { it.second }?.takeIf { it.second >= 40 }?.first
    val close = scored.sortedBy { edit(q, norm(it.first)) - it.second }.take(3).map { it.first }
    return AppMatch(best, close)
}

private val ACTION_START = Regex(
    "^(hey buddy[, ]*)?(please |can you |could you |would you )?(open|tap|click|press|scroll|swipe|type|turn (on|off)|" +
        "switch (on|off)|go to|go back|launch|close|search for|set|enable|disable|toggle|play|send|call|install|" +
        "uninstall|delete|take a screenshot|navigate|mute|unmute)\\b",
)

/** Claude app mode can't act on the phone; a request that clearly asks it to should say so instead of opening Claude. */
fun looksLikeAction(transcript: String): Boolean {
    val t = transcript.trim().lowercase()
    return ACTION_START.containsMatchIn(t) && !SEND_TEXT.containsMatchIn(t)
}

/** "send dad a message that i'm late": writing text for the user to paste (see the [COPY] rule), not an action Buddy can't do. */
private val SEND_TEXT = Regex("^(hey buddy[, ]*)?(please |can you |could you |would you )?send\\b.*\\b(message|msg|text|sms|email|e-mail|mail|note|reply|caption)\\b")

/** True if two coordinate arrays disagree by more than a pixel (used by tests of the mapping helpers). */
internal fun near(a: Float, b: Float) = abs(a - b) <= 1f

/** Haiku gets 25 turns per request, the bigger models 40 (they take on longer multi-app tasks). */
fun turnCap(model: String) = if ("haiku" in model) 25 else 40

/** What an `open_url` link should be opened with: ACTION_VIEW, or compose-only/dial-only actions. Null if unsupported. */
fun urlAction(url: String): String? = when (url.trim().substringBefore(':').lowercase()) {
    "http", "https", "geo" -> "VIEW"
    "mailto", "sms", "smsto" -> "SENDTO"   // opens a draft; never sends
    "tel" -> "DIAL"                          // opens the dialer; never calls
    else -> null
}

private val SETTINGS_ACTIONS = mapOf(
    "wifi" to "android.settings.WIFI_SETTINGS",
    "bluetooth" to "android.settings.BLUETOOTH_SETTINGS",
    "display" to "android.settings.DISPLAY_SETTINGS",
    "sound" to "android.settings.SOUND_SETTINGS",
    "battery" to "android.settings.BATTERY_SAVER_SETTINGS",
    "apps" to "android.settings.MANAGE_ALL_APPLICATIONS_SETTINGS",
    "app_details" to "android.settings.APPLICATION_DETAILS_SETTINGS",
    "location" to "android.settings.LOCATION_SOURCE_SETTINGS",
    "notifications" to "android.settings.ALL_APPS_NOTIFICATION_SETTINGS",
    "accessibility" to "android.settings.ACCESSIBILITY_SETTINGS",
    "date_time" to "android.settings.DATE_SETTINGS",
    "language" to "android.settings.LOCALE_SETTINGS",
    "storage" to "android.settings.INTERNAL_STORAGE_SETTINGS",
    "mobile_data" to "android.settings.NETWORK_OPERATOR_SETTINGS",
    "airplane" to "android.settings.AIRPLANE_MODE_SETTINGS",
    "nfc" to "android.settings.NFC_SETTINGS",
    "developer" to "android.settings.APPLICATION_DEVELOPMENT_SETTINGS",
)
const val MAIN_SETTINGS_ACTION = "android.settings.SETTINGS"

/** The Settings intent action for a page name; the main Settings screen if the page is unknown. */
fun settingsAction(page: String): String = SETTINGS_ACTIONS[page] ?: MAIN_SETTINGS_ACTION

const val MAX_MESSAGES = 24
const val FOLLOW_UP_MS = 90_000L

/** A quick follow-up (within 90 s) keeps the conversation; otherwise each hold starts fresh so old screenshots aren't re-sent. */
fun keepContext(lastAt: Long, now: Long, messageCount: Int): Boolean =
    lastAt != 0L && now - lastAt < FOLLOW_UP_MS && messageCount < MAX_MESSAGES

/** What's wrong with the accessibility service, in words for the user, or null if it is fine. */
fun a11yProblem(enabled: Boolean, connected: Boolean): String? = when {
    connected -> null
    enabled -> "Buddy is enabled but not connected: turn Buddy off and on again in Accessibility"
    else -> "Claude can't control the screen yet: turn on Buddy in Accessibility"
}

const val HINT_TIMES = 3

/** The bubble's "hold to talk · drag down to ✕ to close" hint shows the first few times only. */
fun shouldShowHint(shownBefore: Int) = shownBefore < HINT_TIMES

const val BUBBLE_HINT = "hold to talk · drag down to ✕ to close"

// region effort routing

private const val MULTI_STEP_WORDS = 14

/** Words that start a later step. "first" and "next" are only counted before a verb ("first open settings"), not as ordinals ("the first result", "next song"). */
private val SEQUENCE_WORDS = setOf("then", "finally", "afterwards", "afterward", "ani", "pachi", "अनि", "पछि")
private val ACTION_VERBS = setOf("open", "turn", "set", "search", "play", "find", "go", "change", "add", "khola", "khol", "kholdeu", "gara", "खोल", "गर")

/**
 * "medium" when [thinkDeeper] is on or the request looks multi-step (a sequence word, two or more action verbs joined by
 * "and"/"ani", or more than 14 words); "low" otherwise. Simple commands stay fast.
 */
fun chooseEffort(transcript: String, thinkDeeper: Boolean): String {
    if (thinkDeeper) return "medium"
    val t = normalizeCommand(transcript)
    val words = t.split(' ').filter { it.isNotEmpty() }
    if (words.size > MULTI_STEP_WORDS) return "medium"
    if (words.any { it in SEQUENCE_WORDS } || " after that " in " $t ") return "medium"
    if (words.indices.any { i -> (words[i] == "first" || words[i] == "next") && words.getOrNull(i + 1) in ACTION_VERBS }) return "medium"
    if (words.any { it == "and" || it == "ani" } && words.count { it in ACTION_VERBS } >= 2) return "medium"
    return "low"
}

/** The effort for this run: chosen when a conversation starts, then kept for the rest of it (changing it mid-way would break the prompt cache). */
fun effortFor(freshConversation: Boolean, current: String, transcript: String, thinkDeeper: Boolean): String =
    if (freshConversation) chooseEffort(transcript, thinkDeeper) else current

/** The detail of a "claude" timing entry, which Copy report shows: `38 KB, effort low, HTTP 200`. */
fun requestDetail(sizeKb: Int, effort: String, status: String, mode: String = "") =
    "$sizeKb KB, effort $effort" + (if (mode.isEmpty()) "" else ", $mode") + ", HTTP $status"

// endregion

// region steadier multi-step control

/** Lowercase, with runs of whitespace as one space: how node text and a search are compared. */
fun normalizeText(s: String?): String = s.orEmpty().lowercase().replace(Regex("\\s+"), " ").trim()

/** True if a node's text or content description contains [query] (case-insensitive); a blank query matches nothing. */
fun textMatches(text: String?, description: String?, query: String): Boolean {
    val q = normalizeText(query)
    return q.isNotEmpty() && (normalizeText(text).contains(q) || normalizeText(description).contains(q))
}

/** Runs [block]; if it reports failure, waits [waitMs] and runs it once more (a tap that was cancelled gets one more go). */
suspend fun retryOnce(waitMs: Long, block: suspend () -> Boolean): Boolean {
    if (block()) return true
    delay(waitMs)
    return block()
}

const val WINDOW_WAIT_MS = 1200L

/** After launching another screen: done once a window-state-changed event has arrived since [since], or [maxMs] have passed. */
fun windowWaitDone(lastChangeAt: Long, since: Long, now: Long, maxMs: Long = WINDOW_WAIT_MS): Boolean =
    lastChangeAt >= since || now - since >= maxMs

/** Remembers the last element list so an action that changed nothing can be noticed (and the model told to try something else). */
class ScreenWatcher {
    private var last: String? = null

    /** Records [list]; true if it differs from the previous one (the first one always does). */
    fun changed(list: String): Boolean {
        val differs = list != last
        last = list
        return differs
    }
}

const val UNCHANGED_NOTE = "the screen didn't change after that. don't repeat the same action: scroll or use scroll_to_text to bring " +
    "the target into view, wait_for a screen that's still loading, or try a different target."

/** What is spoken when the turn cap is hit: where it stopped ([screen]: the app in front) and what it was doing last. */
fun turnCapMessage(screen: String?, lastStatus: String?): String {
    val where = screen?.trim()?.takeIf { it.isNotEmpty() }
    val doing = lastStatus?.trim()?.takeIf { it.isNotEmpty() }
    return "that took too many steps, i stopped" + when {
        where != null && doing != null -> " at $where after $doing"
        where != null -> " at $where"
        doing != null -> " after $doing"
        else -> ""
    }
}

// endregion
