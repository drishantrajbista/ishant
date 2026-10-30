package dev.buddy

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * Full-screen, touch-transparent overlay with Clicky's blue triangle. It flies along a quadratic bezier from the
 * bubble to the target, shows a label pill, then fades out after ~4s (sooner once TTS has finished).
 */
class PointerOverlay(private val context: Context) {
    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val view = PointerView()
    private val handler = Handler(Looper.getMainLooper())
    private val params = overlayParams(
        WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT, touchable = false,
    ).apply {
        flags = flags or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
    }
    private var added = false
    private var arrivedAt = 0L
    private var speechDone = false
    private val fade = Runnable { view.fadeOut() }

    fun show() {
        if (added) return
        view.visibility = View.INVISIBLE
        wm.addView(view, params)
        added = true
    }

    fun remove() {
        if (!added) return
        added = false
        handler.removeCallbacks(fade)
        view.cancel()
        wm.removeView(view)
    }

    /** [from] and [to] are in screen pixels. */
    fun flyTo(from: PointF, to: PointF, label: String?) {
        dismiss()
        speechDone = false
        view.fly(from, to, label) {
            arrivedAt = System.currentTimeMillis()
            handler.postDelayed(fade, if (speechDone) MIN_SHOW_MS else SHOW_MS)
        }
    }

    /** Called when TTS finishes: fade early, but let the pointer be seen for at least a moment. */
    fun speechEnded() {
        speechDone = true
        if (arrivedAt > 0 && view.visibility == View.VISIBLE) {
            handler.removeCallbacks(fade)
            val wait = (arrivedAt + MIN_SHOW_MS - System.currentTimeMillis()).coerceAtLeast(0)
            handler.postDelayed(fade, wait)
        }
    }

    fun dismiss() {
        handler.removeCallbacks(fade)
        arrivedAt = 0
        view.cancel()
        view.visibility = View.INVISIBLE
    }

    private inner class PointerView : View(context) {
        private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)
        private val blue = Color.rgb(59, 130, 246)
        private val tri = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = blue; setShadowLayer(dp(4f), 0f, dp(2f), 0x66000000) }
        private val pill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = blue }
        private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; textSize = dp(14f); typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        private var pos = PointF()
        private var rotation = 0f
        private var label: String? = null
        private var showLabel = false
        private var flight: ValueAnimator? = null

        init { setLayerType(LAYER_TYPE_SOFTWARE, null) }

        fun fly(fromScreen: PointF, toScreen: PointF, label: String?, onArrived: () -> Unit) {
            val origin = IntArray(2).also { getLocationOnScreen(it) }
            val p0 = PointF(fromScreen.x - origin[0], fromScreen.y - origin[1])
            val p2 = PointF(toScreen.x - origin[0], toScreen.y - origin[1])
            val dx = p2.x - p0.x; val dy = p2.y - p0.y
            val dist = hypot(dx, dy).coerceAtLeast(1f)
            // Control point: midpoint pushed sideways (upwards) so the path arcs.
            var nx = -dy / dist; var ny = dx / dist
            if (ny > 0) { nx = -nx; ny = -ny }
            val p1 = PointF((p0.x + p2.x) / 2 + nx * dist * 0.3f, (p0.y + p2.y) / 2 + ny * dist * 0.3f)
            this.label = label; showLabel = false; alpha = 1f; visibility = VISIBLE
            val resting = -25f
            flight = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 600
                interpolator = AccelerateDecelerateInterpolator()
                addUpdateListener {
                    val t = it.animatedValue as Float; val u = 1 - t
                    pos = PointF(
                        u * u * p0.x + 2 * u * t * p1.x + t * t * p2.x,
                        u * u * p0.y + 2 * u * t * p1.y + t * t * p2.y,
                    )
                    val tx = 2 * u * (p1.x - p0.x) + 2 * t * (p2.x - p1.x)
                    val ty = 2 * u * (p1.y - p0.y) + 2 * t * (p2.y - p1.y)
                    val heading = Math.toDegrees(atan2(ty, tx).toDouble()).toFloat() + 90f
                    // Ease from the flight heading to the resting tilt over the last 20% of the flight.
                    val s = ((t - 0.8f) / 0.2f).coerceIn(0f, 1f)
                    val diff = ((heading - resting + 540f) % 360f) - 180f
                    rotation = resting + diff * (1 - s)
                    invalidate()
                }
                addListener(object : android.animation.AnimatorListenerAdapter() {
                    private var cancelled = false
                    override fun onAnimationCancel(a: android.animation.Animator) { cancelled = true }
                    override fun onAnimationEnd(a: android.animation.Animator) {
                        if (cancelled) return
                        showLabel = !label.isNullOrBlank(); invalidate(); onArrived()
                    }
                })
                start()
            }
        }

        fun fadeOut() {
            animate().alpha(0f).setDuration(300).withEndAction { visibility = INVISIBLE }.start()
        }

        fun cancel() { flight?.cancel(); animate().cancel() }

        override fun onDraw(canvas: Canvas) {
            if (visibility != VISIBLE) return
            canvas.save()
            canvas.translate(pos.x, pos.y)
            canvas.rotate(rotation)
            canvas.drawPath(Path().apply {
                moveTo(0f, 0f); lineTo(dp(11f), dp(28f)); lineTo(0f, dp(22f)); lineTo(-dp(11f), dp(28f)); close()
            }, tri)
            canvas.restore()
            val l = label
            if (showLabel && l != null) {
                val pad = dp(10f)
                val w = text.measureText(l) + pad * 2
                val h = dp(28f)
                var x = pos.x + dp(18f)
                if (x + w > width - dp(8f)) x = pos.x - dp(18f) - w
                var y = pos.y + dp(30f)
                if (y + h > height - dp(8f)) y = pos.y - dp(18f) - h
                canvas.drawRoundRect(RectF(x, y, x + w, y + h), h / 2, h / 2, pill)
                canvas.drawText(l, x + pad, y + h / 2 - (text.ascent() + text.descent()) / 2, text)
            }
        }
    }

    private companion object {
        const val SHOW_MS = 4000L
        const val MIN_SHOW_MS = 1500L
    }
}
