package dev.buddy

import android.content.Context
import android.os.SystemClock
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.annotation.Keep
import java.lang.reflect.Method
import java.util.concurrent.TimeUnit

/**
 * Buddy's privileged helper, run by Shizuku in its own process with shell privileges (a Shizuku "UserService"). It can do exactly
 * what [IInputService] lists: taps, long presses, swipes and whitelisted keys. Events are injected through InputManager (found by
 * reflection, asynchronous mode); if that isn't available it uses the `input` command with numeric arguments only.
 * It never types text and never captures the screen.
 */
class InputService() : IInputService.Stub() {
    /** Shizuku v13 tries this constructor first. */
    @Keep constructor(@Suppress("UNUSED_PARAMETER") context: Context) : this()

    private var manager: Any? = null
    private var inject: Method? = null
    private var injectArgs = 2
    private var direct = true   // false after the first failure: the `input` command from then on

    init {
        for (cls in listOf("android.hardware.input.InputManagerGlobal", "android.hardware.input.InputManager")) {
            try {
                val c = Class.forName(cls)
                val instance = c.getMethod("getInstance").invoke(null) ?: continue
                val method = try {
                    c.getMethod("injectInputEvent", InputEvent::class.java, Int::class.javaPrimitiveType)
                } catch (e: NoSuchMethodException) {
                    injectArgs = 3
                    c.getMethod("injectInputEvent", InputEvent::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                }
                manager = instance; inject = method
                break
            } catch (e: Throwable) {
                injectArgs = 2
            }
        }
        if (inject == null) direct = false
    }

    override fun destroy() { System.exit(0) }

    override fun tap(x: Int, y: Int): Boolean =
        inRange(x, y) && (viaEvents { downUp(x, y, 50) } || viaCommand(inputTapCommand(x, y)))

    override fun longPress(x: Int, y: Int): Boolean =
        inRange(x, y) && (viaEvents { downUp(x, y, 600) } || viaCommand(inputSwipeCommand(x, y, x, y, 600)))

    override fun doubleTap(x: Int, y: Int): Boolean = tap(x, y).also { if (it) SystemClock.sleep(120) } && tap(x, y)

    override fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, ms: Int): Boolean {
        val d = clampSwipeMs(ms)
        return inRange(x1, y1, x2, y2) && (viaEvents { moveSwipe(x1, y1, x2, y2, d) } || viaCommand(inputSwipeCommand(x1, y1, x2, y2, d)))
    }

    override fun key(keyCode: Int): Boolean {
        if (!isAllowedKey(keyCode)) return false   // the whitelist is enforced here too, not only in the app
        return viaEvents { keyDownUp(keyCode) } || viaCommand(inputKeyCommand(keyCode))
    }

    // --- injecting events ---

    private fun inRange(vararg v: Int) = v.all { it in 0..MAX_PX }

    /** Injects events directly; one failure turns that path off for good. */
    private fun viaEvents(block: () -> Boolean): Boolean {
        if (!direct) return false
        return try { block() } catch (e: Throwable) { direct = false; false }
    }

    private fun viaCommand(command: List<String>): Boolean = try { run(command) } catch (e: Throwable) { false }

    private fun send(e: InputEvent): Boolean {
        val m = inject ?: return false
        return if (injectArgs == 2) m.invoke(manager, e, MODE_ASYNC) as? Boolean ?: false
        else m.invoke(manager, e, MODE_ASYNC, -1) as? Boolean ?: false
    }

    private fun touch(action: Int, downTime: Long, x: Float, y: Float): Boolean {
        val e = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, y, 0)
        e.source = InputDevice.SOURCE_TOUCHSCREEN
        return try { send(e) } finally { e.recycle() }
    }

    private fun downUp(x: Int, y: Int, holdMs: Long): Boolean {
        val t = SystemClock.uptimeMillis()
        if (!touch(MotionEvent.ACTION_DOWN, t, x.toFloat(), y.toFloat())) return false
        SystemClock.sleep(holdMs)
        return touch(MotionEvent.ACTION_UP, t, x.toFloat(), y.toFloat())
    }

    private fun moveSwipe(x1: Int, y1: Int, x2: Int, y2: Int, ms: Int): Boolean {
        val t = SystemClock.uptimeMillis()
        if (!touch(MotionEvent.ACTION_DOWN, t, x1.toFloat(), y1.toFloat())) return false
        val steps = maxOf(2, ms / 16)
        for (i in 1..steps) {
            SystemClock.sleep((ms / steps).toLong())
            touch(MotionEvent.ACTION_MOVE, t, x1 + (x2 - x1) * i / steps.toFloat(), y1 + (y2 - y1) * i / steps.toFloat())
        }
        return touch(MotionEvent.ACTION_UP, t, x2.toFloat(), y2.toFloat())
    }

    private fun keyDownUp(code: Int): Boolean {
        val t = SystemClock.uptimeMillis()
        fun ev(action: Int) = KeyEvent(t, SystemClock.uptimeMillis(), action, code, 0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD)
        return send(ev(KeyEvent.ACTION_DOWN)) && send(ev(KeyEvent.ACTION_UP))
    }

    /** The fallback: `input` with numeric arguments built by the functions in InputSafety.kt, no shell involved. */
    private fun run(command: List<String>): Boolean {
        val p = ProcessBuilder(command).redirectErrorStream(true).start()
        if (!p.waitFor(1, TimeUnit.SECONDS)) { p.destroy(); return false }
        return p.exitValue() == 0
    }

    private companion object {
        const val MODE_ASYNC = 0
        const val MAX_PX = 20_000
    }
}
