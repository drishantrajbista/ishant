package dev.buddy

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

const val CLAUDE_PACKAGE = "com.anthropic.claude"

@Suppress("DEPRECATION")
fun Context.isClaudeAppInstalled() = try {
    packageManager.getPackageInfo(CLAUDE_PACKAGE, 0)
    true
} catch (e: PackageManager.NameNotFoundException) {
    false
}

fun Context.openClaudeInPlayStore() {
    fun view(uri: String) = Intent(Intent.ACTION_VIEW, Uri.parse(uri)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        startActivity(view("market://details?id=$CLAUDE_PACKAGE"))
    } catch (e: ActivityNotFoundException) {
        startActivity(view("https://play.google.com/store/apps/details?id=$CLAUDE_PACKAGE"))
    }
}

/**
 * Hands the screenshot (and, if given, the question text) to the Claude app's share target. The text is also put on
 * the clipboard as a fallback in case the app ignores shared text that comes with an image.
 * Throws [ActivityNotFoundException] if the Claude app isn't installed or doesn't accept shared images.
 */
suspend fun Context.shareToClaudeApp(jpeg: ByteArray, text: String?) {
    val file = withContext(Dispatchers.IO) {
        File(cacheDir, "shots").apply { mkdirs() }.resolve("screen.jpg").also { it.writeBytes(jpeg) }
    }
    val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
    if (text != null) {
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            .setPrimaryClip(ClipData.newPlainText("Buddy question", text))
    }
    val intent = Intent(Intent.ACTION_SEND)
        .setType("image/jpeg")
        .putExtra(Intent.EXTRA_STREAM, uri)
        .setPackage(CLAUDE_PACKAGE)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
    if (text != null) intent.putExtra(Intent.EXTRA_TEXT, text)
    intent.clipData = ClipData.newRawUri("screenshot", uri)
    startActivity(intent)
}
