package dev.buddy

import android.Manifest
import android.app.SearchManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.view.KeyEvent
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import java.time.ZoneId
import java.time.ZonedDateTime

/** Android helpers behind the alarm, timer, calendar, media, search and status tools (and the instant local commands). */

private fun Context.start(intent: Intent) {
    try { startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    catch (e: ActivityNotFoundException) { throw ToolError("nothing on this phone can do that") }
}

fun Context.setAlarm(hour: Int, minute: Int, label: String?) {
    if (hour !in 0..23 || minute !in 0..59) throw ToolError("hour must be 0-23 and minute 0-59")
    start(
        Intent(AlarmClock.ACTION_SET_ALARM).putExtra(AlarmClock.EXTRA_HOUR, hour).putExtra(AlarmClock.EXTRA_MINUTES, minute)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true).apply { if (!label.isNullOrBlank()) putExtra(AlarmClock.EXTRA_MESSAGE, label) },
    )
}

fun Context.setTimer(seconds: Int, label: String?) {
    if (seconds !in 1..86_400) throw ToolError("seconds must be 1 to 86400")
    start(
        Intent(AlarmClock.ACTION_SET_TIMER).putExtra(AlarmClock.EXTRA_LENGTH, seconds).putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            .apply { if (!label.isNullOrBlank()) putExtra(AlarmClock.EXTRA_MESSAGE, label) },
    )
}

/** Opens the calendar app's new-event screen filled in: the user confirms there, so no calendar write permission. */
fun Context.insertEvent(title: String, startMs: Long, endMs: Long, location: String?) = start(
    Intent(Intent.ACTION_INSERT).setData(CalendarContract.Events.CONTENT_URI)
        .putExtra(CalendarContract.Events.TITLE, title)
        .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, startMs)
        .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, endMs)
        .apply { if (!location.isNullOrBlank()) putExtra(CalendarContract.Events.EVENT_LOCATION, location) },
)

fun Context.webSearch(query: String) {
    try { start(Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, query)) }
    catch (e: ToolError) { start(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + Uri.encode(query)))) }
}

/** Play/pause/next/previous as a media key press, so whatever player is active responds. */
fun Context.mediaKey(action: String): Boolean {
    val code = when (action) {
        "play" -> KeyEvent.KEYCODE_MEDIA_PLAY
        "pause" -> KeyEvent.KEYCODE_MEDIA_PAUSE
        "next" -> KeyEvent.KEYCODE_MEDIA_NEXT
        "previous" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
        "playpause" -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
        else -> return false
    }
    val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
    am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
    am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
    return true
}

fun Context.deviceStatus(): String {
    val battery = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
    val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
    val status = battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
    val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
    val wifi = try { (applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager).isWifiEnabled } catch (e: Exception) { null }
    val ringer = when ((getSystemService(Context.AUDIO_SERVICE) as AudioManager).ringerMode) {
        AudioManager.RINGER_MODE_SILENT -> "silent"
        AudioManager.RINGER_MODE_VIBRATE -> "vibrate"
        else -> "normal"
    }
    val percent = if (level >= 0 && scale > 0) level * 100 / scale else null
    return deviceStatusText(percent, charging, ZonedDateTime.now(), wifi, ringer)
}

fun Context.batteryInfo(): Pair<Int?, Boolean> {
    val b = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    val level = b?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
    val scale = b?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
    val status = b?.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
    return (if (level >= 0 && scale > 0) level * 100 / scale else null) to
        (status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL)
}

fun Context.hasCalendarPermission() =
    ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

fun Context.notificationAccessEnabled() = packageName in NotificationManagerCompat.getEnabledListenerPackages(this)

/** The next [max] events in the coming week (read-only; needs READ_CALENDAR, which the user grants in Buddy's settings). */
fun Context.upcomingEvents(max: Int): List<CalEvent> {
    if (!hasCalendarPermission()) throw ToolError("calendar access isn't allowed. ask the user to allow it in buddy's settings")
    val now = System.currentTimeMillis()
    val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
        android.content.ContentUris.appendId(it, now)
        android.content.ContentUris.appendId(it, now + 7 * 24 * 3600_000L)
    }.build()
    val out = mutableListOf<CalEvent>()
    contentResolver.query(
        uri,
        arrayOf(CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN, CalendarContract.Instances.END,
            CalendarContract.Instances.EVENT_LOCATION, CalendarContract.Instances.ALL_DAY),
        null, null, "${CalendarContract.Instances.BEGIN} ASC",
    )?.use { c ->
        while (c.moveToNext() && out.size < max.coerceIn(1, 20)) {
            out += CalEvent(c.getString(0).orEmpty(), c.getLong(1), c.getLong(2), c.getString(3), c.getInt(4) == 1)
        }
    }
    return out
}

internal val zone: ZoneId get() = ZoneId.systemDefault()
