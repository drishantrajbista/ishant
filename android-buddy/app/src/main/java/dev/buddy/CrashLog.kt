package dev.buddy

import android.app.Application
import android.content.Context
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

private const val DELIMITER = "=== crash ==="

/** Removes anything that looks like an Anthropic key, defensively: it should never be in a stack trace anyway. */
fun scrubSecrets(text: String): String = text.replace(Regex("sk-ant-[A-Za-z0-9_\\-]+"), "sk-ant-…")

/** One compact crash record: when, which build, which phone, which thread, the exception and its top frames. */
fun formatCrash(
    time: String, versionName: String, versionCode: Long, device: String, android: String,
    thread: String, error: Throwable, maxFrames: Int = 20,
): String = scrubSecrets(buildString {
    appendLine(DELIMITER)
    appendLine("time: $time")
    appendLine("app: $versionName ($versionCode)")
    appendLine("device: $device")
    appendLine("android: $android")
    appendLine("thread: $thread")
    appendLine("exception: ${error.javaClass.name}: ${(error.message ?: "").take(300)}")
    error.stackTrace.take(maxFrames).forEach { appendLine("  at $it") }
    if (error.stackTrace.size > maxFrames) appendLine("  … ${error.stackTrace.size - maxFrames} more")
    var cause = error.cause
    var depth = 0
    while (cause != null && depth < 3) {
        appendLine("caused by: ${cause.javaClass.name}: ${(cause.message ?: "").take(200)}")
        cause.stackTrace.take(5).forEach { appendLine("  at $it") }
        cause = cause.cause; depth++
    }
})

/** Adds [record] to the log text, keeping only the last [keep] crashes. */
fun appendCrash(existing: String, record: String, keep: Int = 3): String {
    val all = (existing.split(DELIMITER).map { it.trim() }.filter { it.isNotEmpty() } + record.removePrefix(DELIMITER).trim()).takeLast(keep)
    return all.joinToString("\n") { "$DELIMITER\n$it\n" }
}

/** `filesDir/crash.log`, the last few crashes of this app. Written by [BuddyApplication]'s uncaught-exception handler. */
object CrashLog {
    private fun file(context: Context) = File(context.filesDir, "crash.log")

    fun read(context: Context): String = try { file(context).takeIf { it.exists() }?.readText().orEmpty() } catch (e: Throwable) { "" }

    fun write(context: Context, thread: Thread, error: Throwable) {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        val clock = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        val record = formatCrash(
            clock.format(Date()), info.versionName.orEmpty(), PackageInfoCompat.getLongVersionCode(info),
            "${Build.MANUFACTURER} ${Build.MODEL}", "Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})",
            thread.name, error,
        )
        file(context).writeText(appendCrash(read(context), record))
    }
}

/** Installs the crash recorder as early as possible; it then hands the crash on so Android still handles it normally. */
class BuddyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try { CrashLog.write(this, thread, error) } catch (_: Throwable) { /* the recorder must never crash */ }
            previous?.uncaughtException(thread, error)
        }
    }
}
