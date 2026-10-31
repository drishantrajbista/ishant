package dev.buddy

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Where Buddy's own saved screenshots go (the system's screenshots land wherever the phone puts them). */
const val GALLERY_FOLDER = "Pictures/Buddy"

private val FILE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")

/** "Buddy_20261031_154500.png": the local time to the second. (MediaStore adds " (1)" itself if two share a name.) */
fun screenshotFileName(at: Long, zone: ZoneId = ZoneId.systemDefault()): String =
    "Buddy_${FILE_TIME.format(Instant.ofEpochMilli(at).atZone(zone))}.png"

/** Android 10+ can add pictures with MediaStore without any storage permission. */
val canSaveToGallery get() = Build.VERSION.SDK_INT >= 29

/** Saves [bitmap] as a PNG in [GALLERY_FOLDER]. Blocking (encoding takes a moment): call it off the main thread. Null if it failed. */
fun Context.saveToGallery(bitmap: Bitmap, at: Long = System.currentTimeMillis()): Uri? {
    if (!canSaveToGallery) return null
    val values = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, screenshotFileName(at))
        put(MediaStore.Images.Media.MIME_TYPE, "image/png")
        put(MediaStore.Images.Media.RELATIVE_PATH, GALLERY_FOLDER)
        put(MediaStore.Images.Media.IS_PENDING, 1)   // hidden from the gallery until it is complete
    }
    val resolver = contentResolver
    val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
    return try {
        resolver.openOutputStream(uri)?.use { out ->
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) throw IOException("couldn't encode the image")
        } ?: throw IOException("couldn't open the file")
        resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
        uri
    } catch (e: Exception) {
        runCatching { resolver.delete(uri, null, null) }   // no half-written picture left behind
        null
    }
}
