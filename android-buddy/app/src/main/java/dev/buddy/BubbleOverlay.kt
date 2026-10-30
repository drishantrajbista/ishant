package dev.buddy

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
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
        fun onPressStart()
        fun onPressEnd()
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

    fun remove() {
        if (!added) return
        added = false
        bubble.stop()
        wm.removeView(bubble)
        wm.removeView(textView)
    }

    fun setState(state: BuddyState) = bubble.setState(state)
    fun setLevel(level: Float) { bubble.level = level.coerceIn(0f, 1f) }

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

        fun setState(s: BuddyState) { state = s; invalidate() }
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
            if (state != BuddyState.IDLE) postInvalidateOnAnimation()
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY
                    startX = bubbleParams.x; startY = bubbleParams.y
                    dragging = false; pttActive = false
                    handler.postDelayed(holdRunnable, HOLD_MS)
                }
                MotionEvent.ACTION_MOVE -> if (!pttActive) {
                    val dx = e.rawX - downX; val dy = e.rawY - downY
                    if (!dragging && hypot(dx, dy) > slop) { dragging = true; handler.removeCallbacks(holdRunnable) }
                    if (dragging) {
                        bubbleParams.x = startX + dx.toInt(); bubbleParams.y = startY + dy.toInt()
                        wm.updateViewLayout(this, bubbleParams)
                        positionText()
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    handler.removeCallbacks(holdRunnable)
                    if (pttActive) callbacks.onPressEnd()
                    else if (!dragging && e.actionMasked == MotionEvent.ACTION_UP) callbacks.onTap()
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
