package dev.buddy

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Claude's eyes and hands: reads the screen, takes screenshots (Android 11+), taps, swipes and types. */
class BuddyAccessibilityService : AccessibilityService() {
    @Volatile private var lastEventAt = 0L

    override fun onServiceConnected() { try { instance = this } catch (e: Throwable) { android.util.Log.e("Buddy", "onServiceConnected", e) } }

    override fun onUnbind(intent: Intent?): Boolean { instance = null; return try { super.onUnbind(intent) } catch (e: Throwable) { false } }

    override fun onDestroy() { instance = null; super.onDestroy() }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        try { lastEventAt = SystemClock.uptimeMillis() } catch (e: Throwable) { /* ultra-light and never throws */ }
    }

    override fun onInterrupt() { try { acted() } catch (e: Throwable) { android.util.Log.e("Buddy", "onInterrupt", e) } }

    /** After an action: wait until no window events for 300 ms (at most 2 s) rather than sleeping a fixed time. */
    suspend fun settle() {
        val start = SystemClock.uptimeMillis()
        while (true) {
            val now = SystemClock.uptimeMillis()
            val quiet = now - lastEventAt
            if (quiet >= QUIET_MS || now - start >= SETTLE_MAX_MS) return
            delay(minOf(QUIET_MS - quiet, 50))
        }
    }

    fun acted() { lastEventAt = SystemClock.uptimeMillis() }

    /** The screen at full resolution (Android 11+). Retries once the rate limit allows another screenshot. */
    suspend fun frame(): Bitmap {
        if (Build.VERSION.SDK_INT < 30) throw ToolError("screenshots through accessibility need Android 11")
        repeat(4) {
            try {
                return takeFrame()
            } catch (e: ScreenshotFailed) {
                if (e.code == TOO_SOON) delay(350)
                else throw ToolError(if (e.code == SECURE_WINDOW) "this screen blocks screenshots" else "screenshot failed (code ${e.code})")
            }
        }
        throw ToolError("screenshot failed: too many requests")
    }

    private class ScreenshotFailed(val code: Int) : Exception()

    private suspend fun takeFrame(): Bitmap = suspendCancellableCoroutine { cont ->
        takeScreenshot(Display.DEFAULT_DISPLAY, Dispatchers.Default.asExecutor(), object : TakeScreenshotCallback {
            override fun onSuccess(result: ScreenshotResult) {
                try {
                    val hardware = Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)!!
                    cont.resume(hardware.copy(Bitmap.Config.ARGB_8888, false))
                    hardware.recycle()
                } catch (e: Exception) {
                    cont.resumeWithException(e)
                } finally {
                    result.hardwareBuffer.close()
                }
            }
            override fun onFailure(errorCode: Int) = cont.resumeWithException(ScreenshotFailed(errorCode))
        })
    }

    // region gestures

    private suspend fun gesture(vararg strokes: GestureDescription.StrokeDescription): Boolean =
        suspendCancellableCoroutine { cont ->
            val g = GestureDescription.Builder().apply { strokes.forEach(::addStroke) }.build()
            val sent = dispatchGesture(g, object : GestureResultCallback() {
                override fun onCompleted(d: GestureDescription?) { if (cont.isActive) cont.resume(true) }
                override fun onCancelled(d: GestureDescription?) { if (cont.isActive) cont.resume(false) }
            }, null)
            if (!sent && cont.isActive) cont.resume(false)
        }

    private fun at(x: Int, y: Int) = Path().apply { moveTo(x.toFloat(), y.toFloat()) }

    suspend fun tap(x: Int, y: Int) = gesture(GestureDescription.StrokeDescription(at(x, y), 0, 50))
    suspend fun longPress(x: Int, y: Int) = gesture(GestureDescription.StrokeDescription(at(x, y), 0, 600))
    suspend fun doubleTap(x: Int, y: Int) = gesture(
        GestureDescription.StrokeDescription(at(x, y), 0, 50),
        GestureDescription.StrokeDescription(at(x, y), 150, 50),
    )
    suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int) = gesture(
        GestureDescription.StrokeDescription(
            Path().apply { moveTo(x1.toFloat(), y1.toFloat()); lineTo(x2.toFloat(), y2.toFloat()) }, 0, 300,
        ),
    )

    // endregion

    // region typing and keys

    /** Types into the focused input, or the editable node under [lastTap]. Returns an error message, or null. */
    fun typeText(text: String, lastTap: Pair<Int, Int>?): String? {
        val root = rootInActiveWindow ?: return "no window to type into"
        val node = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: lastTap?.let { editableAt(root, it.first, it.second) }
            ?: return "no text field is focused. tap the field first"
        if (node.isPassword) return "password fields are blocked, ask the user to type it themselves"
        if (!node.isEditable) return "the focused element isn't a text field. tap the field first"
        val existing = if (node.isShowingHintText) "" else node.text?.toString().orEmpty()
        return setText(node, existing + text)
    }

    private fun setText(node: AccessibilityNodeInfo, text: String): String? {
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
        return if (node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) null else "the field didn't accept the text"
    }

    private fun editableAt(root: AccessibilityNodeInfo, x: Int, y: Int): AccessibilityNodeInfo? {
        var best: AccessibilityNodeInfo? = null
        var bestArea = Long.MAX_VALUE
        val r = Rect()
        fun walk(n: AccessibilityNodeInfo) {
            n.getBoundsInScreen(r)
            if (n.isEditable && r.contains(x, y) && r.width().toLong() * r.height() < bestArea) { best = n; bestArea = r.width().toLong() * r.height() }
            for (i in 0 until n.childCount) n.getChild(i)?.let(::walk)
        }
        walk(root)
        return best
    }

    fun pressKey(action: KeyAction, repeat: Int): String? {
        repeat(repeat.coerceIn(1, 100)) {
            val error = when (action) {
                KeyAction.BACK -> if (performGlobalAction(GLOBAL_ACTION_BACK)) null else "back failed"
                KeyAction.HOME -> if (performGlobalAction(GLOBAL_ACTION_HOME)) null else "home failed"
                else -> withFocusedInput(action)
            }
            if (error != null) return error
        }
        return null
    }

    private fun withFocusedInput(action: KeyAction): String? {
        val node = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return "no text field is focused. tap the field first"
        if (node.isPassword) return "password fields are blocked, ask the user to type it themselves"
        val text = if (node.isShowingHintText) "" else node.text?.toString().orEmpty()
        return when (action) {
            KeyAction.ENTER ->
                if (Build.VERSION.SDK_INT >= 30 && node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)) null
                else "enter isn't available here"
            KeyAction.BACKSPACE -> setText(node, text.dropLast(1))
            KeyAction.SELECT_ALL -> {
                val args = Bundle().apply {
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 0)
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, text.length)
                }
                if (node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, args)) null else "select all failed"
            }
            else -> null
        }
    }

    fun nav(action: String): Boolean {
        val code = when (action) {
            "back" -> GLOBAL_ACTION_BACK
            "home" -> GLOBAL_ACTION_HOME
            "recents" -> GLOBAL_ACTION_RECENTS
            "notifications" -> GLOBAL_ACTION_NOTIFICATIONS
            "quick_settings" -> GLOBAL_ACTION_QUICK_SETTINGS
            else -> return false
        }
        return performGlobalAction(code)
    }

    // endregion

    /** Visible things worth telling Claude about, centres scaled to screenshot pixels by [sx]/[sy]. Never password text. */
    fun elements(sx: Float, sy: Float): List<UiElement> {
        val root = rootInActiveWindow ?: return emptyList()
        val found = mutableListOf<Pair<Boolean, UiElement>>()   // interactive?, element
        val r = Rect()
        fun walk(n: AccessibilityNodeInfo) {
            if (n.isVisibleToUser) {
                n.getBoundsInScreen(r)
                val text = n.text?.toString()?.takeIf { it.isNotBlank() && !n.isPassword }
                val desc = n.contentDescription?.toString()?.takeIf { it.isNotBlank() }
                val interactive = n.isClickable || n.isEditable || n.isScrollable || n.isCheckable
                if (!r.isEmpty && (interactive || text != null || desc != null)) {
                    val label = if (n.isPassword) "" else text ?: desc ?: n.viewIdResourceName?.substringAfterLast('/').orEmpty()
                    val state = if (n.isCheckable) (if (n.isChecked) "on" else "off") else null
                    found += interactive to UiElement(
                        roleOf(n.className?.toString(), n.isClickable, n.isEditable, n.isScrollable, n.isCheckable, n.isPassword),
                        label, (r.exactCenterX() * sx).toInt(), (r.exactCenterY() * sy).toInt(), state,
                    )
                }
            }
            for (i in 0 until n.childCount) n.getChild(i)?.let(::walk)
        }
        walk(root)
        // At most 50: interactive ones first, then back in reading order.
        return (found.filter { it.first } + found.filter { !it.first }).take(MAX_ELEMENTS).map { it.second }.sortedWith(compareBy({ it.y }, { it.x }))
    }

    companion object {
        @Volatile var instance: BuddyAccessibilityService? = null
        private const val QUIET_MS = 250L
        private const val SETTLE_MAX_MS = 1000L
        private const val TOO_SOON = 3      // ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT
        private const val SECURE_WINDOW = 5 // ERROR_TAKE_SCREENSHOT_SECURE_WINDOW

        fun isEnabled(context: Context): Boolean =
            (context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager)
                .getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                .any { it.resolveInfo.serviceInfo.packageName == context.packageName }
    }
}
