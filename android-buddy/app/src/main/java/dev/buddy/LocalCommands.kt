package dev.buddy

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager

/** Simple spoken commands that need no AI: they run instantly on the phone. */
sealed class LocalCommand {
    data class OpenApp(val label: String) : LocalCommand()
    data class Nav(val action: String) : LocalCommand()
    data class Torch(val on: Boolean) : LocalCommand()
    data class Volume(val action: String) : LocalCommand()
    data class OpenSettings(val page: String) : LocalCommand()
    data class Alarm(val hour: Int, val minute: Int) : LocalCommand()
    data class Timer(val seconds: Int) : LocalCommand()
    data class Media(val action: String) : LocalCommand()
    object Briefing : LocalCommand()
}

/** "7:30 am" for an alarm's hour and minute (24-hour in). */
fun clockText(hour: Int, minute: Int): String =
    "${(hour % 12).let { if (it == 0) 12 else it }}:%02d ${if (hour < 12) "am" else "pm"}".format(minute)

/** "5 minutes", "1 hour 30 minutes", "45 seconds". */
fun durationText(seconds: Int): String {
    val h = seconds / 3600; val m = seconds % 3600 / 60; val s = seconds % 60
    fun unit(n: Int, w: String) = "$n $w${if (n == 1) "" else "s"}"
    return listOfNotNull(h.takeIf { it > 0 }?.let { unit(it, "hour") }, m.takeIf { it > 0 }?.let { unit(it, "minute") }, s.takeIf { it > 0 }?.let { unit(it, "second") })
        .joinToString(" ").ifEmpty { "0 seconds" }
}

/** Total seconds in phrases like "5 minutes" or "1 hour 30 minutes"; null if there is no duration. */
fun parseDuration(t: String): Int? {
    val matches = Regex("(\\d+) ?(hours?|hrs?|minutes?|mins?|seconds?|secs?)\\b").findAll(t).toList()
    if (matches.isEmpty()) return null
    return matches.sumOf {
        val n = it.groupValues[1].toInt()
        n * when (it.groupValues[2][0]) { 'h' -> 3600; 'm' -> 60; else -> 1 }
    }.takeIf { it in 1..86_400 }
}

/** (hour, minute) 24-hour from "7 am", "6 30 pm", or an unambiguous 24-hour hour (13-23); null if ambiguous. */
fun parseClock(hourText: String, minuteText: String, ampm: String): Pair<Int, Int>? {
    var h = hourText.toIntOrNull() ?: return null
    val m = minuteText.ifEmpty { "0" }.toIntOrNull() ?: return null
    if (m !in 0..59) return null
    when (ampm) {
        "am" -> { if (h !in 1..12) return null; if (h == 12) h = 0 }
        "pm" -> { if (h !in 1..12) return null; if (h != 12) h += 12 }
        else -> if (h !in 13..23) return null
    }
    return h to m
}

private val MEDIA_PHRASES = mapOf(
    "pause" to "pause", "pause music" to "pause", "pause the music" to "pause", "stop the music" to "pause", "stop music" to "pause",
    "play" to "play", "play music" to "play", "play the music" to "play", "resume" to "play", "resume music" to "play",
    "play pause" to "playpause",
    "next song" to "next", "next track" to "next", "skip" to "next", "skip song" to "next", "skip this song" to "next", "skip track" to "next",
    "previous song" to "previous", "previous track" to "previous", "last song" to "previous", "go to the previous song" to "previous",
)

private val FILLER_START = Regex("^((hey )?buddy )?((can|could|would|will) you )?(please )?(kindly )?")
private val FILLER_END = Regex(" (please|for me|now)$")

/** Lowercase letters/digits/spaces only, with polite filler ("please", "can you", "for me") stripped. */
fun normalizeCommand(transcript: String): String =
    transcript.lowercase().replace(Regex("[^a-z0-9\\u0900-\\u097F ]"), " ").replace(Regex("\\s+"), " ").trim()
        .replace(FILLER_START, "").replace(FILLER_END, "").trim()

/**
 * An exact or prefix match, or similarity >= 0.85, of [name] among [labels]; null when unsure (then Claude handles it).
 */
fun confidentApp(name: String, labels: List<String>): String? {
    val q = norm(name)
    if (q.length < 3) return null
    val normed = labels.distinct().map { it to norm(it) }.filter { it.second.isNotEmpty() }
    normed.firstOrNull { it.second == q }?.let { return it.first }
    if (q.length >= 4) normed.filter { it.second.startsWith(q) }.minByOrNull { it.second.length }?.let { return it.first }
    return normed.map { it to 1.0 - edit(q, it.second).toDouble() / maxOf(q.length, it.second.length) }
        .filter { it.second >= 0.85 }.maxByOrNull { it.second }?.first?.first
}

private val SETTINGS_WORDS = mapOf("wifi" to "wifi", "wi fi" to "wifi", "bluetooth" to "bluetooth", "display" to "display",
    "battery" to "battery", "sound" to "sound", "location" to "location")

// Nepali (Romanized or Devanagari): a small, conservative set. Anything unsure goes to Claude.
internal val OPEN_WORDS = "khola|kholdeu|kholnu|khol|chalau|खोलदेउ|खोलनु|खोल"
private val ON_WORDS = setOf("on", "chalu", "चालु", "अन", "chalau")
private val OFF_WORDS = setOf("off", "band", "bandh", "बन्द", "अफ")
internal val TORCH_WORDS = setOf("torch", "flashlight", "टर्च", "फ्लैशलाइट")
private val FILL_WORDS = setOf("gara", "garnu", "deu", "dinu", "the", "turn", "switch", "गर", "गरनु", "देउ")

/** "torch on gara", "flashlight band karo", "torch chalu": a torch word plus exactly one of on/off. */
private fun multilingualTorch(t: String): LocalCommand.Torch? {
    val words = t.split(' ')
    if (words.none { it in TORCH_WORDS } || words.size > 5) return null
    if (!words.all { it in TORCH_WORDS || it in ON_WORDS || it in OFF_WORDS || it in FILL_WORDS }) return null
    val on = words.any { it in ON_WORDS }
    val off = words.any { it in OFF_WORDS }
    return if (on != off) LocalCommand.Torch(on) else null
}

/** Matches the handful of phrases that can run locally; [labels] (the launchable apps) is only read for "open <app>". */
fun routeLocal(transcript: String, labels: () -> List<String>): LocalCommand? {
    val t = normalizeCommand(transcript)
    if (t.isEmpty()) return null
    Regex("^(go (to )?(the )?)?home( screen)?$").find(t)?.let { return LocalCommand.Nav("home") }
    if (Regex("^(go )?back$").matches(t)) return LocalCommand.Nav("back")
    if (Regex("^(show |open )?(the )?recents?( apps)?$").matches(t) || t == "recent apps") return LocalCommand.Nav("recents")
    if (Regex("^(open|show|pull down) (the )?notifications?( panel| shade)?$").matches(t)) return LocalCommand.Nav("notifications")
    if (Regex("^(open|show|pull down) (the )?quick settings( panel)?$").matches(t)) return LocalCommand.Nav("quick_settings")

    multilingualTorch(t)?.let { return it }
    Regex("^(pachadi|पछाडि)( (jau|जाउ))?$").find(t)?.let { return LocalCommand.Nav("back") }
    Regex("^(home jau|होम( जाउ)?)$").find(t)?.let { return LocalCommand.Nav("home") }
    if (Regex("^volume (badhau|बढाउ)$").matches(t)) return LocalCommand.Volume("up")
    if (Regex("^volume (ghatau|घटाउ)$").matches(t)) return LocalCommand.Volume("down")
    if (Regex("^volume (band|bandh)( gara)?$").matches(t)) return LocalCommand.Volume("mute")
    Regex("^(?:(?:turn|switch) (on|off) (?:the )?|(?:turn|switch) (?:the )?)?(?:flashlight|torch)(?: (on|off))?$").find(t)?.let { m ->
        val state = m.groupValues[1].ifEmpty { m.groupValues[2] }
        if (state.isNotEmpty()) return LocalCommand.Torch(state == "on")
    }
    Regex("^(?:turn |set )?(?:the )?volume (up|down|mute|max|maximum)$").find(t)?.let {
        return LocalCommand.Volume(if (it.groupValues[1] == "maximum") "max" else it.groupValues[1])
    }
    Regex("^(?:turn )?(up|down) the volume$").find(t)?.let { return LocalCommand.Volume(it.groupValues[1]) }
    Regex("^(mute|(?:max|maximum) volume)(?: the)?(?: phone| sound| volume)?$").find(t)?.let {
        return LocalCommand.Volume(if (it.groupValues[1] == "mute") "mute" else "max")
    }
    if (t == "louder") return LocalCommand.Volume("up")
    if (t == "quieter") return LocalCommand.Volume("down")

    if (Regex("^(whats|what s|what is) my day$|^good morning$|^what did i miss$|^(my |daily )?briefing$|^give me (my |a |the )?(daily )?briefing$|^brief me$").matches(t)) {
        return LocalCommand.Briefing
    }
    MEDIA_PHRASES[t]?.let { return LocalCommand.Media(it) }
    if ("timer" in t && t.split(' ').size <= 9) parseDuration(t)?.let { return LocalCommand.Timer(it) }
    Regex("^(?:set (?:an )?alarm (?:for|at)|alarm (?:for|at)|wake me up at|wake me at) (\\d{1,2})(?: (\\d{2}))?(?: (am|pm))?$").find(t)?.let { m ->
        parseClock(m.groupValues[1], m.groupValues[2], m.groupValues[3])?.let { return LocalCommand.Alarm(it.first, it.second) }
    }
    Regex("^(?:open|show) (?:the )?(wifi|wi fi|bluetooth|display|battery|sound|location) settings$").find(t)?.let {
        return LocalCommand.OpenSettings(SETTINGS_WORDS.getValue(it.groupValues[1]))
    }
    Regex("^(?:open|launch|start) (?:the )?(.+?)(?: app)?$").find(t)?.let { m ->
        val name = m.groupValues[1]
        if (name.split(' ').size <= 4) confidentApp(name, labels())?.let { return LocalCommand.OpenApp(it) }
    }
    // "youtube kholo", "kholo youtube", "gmail chalau": the verb comes last (or first) in Nepali
    (Regex("^(.+?) (?:$OPEN_WORDS)$").find(t) ?: Regex("^(?:$OPEN_WORDS) (.+)$").find(t))?.let { m ->
        val name = m.groupValues[1].removeSuffix(" app").removePrefix("app ")
        if (name.split(' ').size <= 3 && name.split(' ').none { it in TORCH_WORDS }) confidentApp(name, labels())?.let { return LocalCommand.OpenApp(it) }
    }
    return null
}

/** Launchable apps by label. Needs the launcher `<queries>` entry in the manifest to see them all. */
fun Context.launchableApps(): Map<String, ResolveInfo> {
    val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    return packageManager.queryIntentActivities(launcher, PackageManager.MATCH_ALL).associateBy { it.loadLabel(packageManager).toString() }
}

fun Context.launchApp(info: ResolveInfo) = startActivity(
    Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        .setClassName(info.activityInfo.packageName, info.activityInfo.name)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED),
)

/** Torch on/off through the camera service (no camera permission needed). False if there's no torch. */
fun Context.setTorch(on: Boolean): Boolean = try {
    val cm = getSystemService(Context.CAMERA_SERVICE) as CameraManager
    val id = cm.cameraIdList.firstOrNull { cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
    if (id != null) { cm.setTorchMode(id, on); true } else false
} catch (e: Exception) { false }

fun Context.changeVolume(action: String): Boolean = try {
    val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
    val stream = AudioManager.STREAM_MUSIC
    when (action) {
        "up" -> am.adjustStreamVolume(stream, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
        "down" -> am.adjustStreamVolume(stream, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
        "mute" -> am.adjustStreamVolume(stream, AudioManager.ADJUST_MUTE, AudioManager.FLAG_SHOW_UI)
        "max" -> am.setStreamVolume(stream, am.getStreamMaxVolume(stream), AudioManager.FLAG_SHOW_UI)
        else -> throw IllegalArgumentException(action)
    }
    true
} catch (e: RuntimeException) { false }   // unknown action, or a Do Not Disturb restriction
