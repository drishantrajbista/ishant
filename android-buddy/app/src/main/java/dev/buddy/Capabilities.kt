package dev.buddy

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.TextStyle
import java.util.Locale

data class CalEvent(val title: String, val startMs: Long, val endMs: Long, val location: String?, val allDay: Boolean)

data class Notif(val key: String, val app: String, val title: String, val text: String, val at: Long)

/** The last 30 notifications, in memory only: never written to disk. Filled by [BuddyNotificationListener]. */
object NotificationStore {
    private const val MAX = 30
    private val items = ArrayDeque<Notif>()

    @Synchronized fun add(n: Notif) {
        items.removeAll { it.key == n.key }
        items.addLast(n)
        while (items.size > MAX) items.removeFirst()
    }

    @Synchronized fun remove(key: String) { items.removeAll { it.key == key } }
    @Synchronized fun clear() = items.clear()
    @Synchronized fun entries(): List<Notif> = items.toList()
}

/** "9 am", "12:30 pm": how a time is said aloud. */
fun spokenTime(ms: Long, zone: ZoneId): String {
    val t = Instant.ofEpochMilli(ms).atZone(zone)
    val hour = (t.hour % 12).let { if (it == 0) 12 else it }
    val minute = if (t.minute == 0) "" else ":%02d".format(t.minute)
    return "$hour$minute ${if (t.hour < 12) "am" else "pm"}"
}

private fun dayWord(ms: Long, now: Long, zone: ZoneId): String {
    val d = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    return when (d) {
        today -> "today"
        today.plusDays(1) -> "tomorrow"
        else -> d.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.US).lowercase()
    }
}

/** One line per event for the read_calendar tool: `standup, today 9 am, Room 4`. */
fun formatEvents(events: List<CalEvent>, zone: ZoneId, now: Long): String =
    if (events.isEmpty()) "no upcoming events"
    else events.joinToString("\n") {
        val whenText = if (it.allDay) "${dayWord(it.startMs, now, zone)}, all day" else "${dayWord(it.startMs, now, zone)} ${spokenTime(it.startMs, zone)}"
        "${it.title.ifBlank { "(no title)" }}, $whenText" + (it.location?.takeIf { l -> l.isNotBlank() }?.let { l -> ", $l" } ?: "")
    }

/** One line per notification for the read_notifications tool, newest last: `whatsapp, Sam: are we still on?`. */
fun formatNotifications(list: List<Notif>, zone: ZoneId): String =
    if (list.isEmpty()) "no notifications"
    else list.joinToString("\n") {
        val body = listOf(it.title, it.text).filter { s -> s.isNotBlank() }.joinToString(": ").take(160)
        "${spokenTime(it.at, zone)} ${it.app}, $body"
    }

fun deviceStatusText(batteryPercent: Int?, charging: Boolean, now: ZonedDateTime, wifi: Boolean?, ringer: String): String = listOfNotNull(
    "time: ${spokenTime(now.toInstant().toEpochMilli(), now.zone)}, ${now.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.US).lowercase()} ${now.toLocalDate()}",
    batteryPercent?.let { "battery: $it%${if (charging) ", charging" else ""}" },
    wifi?.let { "wifi: ${if (it) "on" else "off"}" },
    "ringer: $ringer",
).joinToString("\n")

/** An ISO date or date-time (with or without an offset or Z) as epoch millis; a bare date means 9 am. Null if unreadable. */
fun parseIso(s: String, zone: ZoneId): Long? {
    val t = s.trim()
    return try { OffsetDateTime.parse(t).toInstant().toEpochMilli() } catch (e: Exception) {
        try { LocalDateTime.parse(t).atZone(zone).toInstant().toEpochMilli() } catch (e: Exception) {
            try { LocalDate.parse(t).atTime(9, 0).atZone(zone).toInstant().toEpochMilli() } catch (e: Exception) { null }
        }
    }
}

/** A short spoken summary from whatever sources are available: null means "not allowed", and is skipped silently. */
fun composeBriefing(
    now: ZonedDateTime,
    batteryPercent: Int?,
    charging: Boolean,
    events: List<CalEvent>?,
    notifications: List<Notif>?,
): String {
    val zone = now.zone
    val nowMs = now.toInstant().toEpochMilli()
    val parts = mutableListOf("it's ${spokenTime(nowMs, zone)} on ${now.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.US).lowercase()}.")
    if (events != null) {
        val next = events.take(3)
        parts += when (next.size) {
            0 -> "nothing coming up on your calendar."
            1 -> "next up: ${eventSpoken(next[0], zone, nowMs)}."
            else -> "you have ${events.size} coming up: ${next.joinToString(", then ") { eventSpoken(it, zone, nowMs) }}."
        }
    }
    if (notifications != null) {
        if (notifications.isEmpty()) parts += "no new notifications."
        else {
            val top = notifications.groupingBy { it.app }.eachCount().maxByOrNull { it.value }!!.key
            val latest = notifications.maxByOrNull { it.at }!!
            val said = listOf(latest.title, latest.text).filter { it.isNotBlank() }.joinToString(", ").take(70)
            parts += "${notifications.size} notification${if (notifications.size == 1) "" else "s"}" +
                (if (notifications.size > 1) ", mostly $top" else "") + ". latest: ${latest.app}${if (said.isNotEmpty()) ", $said" else ""}."
        }
    }
    if (batteryPercent != null && batteryPercent <= 20 && !charging) parts += "battery is at $batteryPercent percent."
    return parts.joinToString(" ").lowercase()
}

private fun eventSpoken(e: CalEvent, zone: ZoneId, now: Long) =
    "${e.title.ifBlank { "an event" }} ${if (e.allDay) dayWord(e.startMs, now, zone) + " all day" else "at ${spokenTime(e.startMs, zone)}"}"

/** Tools that only look. Anything else a request ran counts as an action. */
private val READ_ONLY_TOOLS = setOf("screenshot", "zoom", "mouse_move", "wait", "list_apps", "read_calendar", "read_notifications", "device_status", "default_apps")

fun isActionTool(name: String) = name !in READ_ONLY_TOOLS

/**
 * After a spoken answer Buddy can listen a few seconds for a follow-up, but not after a plain action confirmation
 * ("done"), unless that reply is itself a question (e.g. asking for confirmation) the user needs to answer.
 */
fun shouldOpenFollowUp(enabled: Boolean, spokeAnswer: Boolean, actionsRan: Boolean, reply: String): Boolean =
    enabled && spokeAnswer && reply.isNotBlank() && (!actionsRan || reply.trimEnd().endsWith("?"))

enum class FollowUpState { CLOSED, WAITING, HEARING }

/** The follow-up listening window: opens after an answer, closes on silence, a tap, or once the user has spoken. */
class FollowUp {
    var state = FollowUpState.CLOSED
        private set

    val isOpen get() = state != FollowUpState.CLOSED

    fun open() { state = FollowUpState.WAITING }

    /** The user began talking: the time limit no longer applies. */
    fun speechStarted() { if (state == FollowUpState.WAITING) state = FollowUpState.HEARING }

    /** The listening time ran out. True if that closed the window (not if the user is mid-sentence). */
    fun timeout(): Boolean { if (state == FollowUpState.WAITING) { state = FollowUpState.CLOSED; return true }; return false }

    fun close() { state = FollowUpState.CLOSED }
}
