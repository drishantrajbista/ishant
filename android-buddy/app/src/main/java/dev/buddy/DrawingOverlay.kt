package dev.buddy

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.LinearInterpolator
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Full-screen, touch-transparent overlay where Claude's cursor flies around and draws. Shapes are drawn one after
 * another: the cursor flies to a shape's start (~250 ms) and then traces it while it appears (~500 ms). Drawings are
 * cleared 6 s after the speech has ended (and the drawing is done), on a tap, or when a new question starts.
 */
class DrawingOverlay(private val context: Context) {
    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val view = DrawingView()
    private val handler = Handler(Looper.getMainLooper())
    private val params = overlayParams(
        WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT, touchable = false,
    ).apply {
        flags = flags or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        // Android 12+ blocks touches that pass through another app's overlay window whose alpha is above 0.8, and
        // this window covers the whole screen. 0.8 is the most opaque it may be and still let touches through; the
        // view is also GONE whenever there is nothing to draw, so it normally isn't there at all.
        alpha = 0.8f
    }
    private var added = false
    private var finished = true
    private var speechDone = false
    private val clearLater = Runnable { clear() }

    val hasContent get() = view.hasContent

    fun show() {
        if (added) return
        view.visibility = View.GONE
        wm.addView(view, params)
        added = true
    }

    fun remove() {
        if (!added) return
        added = false
        handler.removeCallbacks(clearLater)
        view.clear()
        wm.removeView(view)
    }

    /** Hides everything (without forgetting it) so the overlay isn't in a screenshot. */
    fun setSuppressed(suppressed: Boolean) = view.setSuppressed(suppressed)

    /** [shapes] are in display pixels; the cursor starts at [from] (screen pixels). */
    fun play(shapes: List<Shape>, from: PointF) {
        clear()
        finished = false
        speechDone = false
        view.play(shapes, from) { finished = true; clearSoon() }
    }

    /** Agent mode: the cursor glides to [to] (screen px) in [ms]. It stays on screen until [clear]. Suspends until there. */
    suspend fun glideTo(to: PointF, from: PointF, ms: Long = 200) = suspendCancellableCoroutine<Unit> { cont ->
        handler.removeCallbacks(clearLater)
        view.glide(to, from, ms) { if (cont.isActive) cont.resume(Unit) }
        cont.invokeOnCancellation { view.stopAnimation() }
    }

    /** The reply has been spoken: the drawings stay a little longer, then go. */
    fun speechEnded() {
        speechDone = true
        clearSoon()
    }

    fun clear() {
        handler.removeCallbacks(clearLater)
        view.clear()
    }

    private fun clearSoon() {
        if (finished && speechDone && view.hasContent) {
            handler.removeCallbacks(clearLater)
            handler.postDelayed(clearLater, CLEAR_MS)
        }
    }

    private class Item(val shape: Shape, val poly: List<PointF>) {
        val cum = FloatArray(poly.size).also { for (i in 1 until poly.size) it[i] = it[i - 1] + hypot(poly[i].x - poly[i - 1].x, poly[i].y - poly[i - 1].y) }
        var progress = 0f
        var alpha = 0f
        val total get() = cum.last()

        fun pointAt(t: Float): PointF {
            if (poly.size == 1 || total == 0f) return poly.first()
            val d = t * total
            val i = (1 until poly.size).firstOrNull { cum[it] >= d } ?: (poly.size - 1)
            val seg = cum[i] - cum[i - 1]
            val f = if (seg == 0f) 0f else (d - cum[i - 1]) / seg
            return PointF(poly[i - 1].x + (poly[i].x - poly[i - 1].x) * f, poly[i - 1].y + (poly[i].y - poly[i - 1].y) * f)
        }
    }

    private class Step(val ms: Long, val interpolator: android.animation.TimeInterpolator, val update: (Float) -> Unit)

    private inner class DrawingView : View(context) {
        private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)
        private val claude = Color.rgb(0xD9, 0x77, 0x57)
        private val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(153, 255, 255, 255); style = Paint.Style.STROKE; strokeWidth = dp(8f)
            strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        }
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = claude; style = Paint.Style.STROKE; strokeWidth = dp(5f)
            strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        }
        private val cursorFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(59, 130, 246) }
        private val cursorEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = dp(2f); strokeJoin = Paint.Join.ROUND
        }
        private val pillFill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val pillText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; textSize = dp(14f); typeface = Typeface.DEFAULT_BOLD
        }

        private val items = mutableListOf<Item>()
        private var cursor: PointF? = null
        private var suppressed = false
        private var anim: ValueAnimator? = null
        private var cancelled = false
        private val steps = ArrayDeque<Step>()
        private var onDone: (() -> Unit)? = null

        val hasContent get() = items.isNotEmpty() || cursor != null

        private fun updateVisibility() { visibility = if (suppressed || !hasContent) GONE else VISIBLE }

        fun setSuppressed(b: Boolean) { suppressed = b; updateVisibility(); invalidate() }

        fun play(shapes: List<Shape>, from: PointF, done: () -> Unit) {
            visibility = if (suppressed) GONE else VISIBLE
            val loc = IntArray(2).also { getLocationOnScreen(it) }
            fun v(p: Pt) = PointF(p.x - loc[0], p.y - loc[1])
            cancelled = false
            onDone = done
            cursor = PointF(from.x - loc[0], from.y - loc[1])
            val ease = AccelerateDecelerateInterpolator()
            val linear = LinearInterpolator()
            for (shape in shapes) {
                val item = Item(shape, polyline(shape).map(::v))
                items += item
                val start = item.poly.first()
                var origin = cursor!!
                steps += Step(FLY_MS, ease) { t ->
                    if (t == 0f) origin = PointF(cursor!!.x, cursor!!.y)
                    cursor = PointF(origin.x + (start.x - origin.x) * t, origin.y + (start.y - origin.y) * t)
                }
                when (shape) {
                    is Shape.Dot -> { steps += Step(PAUSE_MS * 4, linear) { item.alpha = 1f } }
                    is Shape.Label -> steps += Step(FADE_MS, linear) { item.alpha = it }
                    else -> {
                        item.alpha = 1f
                        steps += Step(TRACE_MS, linear) { t -> item.progress = t; cursor = item.pointAt(t) }
                    }
                }
                steps += Step(PAUSE_MS, linear) {}
            }
            next()
        }

        private fun next() {
            val step = steps.removeFirstOrNull()
            if (step == null) { anim = null; onDone?.invoke(); return }
            step.update(0f)
            anim = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = step.ms
                interpolator = step.interpolator
                addUpdateListener { step.update(it.animatedValue as Float); invalidate() }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationCancel(a: Animator) { cancelled = true }
                    override fun onAnimationEnd(a: Animator) { if (!cancelled) next() }
                })
                start()
            }
        }

        fun stopAnimation() { cancelled = true; anim?.cancel(); anim = null; steps.clear() }

        fun glide(to: PointF, from: PointF, ms: Long, done: () -> Unit) {
            stopAnimation()
            visibility = if (suppressed) GONE else VISIBLE
            val loc = IntArray(2).also { getLocationOnScreen(it) }
            val start = cursor ?: PointF(from.x - loc[0], from.y - loc[1])
            val end = PointF(to.x - loc[0], to.y - loc[1])
            cancelled = false
            onDone = done
            cursor = start
            steps += Step(ms, AccelerateDecelerateInterpolator()) { t ->
                cursor = PointF(start.x + (end.x - start.x) * t, start.y + (end.y - start.y) * t)
            }
            next()
        }

        fun clear() {
            cancelled = true
            anim?.cancel()
            anim = null
            steps.clear()
            items.clear()
            cursor = null
            onDone = null
            visibility = GONE
            invalidate()
        }

        private fun polyline(shape: Shape): List<Pt> = when (shape) {
            is Shape.Dot -> listOf(shape.at)
            is Shape.Label -> listOf(shape.at)
            is Shape.Line -> listOf(shape.from, shape.to)
            is Shape.Arrow -> listOf(shape.from, shape.to)
            is Shape.Path -> shape.points
            is Shape.Box -> {
                val l = min(shape.a.x, shape.b.x); val r = max(shape.a.x, shape.b.x)
                val t = min(shape.a.y, shape.b.y); val b = max(shape.a.y, shape.b.y)
                listOf(Pt(l, t), Pt(r, t), Pt(r, b), Pt(l, b), Pt(l, t))
            }
            is Shape.Circle -> (0..48).map {
                val a = Math.toRadians(-90.0 + it * 360.0 / 48)
                Pt(shape.center.x + shape.r * cos(a).toFloat(), shape.center.y + shape.r * sin(a).toFloat())
            }
        }

        override fun onDraw(canvas: Canvas) {
            if (suppressed) return
            for (item in items) drawItem(canvas, item)
            cursor?.let { drawCursor(canvas, it) }
        }

        private fun drawItem(canvas: Canvas, item: Item) {
            when (val s = item.shape) {
                is Shape.Dot -> s.label?.let { pill(canvas, it, item.poly[0].x + dp(20f), item.poly[0].y + dp(34f), claude, item.alpha, topLeft = true) }
                is Shape.Label -> pill(canvas, s.words, item.poly[0].x, item.poly[0].y, Color.argb(235, 32, 33, 36), item.alpha, topLeft = false)
                else -> {
                    if (item.progress <= 0f) return
                    val path = Path()
                    val d = item.progress * item.total
                    path.moveTo(item.poly[0].x, item.poly[0].y)
                    for (i in 1 until item.poly.size) {
                        if (item.cum[i] <= d) path.lineTo(item.poly[i].x, item.poly[i].y)
                        else {
                            val f = (d - item.cum[i - 1]) / (item.cum[i] - item.cum[i - 1])
                            path.lineTo(item.poly[i - 1].x + (item.poly[i].x - item.poly[i - 1].x) * f, item.poly[i - 1].y + (item.poly[i].y - item.poly[i - 1].y) * f)
                            break
                        }
                    }
                    if (s is Shape.Arrow) arrowHead(path, item)
                    canvas.drawPath(path, halo)
                    canvas.drawPath(path, stroke)
                }
            }
        }

        /** The head sits at the tip of the stroke as it grows. */
        private fun arrowHead(path: Path, item: Item) {
            val tip = item.pointAt(item.progress)
            val a = item.poly.first()
            val angle = atan2((tip.y - a.y).toDouble(), (tip.x - a.x).toDouble())
            val len = dp(22f)
            for (side in listOf(-1, 1)) {
                val b = angle + Math.PI + side * Math.toRadians(28.0)
                path.moveTo(tip.x, tip.y)
                path.lineTo(tip.x + len * cos(b).toFloat(), tip.y + len * sin(b).toFloat())
            }
        }

        private fun pill(canvas: Canvas, text: String, x: Float, y: Float, color: Int, alpha: Float, topLeft: Boolean) {
            if (alpha <= 0f) return
            val pad = dp(10f)
            val w = pillText.measureText(text) + pad * 2
            val h = dp(30f)
            var left = if (topLeft) x else x - w / 2
            var top = if (topLeft) y else y - h / 2
            left = left.coerceIn(dp(6f), max(dp(6f), width - w - dp(6f)))
            top = top.coerceIn(dp(6f), max(dp(6f), height - h - dp(6f)))
            pillFill.color = color
            pillFill.alpha = (Color.alpha(color) * alpha).toInt()
            pillText.alpha = (255 * alpha).toInt()
            canvas.drawRoundRect(RectF(left, top, left + w, top + h), h / 2, h / 2, pillFill)
            canvas.drawText(text, left + pad, top + h / 2 - (pillText.ascent() + pillText.descent()) / 2, pillText)
        }

        private fun drawCursor(canvas: Canvas, at: PointF) {
            canvas.save()
            canvas.translate(at.x, at.y)
            canvas.rotate(-25f)
            val p = Path().apply { moveTo(0f, 0f); lineTo(dp(11f), dp(28f)); lineTo(0f, dp(22f)); lineTo(-dp(11f), dp(28f)); close() }
            canvas.drawPath(p, cursorFill)
            canvas.drawPath(p, cursorEdge)
            canvas.restore()
        }
    }

    private companion object {
        const val FLY_MS = 250L
        const val TRACE_MS = 500L
        const val FADE_MS = 250L
        const val PAUSE_MS = 150L
        const val CLEAR_MS = 6000L
    }
}
