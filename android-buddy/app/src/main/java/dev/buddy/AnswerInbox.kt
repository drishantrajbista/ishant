package dev.buddy

import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.widget.Toast

/** A Claude answer shared to Buddy ("Show with Buddy"), waiting for the user to tap the bubble. */
object AnswerInbox {
    private const val MAX_AGE_MS = 10 * 60_000L
    private var text: String? = null
    private var at = 0L

    @Synchronized fun put(t: String?) { text = t?.takeIf { it.isNotBlank() }; at = SystemClock.elapsedRealtime() }

    @Synchronized fun take(): String? =
        text?.takeIf { SystemClock.elapsedRealtime() - at < MAX_AGE_MS }.also { text = null }
}

/**
 * Invisible and instant: it exists only to get window focus, because Android 10+ lets only the focused app read
 * the clipboard. It reads Claude's copied answer (or a shared one), hands it to the service and goes away, so the
 * user stays on the app they were in.
 */
class ClipboardActivity : Activity() {
    private var done = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION") overridePendingTransition(0, 0)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus || done) return
        done = true
        val clip = (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip
        val text = AnswerInbox.take() ?: clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString().orEmpty()
        if (BuddyService.running.value) {
            startService(
                Intent(this, BuddyService::class.java)
                    .setAction(BuddyService.ACTION_ANSWER_TEXT)
                    .putExtra(BuddyService.EXTRA_TEXT, text),
            )
        }
        finish()
        @Suppress("DEPRECATION") overridePendingTransition(0, 0)
    }
}

/** "Show with Buddy" in Claude's share sheet: stores the shared text until the user taps the bubble. */
class ShareTargetActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = intent?.getStringExtra(Intent.EXTRA_TEXT)
        if (text.isNullOrBlank()) {
            Toast.makeText(this, "nothing to show", Toast.LENGTH_SHORT).show()
        } else {
            AnswerInbox.put(text)
            Toast.makeText(this, "got claude's answer, go back to your app and tap the bubble", Toast.LENGTH_LONG).show()
        }
        finish()
    }
}
