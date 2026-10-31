package dev.buddy

import kotlin.math.max
import kotlin.math.roundToInt

/** Pure rectangle math for the crop view (kept free of android.graphics so it can be unit tested). */
data class CropRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width get() = right - left
    val height get() = bottom - top
}

data class PixelRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width get() = right - left
    val height get() = bottom - top
}

enum class Handle { NONE, MOVE, TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT }

const val CROP_MAX_EDGE = 1280

/** Where an [imgW]x[imgH] image is drawn, centered and scaled to fit a [viewW]x[viewH] view. */
fun fitRect(viewW: Float, viewH: Float, imgW: Int, imgH: Int): CropRect {
    val s = minOf(viewW / imgW, viewH / imgH)
    val w = imgW * s; val h = imgH * s
    val l = (viewW - w) / 2; val t = (viewH - h) / 2
    return CropRect(l, t, l + w, t + h)
}

/** The starting selection: the middle of the image. */
fun initialCrop(b: CropRect) = CropRect(b.left + b.width * 0.15f, b.top + b.height * 0.25f, b.right - b.width * 0.15f, b.bottom - b.height * 0.25f)

/** [r] kept inside [b] with at least [min] on each side (a rectangle bigger than [b] shrinks, one off its edge slides back). */
fun clampRect(r: CropRect, b: CropRect, min: Float): CropRect {
    val m = minOf(min, b.width, b.height)
    val w = r.width.coerceIn(m, b.width); val h = r.height.coerceIn(m, b.height)
    val l = r.left.coerceIn(b.left, b.right - w); val t = r.top.coerceIn(b.top, b.bottom - h)
    return CropRect(l, t, l + w, t + h)
}

/** What a finger at ([x],[y]) grabs: a corner within [slop], else the inside (move), else nothing. */
fun hitHandle(r: CropRect, x: Float, y: Float, slop: Float): Handle {
    fun near(cx: Float, cy: Float) = kotlin.math.abs(x - cx) <= slop && kotlin.math.abs(y - cy) <= slop
    return when {
        near(r.left, r.top) -> Handle.TOP_LEFT
        near(r.right, r.top) -> Handle.TOP_RIGHT
        near(r.left, r.bottom) -> Handle.BOTTOM_LEFT
        near(r.right, r.bottom) -> Handle.BOTTOM_RIGHT
        x in r.left..r.right && y in r.top..r.bottom -> Handle.MOVE
        else -> Handle.NONE
    }
}

/** [r] after dragging [handle] by ([dx],[dy]), never leaving [b] or getting smaller than [min]. */
fun dragRect(r: CropRect, handle: Handle, dx: Float, dy: Float, b: CropRect, min: Float): CropRect {
    val m = minOf(min, b.width, b.height)
    var l = r.left; var t = r.top; var rt = r.right; var bt = r.bottom
    when (handle) {
        Handle.NONE -> return r
        Handle.MOVE -> return clampRect(CropRect(l + dx, t + dy, rt + dx, bt + dy), b, m)
        Handle.TOP_LEFT -> { l += dx; t += dy }
        Handle.TOP_RIGHT -> { rt += dx; t += dy }
        Handle.BOTTOM_LEFT -> { l += dx; bt += dy }
        Handle.BOTTOM_RIGHT -> { rt += dx; bt += dy }
    }
    l = l.coerceIn(b.left, r.right - m); rt = rt.coerceIn(r.left + m, b.right)
    t = t.coerceIn(b.top, r.bottom - m); bt = bt.coerceIn(r.top + m, b.bottom)
    return CropRect(
        if (handle == Handle.TOP_LEFT || handle == Handle.BOTTOM_LEFT) l else r.left,
        if (handle == Handle.TOP_LEFT || handle == Handle.TOP_RIGHT) t else r.top,
        if (handle == Handle.TOP_RIGHT || handle == Handle.BOTTOM_RIGHT) rt else r.right,
        if (handle == Handle.BOTTOM_LEFT || handle == Handle.BOTTOM_RIGHT) bt else r.bottom,
    )
}

/** The selection [r] (view coordinates) as pixels of the [imgW]x[imgH] image drawn into [dest]; at least 1 px each way. */
fun viewToImage(r: CropRect, dest: CropRect, imgW: Int, imgH: Int): PixelRect {
    val sx = imgW / dest.width; val sy = imgH / dest.height
    val l = ((r.left - dest.left) * sx).roundToInt().coerceIn(0, imgW - 1)
    val t = ((r.top - dest.top) * sy).roundToInt().coerceIn(0, imgH - 1)
    val rt = ((r.right - dest.left) * sx).roundToInt().coerceIn(l + 1, imgW)
    val bt = ((r.bottom - dest.top) * sy).roundToInt().coerceIn(t + 1, imgH)
    return PixelRect(l, t, rt, bt)
}

/** [w]x[h] scaled down (never up) so the long edge is at most [maxEdge]. */
fun scaleToMaxEdge(w: Int, h: Int, maxEdge: Int = CROP_MAX_EDGE): Pair<Int, Int> {
    val longEdge = max(w, h)
    if (longEdge <= maxEdge) return w to h
    val s = maxEdge.toFloat() / longEdge
    return (w * s).roundToInt().coerceAtLeast(1) to (h * s).roundToInt().coerceAtLeast(1)
}
