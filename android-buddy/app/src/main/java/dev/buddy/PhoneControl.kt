package dev.buddy

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.content.pm.PackageManager
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/** What [PhoneControl] needs from the rest of the app (overlays, screen source). */
interface Stage {
    /** The screen at full resolution with Buddy's own windows hidden. */
    suspend fun frame(): Bitmap
    /** Flies the cursor to a point (display pixels) right before an action. */
    suspend fun glide(x: Float, y: Float)
    fun status(text: String)
    /** The bubble can't be tapped while a gesture is dispatched, so a gesture aimed under it still reaches the app. */
    fun bubbleTouchable(on: Boolean)
}

/** Carries out Claude's tool calls on the real phone through [BuddyAccessibilityService]. */
class PhoneControl(private val context: Context, private val stage: Stage) : Phone {
    /** Maps the latest screenshot Claude saw to the display. Claude's coordinates are in that screenshot's pixels. */
    var map: ScreenMap? = null
        private set
    private var lastFull: Bitmap? = null
    private var elements: List<UiElement> = emptyList()
    private var cursor: Pt? = null                     // display px
    private var lastTap: Pair<Int, Int>? = null        // display px

    private val a11y get() = BuddyAccessibilityService.instance
    private fun hands(): BuddyAccessibilityService =
        a11y ?: throw ToolError("buddy's accessibility service is off. ask the user to turn it on in accessibility settings")

    override suspend fun observe(settle: Boolean): Observation {
        if (settle) a11y?.settle()
        val full = stage.frame()
        lastFull = full
        val shot = encodeScreenshot(full, 1280, 70)
        val m = ScreenMap(shot.width, shot.height, shot.displayWidth, shot.displayHeight)
        map = m
        elements = a11y?.elements(shot.width.toFloat() / shot.displayWidth, shot.height.toFloat() / shot.displayHeight).orEmpty()
        return Observation(shot, formatElements(elements))
    }

    override suspend fun run(use: ToolUse, status: (String) -> Unit): List<JSONObject> {
        shotResult = null
        if (use.isComputer) computer(use) else custom(use)
        return shotResult ?: listOf(AgentJson.text("OK"))
    }

    private suspend fun custom(use: ToolUse) {
        when (use.name) {
            "open_app" -> openApp(use.input.optString("name"))
            "open_url" -> openUrl(use.input.optString("url"))
            "open_settings" -> openSettings(use.input.optString("page"), use.input.optString("package"))
            "list_apps" -> {
                stage.status("looking at your apps")
                shotResult = listOf(AgentJson.text(launchableApps().keys.sorted().take(300).joinToString(", ").ifEmpty { "none" }))
            }
            "android_nav" -> {
                val action = use.input.optString("action")
                stage.status(action.replace('_', ' '))
                if (!hands().nav(action)) throw ToolError("couldn't do $action")
                a11y?.acted()
            }
            else -> throw ToolError("unknown tool ${use.name}")
        }
    }

    private suspend fun computer(use: ToolUse) {
        val input = use.input
        when (use.name) {
            "screenshot" -> {
                stage.status("looking at the screen")
                val o = observe(settle = true)
                shotResult = listOf(AgentJson.image(o.shot.jpeg), AgentJson.text("elements:\n${o.elements}"))
            }
            "zoom" -> zoom(input)
            "mouse_move" -> { val p = point(input, "coordinate"); stage.status("pointing"); glide(p) }
            "left_click" -> act(input, "tapping") { x, y -> hands().tap(x, y) }
            "double_click" -> act(input, "double tapping") { x, y -> hands().doubleTap(x, y) }
            "right_click" -> act(input, "pressing and holding") { x, y -> hands().longPress(x, y) }
            "left_click_drag" -> {
                val from = displayPoint(point(input, "start_coordinate"))
                val to = displayPoint(point(input, "coordinate"))
                stage.status("swiping")
                glide(from)
                dispatch { hands().swipe(from.first, from.second, to.first, to.second) }
                glide(to)
            }
            "scroll" -> {
                val m = map ?: throw ToolError("take a screenshot first")
                val p = if (input.has("coordinate")) displayPoint(point(input, "coordinate")) else (cursor ?: Pt(m.displayW / 2f, m.displayH / 2f)).let { it.x.toInt() to it.y.toInt() }
                val s = scrollSwipe(input.optString("scroll_direction"), input.optInt("scroll_amount", 3), p.first, p.second, m.displayW, m.displayH)
                stage.status("scrolling ${input.optString("scroll_direction")}")
                glide(s[0] to s[1])
                dispatch { hands().swipe(s[0], s[1], s[2], s[3]) }
                glide(s[2] to s[3])
            }
            "type" -> type(input.optString("text"))
            "key" -> key(input.optString("text"), input.optInt("repeat", 1))
            "wait" -> { stage.status("waiting"); delay((input.optDouble("duration", 1.0).coerceIn(0.0, 5.0) * 1000).toLong()) }
            else -> throw ToolError("${use.name} isn't available on a phone")
        }
    }

    // The blocks for the tool call being run, when it isn't a plain "OK".
    private var shotResult: List<JSONObject>? = null

    private fun point(input: JSONObject, key: String): Pt {
        val a = input.optJSONArray(key)
        if (a == null || a.length() < 2) {
            return cursor?.let { c -> map?.let { Pt(c.x * it.shotW / it.displayW, c.y * it.shotH / it.displayH) } }
                ?: throw ToolError("give a coordinate")
        }
        return Pt(a.getDouble(0).toFloat(), a.getDouble(1).toFloat())
    }

    private fun displayPoint(p: Pt): Pair<Int, Int> {
        val m = map ?: throw ToolError("take a screenshot first")
        val d = m.toDisplay(p)
        return d.x.toInt() to d.y.toInt()
    }

    private suspend fun glide(p: Pt) = glide(displayPoint(p))
    private suspend fun glide(d: Pair<Int, Int>) { cursor = Pt(d.first.toFloat(), d.second.toFloat()); stage.glide(d.first.toFloat(), d.second.toFloat()) }

    private suspend fun dispatch(block: suspend () -> Boolean) {
        stage.bubbleTouchable(false)
        try { if (!block()) throw ToolError("the gesture didn't go through") } finally { stage.bubbleTouchable(true) }
        a11y?.acted()
    }

    private suspend fun act(input: JSONObject, verb: String, block: suspend (Int, Int) -> Boolean) {
        val p = point(input, "coordinate")
        val d = displayPoint(p)
        stage.status(nearestLabel(elements, p.x, p.y)?.let { "$verb $it" } ?: verb)
        glide(d)
        lastTap = d
        dispatch { block(d.first, d.second) }
    }

    private suspend fun type(raw: String) {
        stage.status("typing…")
        val submit = raw.endsWith("\n")
        hands().typeText(raw.removeSuffix("\n"), lastTap)?.let { throw ToolError(it) }
        if (submit) hands().pressKey(KeyAction.ENTER, 1)?.let { throw ToolError(it) }
        a11y?.acted()
    }

    private suspend fun key(text: String, repeat: Int) {
        val action = parseKey(text) ?: throw ToolError("unsupported key \"$text\". supported: $SUPPORTED_KEYS")
        stage.status("pressing ${text.lowercase()}")
        hands().pressKey(action, repeat)?.let { throw ToolError(it) }
        a11y?.acted()
    }

    private fun zoom(input: JSONObject) {
        val full = lastFull ?: throw ToolError("take a screenshot first")
        val r = input.optJSONArray("region")?.takeIf { it.length() == 4 } ?: throw ToolError("give a region [x0, y0, x1, y1]")
        val a = displayPoint(Pt(r.getDouble(0).toFloat(), r.getDouble(1).toFloat()))
        val b = displayPoint(Pt(r.getDouble(2).toFloat(), r.getDouble(3).toFloat()))
        val box = Rect(minOf(a.first, b.first), minOf(a.second, b.second), maxOf(a.first, b.first), maxOf(a.second, b.second))
        if (box.width() < 2 || box.height() < 2) throw ToolError("that region is empty")
        val crop = Bitmap.createBitmap(full, box.left, box.top, box.width(), box.height())
        shotResult = listOf(AgentJson.image(encodeScreenshot(crop, 1280, 70).jpeg))
    }

    private fun launchableApps(): Map<String, android.content.pm.ResolveInfo> {
        val pm = context.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(launcher, PackageManager.MATCH_ALL).associateBy { it.loadLabel(pm).toString() }
    }

    private fun launch(intent: Intent) {
        try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: android.content.ActivityNotFoundException) {
            throw ToolError("nothing on this phone can open that")
        }
        a11y?.acted()
    }

    /** Links open in the right app; mail, sms and tel only ever open a draft or the dialer. */
    private fun openUrl(url: String) {
        val kind = urlAction(url) ?: throw ToolError("unsupported link. use http, https, geo, mailto, tel or sms")
        stage.status("opening link")
        val uri = android.net.Uri.parse(url.trim())
        launch(Intent(when (kind) { "SENDTO" -> Intent.ACTION_SENDTO; "DIAL" -> Intent.ACTION_DIAL; else -> Intent.ACTION_VIEW }, uri))
    }

    private fun openSettings(page: String, pkg: String) {
        stage.status("opening $page settings")
        val intent = Intent(settingsAction(page))
        if (page == "app_details") {
            if (pkg.isBlank()) throw ToolError("app_details needs the app's package name")
            intent.data = android.net.Uri.parse("package:$pkg")
        }
        try {
            launch(intent)
        } catch (e: ToolError) {
            launch(Intent(settingsAction("main")))   // that page isn't on this phone: the main Settings screen
        }
    }

    private suspend fun openApp(name: String) {
        val pm = context.packageManager
        val apps = launchableApps()
        val match = matchApps(name, apps.keys.toList())
        val label = match.best ?: throw ToolError("no app named \"$name\". closest: ${match.candidates.joinToString(", ").ifEmpty { "none" }}")
        val info = apps.getValue(label).activityInfo
        stage.status("opening $label")
        context.startActivity(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                .setClassName(info.packageName, info.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED),
        )
        a11y?.acted()
    }
}
