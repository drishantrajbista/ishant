package dev.buddy

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.cancellation.CancellationException

/**
 * Everything about power mode that can be decided without Android: which key codes may ever be sent, how coordinates are
 * checked, the only commands the fallback may build (numbers only), and which path carries an action.
 */

/** The only key codes the helper will inject (also enforced inside the helper). */
const val KEY_BACK = 4
const val KEY_HOME = 3
const val KEY_APP_SWITCH = 187
const val KEY_ENTER = 66
const val KEY_DEL = 67
val ALLOWED_KEYCODES: Set<Int> = setOf(
    KEY_BACK, KEY_HOME, KEY_APP_SWITCH, KEY_ENTER, KEY_DEL,
    19, 20, 21, 22, 23,        // DPAD up, down, left, right, centre
    24, 25,                    // volume up, down
    85, 87, 88, 126, 127,      // media play/pause, next, previous, play, pause
)

fun isAllowedKey(code: Int) = code in ALLOWED_KEYCODES

/** How far past the edge a coordinate may be and still be clamped back onto the screen (pixels). */
private const val EDGE_SLACK = 8

/** A tap point as ints inside a [w]x[h] display: NaN, infinity, negatives and points well off the screen are rejected, near-edge ones clamped. */
fun validatePoint(x: Double, y: Double, w: Int, h: Int): Pair<Int, Int> {
    if (w <= 0 || h <= 0) throw ToolError("the screen size isn't known yet")
    if (x.isNaN() || y.isNaN() || x.isInfinite() || y.isInfinite()) throw ToolError("that point isn't a number")
    if (x < 0 || y < 0) throw ToolError("that point is off the screen")
    if (x >= w + EDGE_SLACK || y >= h + EDGE_SLACK) throw ToolError("that point is off the screen")
    return minOf(x.toInt(), w - 1) to minOf(y.toInt(), h - 1)
}

/** A swipe's length in milliseconds, kept to something a person could do. */
fun clampSwipeMs(ms: Int) = ms.coerceIn(50, 2000)

/** What [InputService] runs if it can't inject events directly. Only numbers go into the command, never text from anywhere else. */
fun inputTapCommand(x: Int, y: Int) = listOf("input", "tap", "$x", "$y")
fun inputSwipeCommand(x1: Int, y1: Int, x2: Int, y2: Int, ms: Int) = listOf("input", "swipe", "$x1", "$y1", "$x2", "$y2", "${clampSwipeMs(ms)}")
fun inputKeyCommand(code: Int): List<String> {
    require(isAllowedKey(code)) { "key $code isn't allowed" }
    return listOf("input", "keyevent", "$code")
}

enum class InputPath(val label: String) { SHIZUKU("shizuku"), ACCESSIBILITY("accessibility") }

/** Shizuku carries gestures and keys only when the switch is on AND it is running AND Buddy has its permission AND the helper is bound. */
fun chooseInputPath(powerOn: Boolean, shizukuRunning: Boolean, permissionGranted: Boolean, serviceBound: Boolean): InputPath =
    if (powerOn && shizukuRunning && permissionGranted && serviceBound) InputPath.SHIZUKU else InputPath.ACCESSIBILITY

enum class PowerStatus(val label: String) {
    NOT_INSTALLED("Not installed"), NOT_RUNNING("Not running"), PERMISSION_NEEDED("Permission needed"), READY("Ready"), DEGRADED("Degraded")
}

fun powerStatus(installed: Boolean, running: Boolean, granted: Boolean, degraded: Boolean): PowerStatus = when {
    !installed -> PowerStatus.NOT_INSTALLED
    !running -> PowerStatus.NOT_RUNNING
    !granted -> PowerStatus.PERMISSION_NEEDED
    degraded -> PowerStatus.DEGRADED
    else -> PowerStatus.READY
}

const val POWER_TIMEOUT_MS = 1500L

class Routed<T>(val value: T, val path: InputPath)

private val powerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

/**
 * Runs [power] (a call into the helper) if there is one, giving up after [timeoutMs]; if it throws or times out, [onDegraded] is
 * told and [accessibility] does the same action instead. The caller learns which path carried it.
 */
suspend fun <T : Any> routeInput(power: (suspend () -> T)?, timeoutMs: Long = POWER_TIMEOUT_MS, onDegraded: () -> Unit, accessibility: suspend () -> T): Routed<T> {
    if (power != null) {
        val result = try {
            withTimeoutOrNull(timeoutMs) { powerScope.async { power() }.await() }   // a blocked binder call can't be cancelled, but we stop waiting for it
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        if (result != null) return Routed(result, InputPath.SHIZUKU)
        onDegraded()
    }
    return Routed(accessibility(), InputPath.ACCESSIBILITY)
}
