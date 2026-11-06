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

/** What the card shows ([label], on the chip) and what the user should do about it ([todo], one plain line under it). */
enum class PowerStatus(val label: String, val todo: String) {
    NOT_INSTALLED("Not installed", "Install Shizuku, then come back and tap Refresh."),
    NOT_RUNNING("Not running", "Open Shizuku and start it (Wireless debugging), then tap Refresh."),
    PERMISSION_NEEDED("Permission needed", "Tap Allow Buddy and confirm in Shizuku."),
    PERMISSION_DENIED("Denied", "Denied: open Shizuku → Authorized apps and allow Buddy."),
    SWITCH_OFF("Allowed (switch off)", "Shizuku is ready. Turn on \"Use power mode for taps\" to use it."),
    HELPER_STARTING("Starting helper…", "Starting Buddy's helper in Shizuku. Give it a few seconds, then tap Refresh."),
    HELPER_FAILED("Helper didn't start", "The helper didn't start. Tap Reconnect; Copy report has the error."),
    READY("Ready", "All set: taps and swipes go through Shizuku."),
    DEGRADED("Degraded", "A call failed, so Buddy used accessibility. Tap Reconnect."),
}

/**
 * The truthful state, most basic problem first. READY needs the helper really bound; while the switch is off a healthy Shizuku is
 * [PowerStatus.SWITCH_OFF] (the helper is only started when the switch is on). [denied] is "deny and don't ask again".
 */
fun powerStatus(
    installed: Boolean, running: Boolean, granted: Boolean, denied: Boolean,
    wanted: Boolean, helperBound: Boolean, helperFailed: Boolean, degraded: Boolean,
): PowerStatus = when {
    !installed -> PowerStatus.NOT_INSTALLED
    !running -> PowerStatus.NOT_RUNNING
    !granted -> if (denied) PowerStatus.PERMISSION_DENIED else PowerStatus.PERMISSION_NEEDED
    !wanted -> PowerStatus.SWITCH_OFF
    helperFailed && !helperBound -> PowerStatus.HELPER_FAILED
    !helperBound -> PowerStatus.HELPER_STARTING
    degraded -> PowerStatus.DEGRADED
    else -> PowerStatus.READY
}

/** One recorded failure (class and message only). */
class PowerError(val at: Long, val text: String)

/** Everything the Shizuku block of Check setup and Copy report says. State only: no keys, no screen content. */
class ShizukuDiag(
    val installedVersion: String?, val ping: Boolean, val preV11: Boolean?, val apiVersion: Int?, val uid: Int?,
    val permissionCode: Int?, val rationale: Boolean?, val helperBound: Boolean, val binderReceivedAt: Long?,
    val switchOn: Boolean, val status: PowerStatus, val errors: List<PowerError>,
)

const val MAX_POWER_ERRORS = 5

private fun yesNo(b: Boolean?) = when (b) { true -> "yes"; false -> "no"; null -> "n/a" }

fun formatShizukuDiagnostics(d: ShizukuDiag, now: Long = System.currentTimeMillis(), clock: (Long) -> String = { it.toString() }): String = buildString {
    appendLine("Shizuku:")
    appendLine("  app: ${d.installedVersion?.let { "installed, version $it" } ?: "not installed"}")
    appendLine("  binder alive (ping): ${yesNo(d.ping)}")
    appendLine("  pre-v11: ${yesNo(d.preV11)}; api version: ${d.apiVersion ?: "n/a"}; uid: ${d.uid?.let { "$it (${when (it) { 0 -> "root"; 2000 -> "adb shell"; else -> "other" }})" } ?: "n/a"}")
    appendLine("  permission: ${d.permissionCode?.let { if (it == 0) "granted (0)" else "not granted ($it)" } ?: "n/a"}; rationale (denied for good): ${yesNo(d.rationale)}")
    appendLine("  helper bound: ${yesNo(d.helperBound)}")
    appendLine("  binder-received event: ${d.binderReceivedAt?.let { "fired ${((now - it) / 1000).coerceAtLeast(0)} s ago (${clock(it)})" } ?: "never"}")
    appendLine("  power mode switch: ${if (d.switchOn) "on" else "off"}; state: ${d.status.label}")
    val errors = d.errors.takeLast(MAX_POWER_ERRORS)
    append("  errors: ${if (errors.isEmpty()) "none" else ""}")
    errors.forEach { append("\n    ${clock(it.at)} ${it.text.replace(Regex("\\s+"), " ").take(160)}") }
}

/** The one line Check setup shows, naming the failing step. */
fun powerCheckDetail(switchOn: Boolean, status: PowerStatus, lastError: String?): String = when (status) {
    PowerStatus.NOT_INSTALLED -> "Shizuku app not installed"
    PowerStatus.NOT_RUNNING -> "Shizuku app not running (start it, then Refresh)"
    PowerStatus.PERMISSION_NEEDED -> "permission not granted yet (tap Allow Buddy)"
    PowerStatus.PERMISSION_DENIED -> "permission denied (Shizuku → Authorized apps)"
    PowerStatus.SWITCH_OFF -> "Shizuku ready; switch is off (optional)"
    PowerStatus.HELPER_STARTING -> "helper starting…"
    PowerStatus.HELPER_FAILED -> "helper didn't start" + (lastError?.let { ": $it" } ?: "")
    PowerStatus.DEGRADED -> "a call failed, using accessibility" + (lastError?.let { ": $it" } ?: "")
    PowerStatus.READY -> "ready: taps and swipes go through Shizuku"
}.let { if (!switchOn && status != PowerStatus.SWITCH_OFF) "$it (switch off)" else it }

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
