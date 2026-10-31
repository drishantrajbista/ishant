package dev.buddy

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/** A point in whichever pixel space the surrounding code is using (screenshot or display). */
data class Pt(val x: Float, val y: Float)

/** Things Claude can ask Buddy to draw. Coordinates are screenshot pixels until mapped to the display. */
sealed class Shape {
    data class Dot(val at: Pt, val label: String?) : Shape()
    data class Circle(val center: Pt, val r: Float) : Shape()
    data class Box(val a: Pt, val b: Pt) : Shape()
    data class Arrow(val from: Pt, val to: Pt) : Shape()
    data class Line(val from: Pt, val to: Pt) : Shape()
    data class Path(val points: List<Pt>) : Shape()
    data class Label(val at: Pt, val words: String) : Shape()
}

fun Shape.map(point: (Pt) -> Pt, scale: Float): Shape = when (this) {
    is Shape.Dot -> copy(at = point(at))
    is Shape.Circle -> Shape.Circle(point(center), r * scale)
    is Shape.Box -> Shape.Box(point(a), point(b))
    is Shape.Arrow -> Shape.Arrow(point(from), point(to))
    is Shape.Line -> Shape.Line(point(from), point(to))
    is Shape.Path -> Shape.Path(points.map(point))
    is Shape.Label -> copy(at = point(at))
}

/** Maps screenshot pixels to display pixels (the screenshot is a uniformly downscaled copy of the display). */
class ScreenMap(val shotW: Int, val shotH: Int, val displayW: Int, val displayH: Int) {
    private val sx = displayW.toFloat() / shotW
    private val sy = displayH.toFloat() / shotH

    /** Claude's coordinates can stray a little outside the image: pull them back onto it. */
    fun clamp(p: Pt) = Pt(p.x.coerceIn(0f, shotW.toFloat()), p.y.coerceIn(0f, shotH.toFloat()))

    fun toDisplay(p: Pt): Pt = clamp(p).let { Pt(it.x * sx, it.y * sy) }

    fun toDisplay(shape: Shape): Shape = shape.map(::toDisplay, (sx + sy) / 2)

    /** The screen rotated or changed size since the screenshot was taken, so the coordinates mean nothing now. */
    fun matches(displayW: Int, displayH: Int) = displayW == this.displayW && displayH == this.displayH
}

/** The scale that fits a w x h image within [maxEdge] on the long side and [maxPixels] in total (never enlarges). */
fun scaleFor(w: Int, h: Int, maxEdge: Int, maxPixels: Int = Int.MAX_VALUE): Float =
    minOf(1f, maxEdge.toFloat() / max(w, h), sqrt(maxPixels.toDouble() / (w.toLong() * h)).toFloat())

/** The text that goes to the Claude app with the screenshot (and to the clipboard). */
fun handoffText(question: String, w: Int, h: Int, draw: Boolean): String =
    if (!draw) question
    else "$question\n\n[buddy: my screenshot is ${w}x$h px. answer to the point in one short sentence in english (more only if i ask), no markdown. " +
        "to show me on screen add tags in screenshot pixels: [POINT:x,y:label] [CIRCLE:x,y,r] [BOX:x1,y1,x2,y2] " +
        "[ARROW:x1,y1,x2,y2] [LINE:x1,y1,x2,y2] [PATH:x1,y1,x2,y2,...] [TEXT:x,y:words]]"

/** True if the clipboard holds nothing new: it's empty, or still the question/prefilled text we copied ourselves. */
fun isOwnText(clip: String, copied: String?, question: String?): Boolean {
    val c = clip.trim()
    return c.isEmpty() || c == copied?.trim() || c == question?.trim()
}

class Answer(val spoken: String, val shapes: List<Shape>)

// A tag anywhere in the text, any case, spaces allowed, optionally wrapped in markdown (backticks, bold, italics).
private val TAG = Regex(
    """[`*_~]*\[\s*(POINT|CIRCLE|BOX|ARROW|LINE|PATH|TEXT)\s*:\s*([^\]]*?)\s*][`*_~]*""",
    RegexOption.IGNORE_CASE,
)
private val NUM = Regex("""-?\d+(?:\.\d+)?""")
private val AT = Regex("""^\s*(-?\d+(?:\.\d+)?)\s*,\s*(-?\d+(?:\.\d+)?)\s*(?::\s*(.*))?$""", RegexOption.DOT_MATCHES_ALL)

/** Splits a Claude answer into its text (every tag removed, even malformed ones) and the shapes it asks for. */
fun parseAnswer(text: String): Answer =
    Answer(removeTags(text), TAG.findAll(text).mapNotNull { shapeOf(it.groupValues[1].uppercase(), it.groupValues[2]) }.toList())

fun removeTags(text: String): String = TAG.replace(text, " ")
    .replace(Regex("""[ \t]{2,}"""), " ")
    .replace(Regex("""[ \t]+([.,!?;:])"""), "$1")
    .trim()

private fun shapeOf(name: String, args: String): Shape? {
    val nums = NUM.findAll(args).map { it.value.toFloat() }.toList()
    fun pt(i: Int) = Pt(nums[i], nums[i + 1])
    return when (name) {
        "POINT" -> if (args.trim().equals("none", ignoreCase = true)) null else AT.find(args)?.let { m ->
            val label = m.groupValues[3].replace(Regex(""":\s*screen\d+\s*$""", RegexOption.IGNORE_CASE), "").trim()
            Shape.Dot(Pt(m.groupValues[1].toFloat(), m.groupValues[2].toFloat()), label.ifEmpty { null })
        }
        "TEXT" -> AT.find(args)?.let { m ->
            m.groupValues[3].trim().takeIf { it.isNotEmpty() }
                ?.let { Shape.Label(Pt(m.groupValues[1].toFloat(), m.groupValues[2].toFloat()), it) }
        }
        "CIRCLE" -> if (nums.size >= 3) Shape.Circle(pt(0), max(1f, abs(nums[2]))) else null
        "BOX" -> if (nums.size >= 4) Shape.Box(pt(0), pt(2)) else null
        "ARROW" -> if (nums.size >= 4) Shape.Arrow(pt(0), pt(2)) else null
        "LINE" -> if (nums.size >= 4) Shape.Line(pt(0), pt(2)) else null
        "PATH" -> nums.chunked(2).filter { it.size == 2 }.map { Pt(it[0], it[1]) }.takeIf { it.size >= 2 }?.let { Shape.Path(it) }
        else -> null
    }
}

/** Plain speech from a markdown-ish reply: no headings, emphasis, bullets, links, URLs or code fences. */
fun stripMarkdown(text: String): String = text
    .replace(Regex("""```[^\n]*\n?"""), "")
    .replace(Regex("""!?\[([^\]]*)]\([^)]*\)"""), "$1")
    .replace(Regex("""https?://\S+"""), "")
    .replace(Regex("""(?m)^\s{0,3}#{1,6}\s*"""), "")
    .replace(Regex("""(?m)^\s*>\s?"""), "")
    .replace(Regex("""(?m)^\s*(-{3,}|\*{3,}|_{3,})\s*$"""), "")
    .replace(Regex("""(?m)^\s*[-*+•]\s+"""), "")
    .replace(Regex("""(\*\*|__)(.+?)\1"""), "$2")
    .replace(Regex("""(?<![\w*])\*(?!\s)([^*\n]+?)\*(?![\w*])"""), "$1")
    .replace(Regex("""(?<!\w)_(?!\s)([^_\n]+?)_(?!\w)"""), "$1")
    .replace("`", "")
    .replace(Regex("""(?<=\S)\*+|\*+(?=\S)"""), "")
    .replace(Regex("""\s+"""), " ")
    .trim()
