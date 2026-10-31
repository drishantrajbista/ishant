package dev.buddy

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.io.ByteArrayOutputStream

/**
 * A full-screen overlay showing a screenshot with a draggable crop rectangle. "Use" gives [onDone] the crop scaled for Claude,
 * Cancel gives it null, and "Save" (shown only when [onSave] is set) gives [onSave] the selected pixels at full resolution.
 */
class CropOverlay(
    private val context: Context,
    private val shot: Bitmap,
    private val onSave: ((Bitmap) -> Unit)?,
    private val onDone: (Screenshot?) -> Unit,
) {
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var root: FrameLayout? = null
    private fun dp(v: Float) = v * context.resources.displayMetrics.density

    private inner class CropView : View(context) {
        private var dest = CropRect(0f, 0f, 1f, 1f)
        private var sel: CropRect? = null
        private var handle = Handle.NONE
        private var lastX = 0f; private var lastY = 0f
        private val dim = Paint().apply { color = Color.argb(150, 0, 0, 0) }
        private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = dp(2f) }
        private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        val selection: CropRect? get() = sel
        val image: CropRect get() = dest

        override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
            dest = fitRect(w.toFloat(), h.toFloat(), shot.width, shot.height)
            sel = initialCrop(dest)
        }

        override fun onDraw(c: Canvas) {
            c.drawColor(Color.BLACK)
            c.drawBitmap(shot, null, RectF(dest.left, dest.top, dest.right, dest.bottom), null)
            val s = sel ?: return
            c.drawRect(dest.left, dest.top, dest.right, s.top, dim)
            c.drawRect(dest.left, s.bottom, dest.right, dest.bottom, dim)
            c.drawRect(dest.left, s.top, s.left, s.bottom, dim)
            c.drawRect(s.right, s.top, dest.right, s.bottom, dim)
            c.drawRect(s.left, s.top, s.right, s.bottom, line)
            for ((x, y) in listOf(s.left to s.top, s.right to s.top, s.left to s.bottom, s.right to s.bottom)) c.drawCircle(x, y, dp(9f), dot)
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            val s = sel ?: return true
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { handle = hitHandle(s, e.x, e.y, dp(28f)); lastX = e.x; lastY = e.y }
                MotionEvent.ACTION_MOVE -> {
                    sel = dragRect(s, handle, e.x - lastX, e.y - lastY, dest, dp(56f))
                    lastX = e.x; lastY = e.y
                    invalidate()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> handle = Handle.NONE
            }
            return true
        }
    }

    private fun button(text: String, fill: Int, onClick: () -> Unit) = TextView(context).apply {
        this.text = text; setTextColor(Color.WHITE); textSize = 16f; gravity = Gravity.CENTER
        setPadding(dp(22f).toInt(), dp(12f).toInt(), dp(22f).toInt(), dp(12f).toInt())
        background = GradientDrawable().apply { setColor(fill); cornerRadius = dp(28f) }
        setOnClickListener { onClick() }
    }

    fun show(): Boolean = try {
        val view = CropView()
        val bar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
            val gap = dp(6f).toInt()
            val margins = LinearLayout.LayoutParams(-2, -2).apply { setMargins(gap, 0, gap, 0) }
            addView(button("Cancel", Color.argb(200, 60, 60, 67)) { finish(null) }, margins)
            if (onSave != null) addView(button("Save", Color.argb(220, 88, 88, 96)) {
                val part = runCatching { part(view) }.getOrNull()
                remove()
                if (part != null) onSave.invoke(part) else onDone(null)
            }, LinearLayout.LayoutParams(margins))
            addView(button("Use", Color.rgb(10, 132, 255)) { finish(runCatching { part(view)?.let { scaled(it) } }.getOrNull()) }, LinearLayout.LayoutParams(margins))
        }
        val hint = TextView(context).apply {
            text = "Drag to choose the part to ask about"; setTextColor(Color.WHITE); textSize = 14f; gravity = Gravity.CENTER
        }
        root = FrameLayout(context).apply {
            addView(view, FrameLayout.LayoutParams(-1, -1))
            addView(hint, FrameLayout.LayoutParams(-1, -2, Gravity.TOP).apply { topMargin = dp(48f).toInt() })
            addView(bar, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(64f).toInt() })
        }
        val params = overlayParams(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT, true)
        if (Build.VERSION.SDK_INT >= 30) params.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        windowManager.addView(root, params)
        true
    } catch (e: RuntimeException) { false }

    /** The selected part of the screenshot at full resolution. */
    private fun part(view: CropView): Bitmap? {
        val sel = view.selection ?: return null
        val px = viewToImage(sel, view.image, shot.width, shot.height)
        return Bitmap.createBitmap(shot, px.left, px.top, px.width, px.height)
    }

    /** [part] scaled to at most [CROP_MAX_EDGE] on its long edge and JPEG-encoded, for Claude. */
    private fun scaled(part: Bitmap): Screenshot {
        val (w, h) = scaleToMaxEdge(part.width, part.height)
        val out = if (w == part.width) part else Bitmap.createScaledBitmap(part, w, h, true)
        val bytes = ByteArrayOutputStream().also { out.compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()
        return Screenshot(bytes, out.width, out.height, out.width, out.height)
    }

    private fun finish(result: Screenshot?) {
        remove()
        onDone(result)
    }

    fun remove() {
        root?.let { runCatching { windowManager.removeView(it) } }
        root = null
    }
}
