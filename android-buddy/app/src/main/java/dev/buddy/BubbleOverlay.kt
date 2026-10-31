package dev.buddy

import android.annotation.SuppressLint
import android.content.Context
import android.animation.ValueAnimator
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.SweepGradient
import android.graphics.Typeface
import android.graphics.LinearGradient
import android.graphics.Shader
import android.util.DisplayMetrics
import android.view.animation.OvershootInterpolator
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.hypot
import kotlin.math.cos
import kotlin.math.sin

enum class BuddyState { IDLE, LISTENING, PROCESSING, RESPONDING, ACTING }

fun overlayParams(w: Int, h: Int, touchable: Boolean) = WindowManager.LayoutParams(
    w, h,
    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
        (if (touchable) 0 else WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE),
    android.graphics.PixelFormat.TRANSLUCENT,
).apply { gravity = Gravity.TOP or Gravity.START }

/** The draggable push-to-talk bubble plus the small reply text bubble that follows it. */
class BubbleOverlay(private val context: Context, private val callbacks: Callbacks) {
    interface Callbacks {
        /** A finger landed. It may become push-to-talk, a tap or a drag; Gemini voice starts recording right away. */
        fun onTouchDown()
        /** ...and it turned out to be a tap or a drag, not push-to-talk. */
        fun onTouchAbort()
        /** Held long enough: push-to-talk. */
        fun onPressStart()
        /** Released after push-to-talk. */
        fun onPressEnd()
        /** Push-to-talk was abandoned because the finger started dragging: stop listening, say nothing. */
        fun onPressCancel()
        /** The bubble was dropped on the close target. */
        fun onClose()
        fun onTap()
        /** The "Copy" button on a finished reply was tapped. */
        fun onCopyReply()
    }

    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, context.resources.displayMetrics)
    private val size = dp(56f).toInt()

    private val bubble = BubbleView()
    private val bubbleParams = overlayParams(size, size, touchable = true)
    private val darkMode = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    /** The reply text inside the card. */
    private val replyText = TextView(context).apply {
        setTextColor(if (darkMode) Color.WHITE else Color.rgb(0x1C, 0x1C, 0x1E))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
        setLineSpacing(dp(2f), 1f)
        maxLines = 10
        ellipsize = android.text.TextUtils.TruncateAt.END
    }

    /** "Copy" at the bottom of the card. Shown (and the card made touchable) only for a finished reply, never for status text. */
    private val copyButton = TextView(context).apply {
        text = "Copy"
        setTextColor(Color.rgb(0xD9, 0x77, 0x57))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        typeface = Typeface.DEFAULT_BOLD
        contentDescription = "Copy the reply"
        gravity = Gravity.CENTER_VERTICAL or Gravity.END
        minHeight = dp(36f).toInt()
        setPadding(dp(20f).toInt(), 0, 0, 0)
        visibility = View.GONE
        setOnClickListener { callbacks.onCopyReply() }
    }

    /** The reply card: a rounded, softly shadowed card (the shadow is layered translucent shapes, inset so it isn't clipped). */
    private val card = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        val inset = dp(10f).toInt()
        val pad = inset + dp(14f).toInt()
        setPadding(pad, pad - dp(2f).toInt(), pad, pad + dp(2f).toInt())
        fun shape(color: Int, stroke: Boolean = false) = GradientDrawable().apply {
            setColor(color); cornerRadius = dp(20f)
            if (stroke) setStroke(dp(1f).toInt(), if (darkMode) Color.argb(50, 255, 255, 255) else Color.argb(28, 0, 0, 0))
        }
        val face = shape(if (darkMode) Color.argb(245, 28, 28, 30) else Color.argb(247, 255, 255, 255), stroke = true)
        background = android.graphics.drawable.LayerDrawable(
            arrayOf(shape(Color.argb(12, 0, 0, 0)), shape(Color.argb(16, 0, 0, 0)), shape(Color.argb(22, 0, 0, 0)), face),
        ).also { l ->
            l.setLayerInset(1, dp(2f).toInt(), dp(2f).toInt(), dp(2f).toInt(), dp(1f).toInt())
            l.setLayerInset(2, dp(5f).toInt(), dp(5f).toInt(), dp(5f).toInt(), dp(3f).toInt())
            l.setLayerInset(3, inset, dp(8f).toInt(), inset, dp(12f).toInt())
        }
        addView(replyText, LinearLayout.LayoutParams(-2, -2))
        addView(copyButton, LinearLayout.LayoutParams(-2, -2).apply { gravity = Gravity.END })
        visibility = View.GONE
    }
    private val textParams = overlayParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, false)
    // Android 12+ blocks touches passing through another app's overlay window above 0.8 alpha; 0.8 is the maximum.
    init { textParams.alpha = 0.8f }
    private var added = false
    private var hiddenForCapture = false

    /** Adds the bubble's windows. False if Android refuses (e.g. "draw over other apps" was revoked). */
    fun show(): Boolean {
        if (added) return true
        val m = context.resources.displayMetrics
        bubbleParams.x = m.widthPixels - size - dp(8f).toInt()
        bubbleParams.y = (m.heightPixels * 0.4f).toInt()
        return try {
            wm.addView(card, textParams)
            wm.addView(bubble, bubbleParams)
            added = true
            true
        } catch (e: RuntimeException) {   // BadTokenException, SecurityException, IllegalStateException
            runCatching { wm.removeView(card) }
            false
        }
    }

    /** Window changes can fail if the permission goes away mid-use; that must never crash Buddy. */
    private inline fun guard(block: () -> Unit) { try { block() } catch (e: RuntimeException) { android.util.Log.w("Buddy", "overlay", e) } }

    // --- drag to close: a target fades in at the bottom while the bubble is dragged ---

    private val closeView = CloseView()
    // Its own non-touchable window; alpha 0.8 is the most opaque Android 12+ lets touches pass through.
    private val closeParams = overlayParams(WindowManager.LayoutParams.MATCH_PARENT, dp(200f).toInt(), false).apply { alpha = 0.8f }
    private var closeShown = false
    private var snapped = false

    private fun realSize(): Pair<Int, Int> {
        val m = DisplayMetrics()
        @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(m)
        return m.widthPixels to m.heightPixels
    }

    private fun closeCenter(): PointF = realSize().let { PointF(it.first / 2f, it.second - dp(72f)) }

    private fun showClose() {
        if (closeShown) return
        closeShown = true
        closeParams.y = realSize().second - closeParams.height
        closeView.alpha = 0f
        guard { wm.addView(closeView, closeParams) }
        closeView.animate().alpha(1f).setDuration(150).start()
    }

    private fun hideClose() {
        if (!closeShown) return
        closeShown = false
        snapped = false
        closeView.setActive(false)
        closeView.animate().alpha(0f).setDuration(150).withEndAction {
            if (closeView.isAttachedToWindow) guard { wm.removeView(closeView) }
        }.start()
    }

    /** While dragging: within ~110 dp of the target the bubble snaps onto it (with a tick) and the target grows. */
    private fun dragMoved(x: Int, y: Int) {
        val c = closeCenter()
        val near = hypot(x + size / 2f - c.x, y + size / 2f - c.y) <= dp(110f)
        if (near != snapped) {
            snapped = near
            closeView.setActive(near)
            if (near) bubble.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        }
        if (snapped) { bubbleParams.x = (c.x - size / 2).toInt(); bubbleParams.y = (c.y - size / 2).toInt() }
        else { bubbleParams.x = x; bubbleParams.y = y }
        guard { wm.updateViewLayout(bubble, bubbleParams) }
        positionText()
    }

    /** Released away from the target: spring to the nearest left or right edge. */
    private fun springToEdge() {
        val (w, h) = realSize()
        val margin = dp(8f).toInt()
        val target = if (bubbleParams.x + size / 2 < w / 2) margin else w - size - margin
        bubbleParams.y = bubbleParams.y.coerceIn(margin, h - size - margin)
        ValueAnimator.ofInt(bubbleParams.x, target).apply {
            duration = 380
            interpolator = OvershootInterpolator(1.6f)
            addUpdateListener {
                bubbleParams.x = it.animatedValue as Int
                if (added) { guard { wm.updateViewLayout(bubble, bubbleParams) }; positionText() }
            }
            start()
        }
    }

    private inner class CloseView : View(context) {
        private var scale = 1f
        private val scrim = Paint()
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(70, 255, 255, 255) }
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = dp(2f) }
        private val cross = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = dp(3f); strokeCap = Paint.Cap.ROUND
        }

        fun setActive(active: Boolean) {
            ValueAnimator.ofFloat(scale, if (active) 1.2f else 1f).apply {
                duration = 120
                addUpdateListener { scale = it.animatedValue as Float; invalidate() }
                start()
            }
        }

        override fun onDraw(canvas: Canvas) {
            scrim.shader = LinearGradient(0f, 0f, 0f, height.toFloat(), Color.TRANSPARENT, Color.argb(150, 0, 0, 0), Shader.TileMode.CLAMP)
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), scrim)
            val cx = width / 2f
            val cy = height - dp(72f)
            val r = dp(32f) * scale
            canvas.drawCircle(cx, cy, r, fill)
            canvas.drawCircle(cx, cy, r, ring)
            val k = dp(10f) * scale
            canvas.drawLine(cx - k, cy - k, cx + k, cy + k, cross)
            canvas.drawLine(cx - k, cy + k, cx + k, cy - k, cross)
        }
    }

    fun remove() {
        if (!added) return
        added = false
        if (closeView.isAttachedToWindow) guard { wm.removeView(closeView) }
        bubble.stop()
        guard { wm.removeView(bubble) }
        guard { wm.removeView(card) }
    }

    fun setState(state: BuddyState) = bubble.setState(state)

    /** A short buzz, e.g. to say "the microphone is open, start talking". */
    fun buzz() {
        bubble.performHapticFeedback(
            if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.LONG_PRESS,
        )
    }
    fun setLevel(level: Float) { bubble.level = level.coerceIn(0f, 1f) }

    /** Off while the agent dispatches a gesture, so one aimed at a spot under the bubble reaches the app below. */
    fun setTouchable(on: Boolean) {
        if (!added) return
        bubbleParams.flags = if (on) bubbleParams.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        else bubbleParams.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        guard { wm.updateViewLayout(bubble, bubbleParams) }
    }

    /** Claude's answer is expected: a small pulsing Claude-orange badge. */
    fun setWaiting(waiting: Boolean) = bubble.setWaiting(waiting)

    /**
     * Shows [text] in the small bubble next to the floating bubble; null hides it. With [copyable] (a finished reply) the card
     * gets a "Copy" button and takes touches; status text never does, so a gesture aimed under it still reaches the app.
     */
    fun setText(text: String?, copyable: Boolean = false) {
        if (!added) return
        replyText.text = text.orEmpty()
        val canCopy = copyable && !text.isNullOrBlank()
        copyButton.visibility = if (canCopy) View.VISIBLE else View.GONE
        textParams.flags = if (canCopy) textParams.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        else textParams.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        val show = !(text.isNullOrBlank() || hiddenForCapture)
        val appearing = show && card.visibility != View.VISIBLE
        card.visibility = if (show) View.VISIBLE else View.GONE
        positionText()
        if (appearing) {   // fade and slide in
            card.alpha = 0f
            card.translationY = dp(10f)
            card.animate().alpha(1f).translationY(0f).setDuration(260).setInterpolator(OvershootInterpolator(0.9f)).start()
        }
    }

    /** Hides both windows so they are not in the screenshot. */
    fun setHiddenForCapture(hidden: Boolean) {
        hiddenForCapture = hidden
        bubble.visibility = if (hidden) View.INVISIBLE else View.VISIBLE
        card.visibility = if (hidden || replyText.text.isNullOrBlank()) View.GONE else View.VISIBLE
    }

    /** Centre of the bubble in screen pixels. */
    fun center(): PointF {
        val loc = IntArray(2).also { bubble.getLocationOnScreen(it) }
        return PointF(loc[0] + size / 2f, loc[1] + size / 2f)
    }

    private fun positionText() {
        val m = context.resources.displayMetrics
        val margin = dp(8f).toInt()
        replyText.maxWidth = (minOf(dp(300f).toInt(), (m.widthPixels * 0.85f).toInt()) - card.paddingLeft - card.paddingRight).coerceAtLeast(dp(80f).toInt())
        card.measure(
            View.MeasureSpec.makeMeasureSpec(m.widthPixels, View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(m.heightPixels / 2, View.MeasureSpec.AT_MOST),
        )
        val w = card.measuredWidth
        val h = card.measuredHeight
        textParams.x = (bubbleParams.x + size / 2 - w / 2).coerceIn(margin, (m.widthPixels - w - margin).coerceAtLeast(margin))
        textParams.y = (bubbleParams.y - h - margin).let { if (it < margin) bubbleParams.y + size + margin else it }
        runCatching { wm.updateViewLayout(card, textParams) }
    }

    @SuppressLint("ViewConstructor")
    private inner class BubbleView : View(context) {
        var level = 0f
        private var state = BuddyState.IDLE
        private var smoothLevel = 0f
        // Frosted-glass look: a translucent circle with a soft shadow, a sheen and a thin border.
        private val dark = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        private val accent = Color.rgb(0xD9, 0x77, 0x57)
        private val glass = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = if (dark) Color.argb(205, 28, 28, 30) else Color.argb(217, 255, 255, 255) }
        private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = dp(1f); color = if (dark) Color.argb(60, 255, 255, 255) else Color.argb(40, 0, 0, 0)
        }
        private val sheen = Paint(Paint.ANTI_ALIAS_FLAG)
        private val tintGlow = Paint(Paint.ANTI_ALIAS_FLAG)   // a faint brand tint toward the bottom
        private val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(14, 0, 0, 0) }
        private val accentFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent }
        private val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent; strokeWidth = dp(2.5f); strokeCap = Paint.Cap.ROUND }
        private val pulse = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent; style = Paint.Style.STROKE; strokeWidth = dp(2f) }
        private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(3.5f); strokeCap = Paint.Cap.ROUND }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            val cx = w / 2f; val cy = h / 2f; val r = dp(19f)
            sheen.shader = LinearGradient(cx, cy - r, cx, cy + r, Color.argb(if (dark) 36 else 130, 255, 255, 255), Color.TRANSPARENT, Shader.TileMode.CLAMP)
            tintGlow.shader = LinearGradient(cx, cy, cx, cy + r, Color.TRANSPARENT, Color.argb(if (dark) 46 else 34, 0xD9, 0x77, 0x57), Shader.TileMode.CLAMP)
            arc.shader = SweepGradient(cx, cy, intArrayOf(Color.TRANSPARENT, accent), null)
        }
        private val handler = Handler(Looper.getMainLooper())
        private val slop = ViewConfiguration.get(context).scaledTouchSlop
        private var downX = 0f; private var downY = 0f
        private var startX = 0; private var startY = 0
        private var dragging = false
        private var pttActive = false
        private val holdRunnable = Runnable {
            pttActive = true
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            callbacks.onPressStart()
        }

        private var waiting = false
        private val badge = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(0xD9, 0x77, 0x57) }
        private val badgeEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = dp(2f)
        }

        fun setState(s: BuddyState) { state = s; invalidate() }
        fun setWaiting(w: Boolean) { waiting = w; invalidate() }
        fun stop() = handler.removeCallbacks(holdRunnable)

        /** The Buddy mark: a small arrow-like cursor. */
        private fun glyph(canvas: Canvas, cx: Float, cy: Float, r: Float, rotation: Float = 0f) {
            canvas.save()
            canvas.rotate(rotation, cx, cy)
            canvas.drawPath(Path().apply {
                moveTo(cx, cy - r * 0.5f); lineTo(cx + r * 0.42f, cy + r * 0.45f)
                lineTo(cx, cy + r * 0.25f); lineTo(cx - r * 0.42f, cy + r * 0.45f); close()
            }, accentFill)
            canvas.restore()
        }

        override fun onDraw(canvas: Canvas) {
            val cx = width / 2f; val cy = height / 2f
            val t = System.currentTimeMillis()
            smoothLevel += (level - smoothLevel) * 0.25f
            val r = dp(19f) * if (state == BuddyState.IDLE) 1f + 0.035f * sin(t / 800.0).toFloat() else 1f   // idle: breathing
            for (i in 3 downTo 1) canvas.drawCircle(cx, cy + dp(1.5f) * i, r + dp(1.2f) * i, shadow)
            canvas.drawCircle(cx, cy, r, glass)
            canvas.drawCircle(cx, cy, r, sheen)
            canvas.drawCircle(cx, cy, r, tintGlow)
            canvas.drawCircle(cx, cy, r, border)
            when (state) {
                BuddyState.IDLE -> glyph(canvas, cx, cy, r)
                BuddyState.LISTENING -> {   // a waveform ring that follows the voice
                    glyph(canvas, cx, cy, r)
                    for (i in 0 until 28) {
                        val a = i * 2 * Math.PI / 28
                        val amp = 0.15f + 0.85f * smoothLevel * (0.5f + 0.5f * sin(t / 110.0 + i * 0.9).toFloat())
                        val from = r + dp(2f); val to = from + dp(1f) + dp(5f) * amp
                        canvas.drawLine(cx + from * cos(a).toFloat(), cy + from * sin(a).toFloat(), cx + to * cos(a).toFloat(), cy + to * sin(a).toFloat(), tick)
                    }
                }
                BuddyState.PROCESSING -> {   // a rotating gradient arc
                    glyph(canvas, cx, cy, r * 0.8f)
                    canvas.save()
                    canvas.rotate((t / 3 % 360).toFloat(), cx, cy)
                    canvas.drawArc(RectF(cx - r - dp(4f), cy - r - dp(4f), cx + r + dp(4f), cy + r + dp(4f)), 0f, 300f, false, arc)
                    canvas.restore()
                }
                BuddyState.RESPONDING -> {   // speaking: a ring that pulses outwards
                    glyph(canvas, cx, cy, r)
                    val phase = (t % 1100) / 1100f
                    pulse.alpha = (200 * (1f - phase)).toInt()
                    canvas.drawCircle(cx, cy, r + dp(7f) * phase, pulse)
                }
                BuddyState.ACTING -> {   // acting: a cursor glyph with a dot circling it
                    glyph(canvas, cx, cy, r, -25f)
                    val a = t / 350.0
                    canvas.drawCircle(cx + (r + dp(5f)) * cos(a).toFloat(), cy + (r + dp(5f)) * sin(a).toFloat(), dp(2.5f), accentFill)
                }
            }
            if (waiting) {
                val br = dp(6f) * (1f + 0.25f * sin(t / 300.0).toFloat())
                canvas.drawCircle(cx + dp(15f), cy - dp(15f), br, badge)
                canvas.drawCircle(cx + dp(15f), cy - dp(15f), br, badgeEdge)
            }
            if (state != BuddyState.IDLE || waiting) postInvalidateOnAnimation() else postInvalidateDelayed(40)
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY
                    startX = bubbleParams.x; startY = bubbleParams.y
                    dragging = false; pttActive = false
                    handler.postDelayed(holdRunnable, HOLD_MS)
                    callbacks.onTouchDown()
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX; val dy = e.rawY - downY
                    if (pttActive && hypot(dx, dy) > dp(40f)) {
                        // A finger that rested 250 ms and then moved wants to drag, not talk: drop the recording quietly.
                        pttActive = false
                        dragging = true
                        callbacks.onPressCancel()
                    } else if (!pttActive && !dragging && hypot(dx, dy) > slop) {
                        dragging = true
                        handler.removeCallbacks(holdRunnable)
                        callbacks.onTouchAbort()
                    }
                    if (dragging) {
                        showClose()
                        dragMoved(startX + dx.toInt(), startY + dy.toInt())
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    handler.removeCallbacks(holdRunnable)
                    if (dragging) {
                        val close = snapped
                        hideClose()
                        if (close) callbacks.onClose() else springToEdge()
                    }
                    if (pttActive) callbacks.onPressEnd()
                    else if (!dragging) {
                        callbacks.onTouchAbort()
                        if (e.actionMasked == MotionEvent.ACTION_UP) callbacks.onTap()
                    }
                    pttActive = false
                }
            }
            return true
        }
    }

    private companion object {
        const val HOLD_MS = 250L
    }
}
