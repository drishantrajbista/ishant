package dev.buddy

import android.content.Context
import android.os.SystemClock
import androidx.core.content.pm.PackageInfoCompat
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.Request
import org.json.JSONObject

class UpdateInfo(val versionCode: Long, val versionName: String, val apk: String, val notes: String)

/** `{"versionCode": 12, "versionName": "1.7", "apk": "https://...", "notes": "..."}`; null if it isn't that. */
fun parseUpdate(json: String): UpdateInfo? = try {
    val o = JSONObject(json)
    UpdateInfo(o.getLong("versionCode"), o.getString("versionName"), o.getString("apk"), o.optString("notes"))
} catch (e: Exception) { null }

fun isNewer(remoteCode: Long, localCode: Long) = remoteCode > localCode

/** Sideload builds look for a newer APK about once an hour. The install itself is the browser opening the APK link. */
object UpdateCheck {
    const val URL = "https://raw.githubusercontent.com/drishantrajbista/ishant/ccr-574dfcd3-b7hcm6/android-buddy/release/version.json"
    private const val EVERY_MS = 60 * 60_000L
    private var lastAt = 0L
    val available = MutableStateFlow<UpdateInfo?>(null)

    suspend fun maybeCheck(context: Context) {
        if (!BuildConfig.UPDATE_CHECK) return   // Play builds must not update themselves
        val now = SystemClock.elapsedRealtime()
        if (lastAt != 0L && now - lastAt < EVERY_MS) return
        lastAt = now
        try {
            val json = httpText(Request.Builder().url(URL).build())
            val info = parseUpdate(json) ?: return
            val local = PackageInfoCompat.getLongVersionCode(context.packageManager.getPackageInfo(context.packageName, 0))
            available.value = if (isNewer(info.versionCode, local)) info else null
        } catch (e: Exception) { lastAt = 0L }   // offline: try again next time
    }
}
