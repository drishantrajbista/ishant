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
}

private val FILLER_START = Regex("^((hey )?buddy )?((can|could|would|will) you )?(please )?(kindly )?")
private val FILLER_END = Regex(" (please|for me|now)$")

/** Lowercase letters/digits/spaces only, with polite filler ("please", "can you", "for me") stripped. */
fun normalizeCommand(transcript: String): String =
    transcript.lowercase().replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()
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

/** Matches the handful of phrases that can run locally; [labels] (the launchable apps) is only read for "open <app>". */
fun routeLocal(transcript: String, labels: () -> List<String>): LocalCommand? {
    val t = normalizeCommand(transcript)
    if (t.isEmpty()) return null
    Regex("^(go (to )?(the )?)?home( screen)?$").find(t)?.let { return LocalCommand.Nav("home") }
    if (Regex("^(go )?back$").matches(t)) return LocalCommand.Nav("back")
    if (Regex("^(show |open )?(the )?recents?( apps)?$").matches(t) || t == "recent apps") return LocalCommand.Nav("recents")
    if (Regex("^(open|show|pull down) (the )?notifications?( panel| shade)?$").matches(t)) return LocalCommand.Nav("notifications")
    if (Regex("^(open|show|pull down) (the )?quick settings( panel)?$").matches(t)) return LocalCommand.Nav("quick_settings")

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

    Regex("^(?:open|show) (?:the )?(wifi|wi fi|bluetooth|display|battery|sound|location) settings$").find(t)?.let {
        return LocalCommand.OpenSettings(SETTINGS_WORDS.getValue(it.groupValues[1]))
    }
    Regex("^(?:open|launch|start) (?:the )?(.+?)(?: app)?$").find(t)?.let { m ->
        val name = m.groupValues[1]
        if (name.split(' ').size <= 4) confidentApp(name, labels())?.let { return LocalCommand.OpenApp(it) }
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

fun Context.changeVolume(action: String): Boolean {
    val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
    val stream = AudioManager.STREAM_MUSIC
    when (action) {
        "up" -> am.adjustStreamVolume(stream, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
        "down" -> am.adjustStreamVolume(stream, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
        "mute" -> am.adjustStreamVolume(stream, AudioManager.ADJUST_MUTE, AudioManager.FLAG_SHOW_UI)
        "max" -> am.setStreamVolume(stream, am.getStreamMaxVolume(stream), AudioManager.FLAG_SHOW_UI)
        else -> return false
    }
    return true
}
