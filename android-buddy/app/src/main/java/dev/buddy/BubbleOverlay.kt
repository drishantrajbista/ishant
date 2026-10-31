package dev.buddy

import android.annotation.SuppressLint
import android.content.Context
import android.animation.ValueAnimator
import android.graphics.Canvas
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
import android.widget.TextView
import kotlin.math.hypot
import kotlin.math.sin

enum class BuddyState { IDLE, LISTENING, PROCESSING, RESPONDING }

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
        /** The bubble was dropped on the close target. */
        fun onClose()
        fun onTap()
    }

    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, context.resources.displayMetrics)
    private val size = dp(56f).toInt()

    private val bubble = BubbleView()
    private val bubbleParams = overlayParams(size, size, touchable = true)
    private val textView = TextView(context).apply {
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        maxLines = 10
        ellipsize = android.text.TextUtils.TruncateAt.END
        val pad = dp(12f).toInt()
        setPadding(pad, pad, pad, pad)
        background = GradientDrawable().apply { setColor(0xE6202124.toInt()); cornerRadius = dp(16f) }
        visibility = View.GONE
    }
    private val textParams = overlayParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, false)
    // Android 12+ blocks touches passing through another app's overlay window above 0.8 alpha; 0.8 is the maximum.
    init { textParams.alpha = 0.8f }
    private var added = false
    private var hiddenForCapture = false

    fun show() {
        if (added) return
        val m = context.resources.displayMetrics
        bubbleParams.x = m.widthPixels - size - dp(8f).toInt()
        bubbleParams.y = (m.heightPixels * 0.4f).toInt()
        wm.addView(textView, textParams)
        wm.addView(bubble, bubbleParams)
        added = true
    }

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
        wm.addView(closeView, closeParams)
        closeView.animate().alpha(1f).setDuration(150).start()
    }

    private fun hideClose() {
        if (!closeShown) return
        closeShown = false
        snapped = false
        closeView.setActive(false)
        closeView.animate().alpha(0f).setDuration(150).withEndAction {
            if (closeView.isAttachedToWindow) wm.removeView(closeView)
        }.start()
    }

    /** While dragging: within ~90 dp of the target the bubble snaps onto it (with a tick) and the target grows. */
    private fun dragMoved(x: Int, y: Int) {
        val c = closeCenter()
        val near = hypot(x + size / 2f - c.x, y + size / 2f - c.y) <= dp(90f)
        if (near != snapped) {
            snapped = near
            closeView.setActive(near)
            if (near) bubble.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        }
        if (snapped) { bubbleParams.x = (c.x - size / 2).toInt(); bubbleParams.y = (c.y - size / 2).toInt() }
        else { bubbleParams.x = x; bubbleParams.y = y }
        wm.updateViewLayout(bubble, bubbleParams)
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
                if (added) { wm.updateViewLayout(bubble, bubbleParams); positionText() }
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
        if (closeView.isAttachedToWindow) wm.removeView(closeView)
        bubble.stop()
        wm.removeView(bubble)
        wm.removeView(textView)
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
        wm.updateViewLayout(bubble, bubbleParams)
    }

    /** Claude's answer is expected: a small pulsing Claude-orange badge. */
    fun setWaiting(waiting: Boolean) = bubble.setWaiting(waiting)

    /** Shows [text] in the small bubble next to the floating bubble; null hides it. */
    fun setText(text: String?) {
        if (!added) return
        textView.text = text.orEmpty()
        textView.visibility = if (text.isNullOrBlank() || hiddenForCapture) View.GONE else View.VISIBLE
        positionText()
    }

    /** Hides both windows so they are not in the screenshot. */
    fun setHiddenForCapture(hidden: Boolean) {
        hiddenForCapture = hidden
        bubble.visibility = if (hidden) View.INVISIBLE else View.VISIBLE
        textView.visibility = if (hidden || textView.text.isNullOrBlank()) View.GONE else View.VISIBLE
    }

    /** Centre of the bubble in screen pixels. */
    fun center(): PointF {
        val loc = IntArray(2).also { bubble.getLocationOnScreen(it) }
        return PointF(loc[0] + size / 2f, loc[1] + size / 2f)
    }

    private fun positionText() {
        val m = context.resources.displayMetrics
        val margin = dp(8f).toInt()
        textView.maxWidth = (m.widthPixels * 0.7f).toInt()
        textView.measure(
            View.MeasureSpec.makeMeasureSpec(m.widthPixels, View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(m.heightPixels / 2, View.MeasureSpec.AT_MOST),
        )
        val w = textView.measuredWidth
        val h = textView.measuredHeight
        textParams.x = (bubbleParams.x + size / 2 - w / 2).coerceIn(margin, (m.widthPixels - w - margin).coerceAtLeast(margin))
        textParams.y = (bubbleParams.y - h - margin).let { if (it < margin) bubbleParams.y + size + margin else it }
        runCatching { wm.updateViewLayout(textView, textParams) }
    }

    @SuppressLint("ViewConstructor")
    private inner class BubbleView : View(context) {
        var level = 0f
        private var state = BuddyState.IDLE
        private var smoothLevel = 0f
        private val blue = Color.rgb(59, 130, 246)
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = blue }
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(90, 59, 130, 246) }
        private val white = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        private val spinner = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = dp(3f); strokeCap = Paint.Cap.ROUND
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

        override fun onDraw(canvas: Canvas) {
            val cx = width / 2f; val cy = height / 2f
            val r = dp(18f)
            val t = System.currentTimeMillis()
            smoothLevel += (level - smoothLevel) * 0.25f
            if (state == BuddyState.LISTENING) {
                val pulse = 0.08f * sin(t / 180.0).toFloat()
                canvas.drawCircle(cx, cy, r * (1.2f + 0.55f * smoothLevel + pulse), ring)
            }
            canvas.drawCircle(cx, cy, r, fill)
            when (state) {
                BuddyState.PROCESSING -> canvas.drawArc(
                    RectF(cx - r * 0.55f, cy - r * 0.55f, cx + r * 0.55f, cy + r * 0.55f),
                    (t / 3 % 360).toFloat(), 260f, false, spinner,
                )
                BuddyState.RESPONDING -> for (i in -1..1) {
                    val barH = r * (0.5f + 0.4f * sin(t / 140.0 + i * 1.6).toFloat())
                    canvas.drawRoundRect(cx + i * dp(7f) - dp(2f), cy - barH / 2, cx + i * dp(7f) + dp(2f), cy + barH / 2, dp(2f), dp(2f), white)
                }
                else -> canvas.drawPath(Path().apply {
                    moveTo(cx, cy - r * 0.5f); lineTo(cx + r * 0.42f, cy + r * 0.45f)
                    lineTo(cx, cy + r * 0.25f); lineTo(cx - r * 0.42f, cy + r * 0.45f); close()
                }, white)
            }
            if (waiting) {
                val r = dp(6f) * (1f + 0.25f * sin(t / 300.0).toFloat())
                canvas.drawCircle(cx + dp(15f), cy - dp(15f), r, badge)
                canvas.drawCircle(cx + dp(15f), cy - dp(15f), r, badgeEdge)
            }
            if (state != BuddyState.IDLE || waiting) postInvalidateOnAnimation()
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
                MotionEvent.ACTION_MOVE -> if (!pttActive) {
                    val dx = e.rawX - downX; val dy = e.rawY - downY
                    if (!dragging && hypot(dx, dy) > slop) {
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
