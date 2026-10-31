package dev.buddy

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast

/** Puts [text] on the clipboard. False if Android refuses. */
fun Context.copyToClipboard(text: String, label: String = "Buddy"): Boolean = try {
    (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText(label, text))
    true
} catch (e: RuntimeException) { false }

/** [copyToClipboard] plus a short "copied" toast. */
fun Context.copyWithToast(text: String): Boolean = copyToClipboard(text).also {
    Toast.makeText(this, if (it) "copied" else "couldn't copy", Toast.LENGTH_SHORT).show()
}
