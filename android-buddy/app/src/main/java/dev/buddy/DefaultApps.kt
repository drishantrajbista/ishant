package dev.buddy

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.MediaStore

/** The app the user has chosen for a kind of task; [label] is null when no default is set. */
data class DefaultApp(val kind: String, val label: String?, val pkg: String?)

/** When several apps could handle an intent and none is the default, Android answers with its own chooser. */
fun isChooser(pkg: String?, activity: String?): Boolean =
    pkg == "android" || pkg.isNullOrEmpty() || activity?.let { "Resolver" in it || "Chooser" in it } == true

/** One line per kind for the default_apps tool: `browser: Chrome (com.android.chrome)` or `maps: no default set`. */
fun formatDefaultApps(apps: List<DefaultApp>): String = apps.joinToString("\n") {
    "${it.kind}: " + (if (it.label != null) "${it.label} (${it.pkg})" else "no default set")
}

/** A short block about the phone, prepended to the first message of a conversation. At most ~400 characters. */
fun phoneContext(device: String, android: String, foreground: String?, defaults: List<DefaultApp>): String {
    val set = defaults.filter { it.label != null }.joinToString(", ") { "${it.kind} ${it.label}" }
    val text = listOfNotNull(
        "phone: ${device.trim()}, $android.",
        foreground?.takeIf { it.isNotBlank() }?.let { "foreground app: $it." },
        set.takeIf { it.isNotEmpty() }?.let { "default apps: $it." },
    ).joinToString(" ")
    return if (text.length <= MAX_CONTEXT) text else text.take(MAX_CONTEXT - 1) + "…"
}

const val MAX_CONTEXT = 400

/** Resolves the user's default app for the standard intents. Needs the matching `<queries>` entries in the manifest. */
fun Context.defaultApps(): List<DefaultApp> {
    val pm = packageManager
    fun main(category: String) = Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, category)
    val intents = listOf(
        "browser" to Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com")),
        "dialer" to Intent(Intent.ACTION_DIAL, Uri.parse("tel:")),
        "messages" to Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:")),
        "email" to Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:")),
        "camera" to Intent(MediaStore.ACTION_IMAGE_CAPTURE),
        "maps" to Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0")),
        "music" to main(Intent.CATEGORY_APP_MUSIC),
        "gallery" to main(Intent.CATEGORY_APP_GALLERY),
        "launcher" to Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
    )
    return intents.map { (kind, intent) ->
        val info = try { pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY) } catch (e: RuntimeException) { null }
        val pkg = info?.activityInfo?.packageName
        if (info == null || isChooser(pkg, info.activityInfo?.name)) DefaultApp(kind, null, null)
        else DefaultApp(kind, info.loadLabel(pm).toString(), pkg)
    }
}

/** The label of the app in the foreground (from the last window-change event), or its package name. */
fun Context.appLabel(pkg: String?): String? {
    if (pkg.isNullOrBlank()) return null
    return try { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString() } catch (e: Exception) { pkg }
}
