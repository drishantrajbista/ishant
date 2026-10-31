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

/** Asks Claude to reason before it answers (the Claude app has no system prompt of ours); it continues "...then answer to the point...". */
private const val THINK_CLAUSE = "think it through carefully (what i really want, what you see vs what you assume), then answer"

/** Write-and-copy for the Claude app (AGENT_PROMPT has the same rule): the written text goes in [COPY]...[/COPY] and Buddy puts it on the clipboard. */
internal const val WRITE_CLAUSE = "if i ask you to write some text for me (a note, a caption, a reply, an email, any text), or to write, text, " +
    "message or send a message to someone, write just the words to use, in the language and tone i want, and put exactly that text inside " +
    "[COPY]...[/COPY] at the end of your reply (the text can be as long as it needs; no name label, no \"message:\", no quotes, no explanation), " +
    "then say in one short sentence that it's copied and ready to paste"

private val WRITE_WORDS = setOf("write", "draft", "compose", "caption", "reply", "message", "msg", "text", "email", "mail", "lekha", "lekh", "likh", "lekhdeu", "sandesh")

/** True if a question looks like it asks for some text to be written (so the hand-off carries [WRITE_CLAUSE] even without the drawing tags). */
fun looksLikeWriteRequest(question: String): Boolean = normalizeCommand(question).split(' ').any { it in WRITE_WORDS }

/** The text that goes to the Claude app with the screenshot (and to the clipboard). */
fun handoffText(question: String, w: Int, h: Int, draw: Boolean, recent: String = "", cropped: Boolean = false): String {
    val context = if (recent.isBlank()) "" else "\n\n[buddy: $recent]"
    if (cropped) return "$question$context\n\n[buddy: the image is a cropped part of my screen. $THINK_CLAUSE to the point in one short sentence in english, no markdown. $WRITE_CLAUSE.]"
    return question + context + when {
        draw -> "\n\n[buddy: my screenshot is ${w}x$h px. $THINK_CLAUSE to the point in one short sentence in english (more only if i ask), no markdown. " +
            "$WRITE_CLAUSE. to show me on screen add tags in screenshot pixels: [POINT:x,y:label] [CIRCLE:x,y,r] [BOX:x1,y1,x2,y2] " +
            "[ARROW:x1,y1,x2,y2] [LINE:x1,y1,x2,y2] [PATH:x1,y1,x2,y2,...] [TEXT:x,y:words]]"
        looksLikeWriteRequest(question) -> "\n\n[buddy: $WRITE_CLAUSE.]"
        else -> ""   // drawing tags off: just the question
    }
}

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

// region write and copy

/** What [parseCopy] found: [text] is the reply with the [COPY] markers removed, [copy] the first block's content (null if none, or if the markers are malformed). */
class CopyParse(val text: String, val copy: String?)

private val COPY_OPEN = Regex("""\[\s*copy\s*]""", RegexOption.IGNORE_CASE)
private val COPY_CLOSE = Regex("""\[\s*/\s*copy\s*]""", RegexOption.IGNORE_CASE)
private val COPY_BLOCK = Regex("""\[\s*copy\s*](.*?)\[\s*/\s*copy\s*]""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
private val COPY_MARK = Regex("""\[\s*/?\s*copy\s*]""", RegexOption.IGNORE_CASE)

/**
 * Finds the text a reply wants on the clipboard: `[COPY]...[/COPY]`, any case, spaces and newlines allowed in and around the
 * markers. The content (trimmed, with surrounding quotes removed) is [CopyParse.copy]; nothing is copied if the markers
 * don't pair up or the content is empty. Either way the markers leave [CopyParse.text], but the content stays in it: the
 * written text is shown and spoken so the user can check it.
 */
fun parseCopy(raw: String): CopyParse {
    val balanced = COPY_OPEN.findAll(raw).count() == COPY_CLOSE.findAll(raw).count()
    val copy = if (balanced) COPY_BLOCK.find(raw)?.groupValues?.get(1)?.let(::cleanCopy)?.takeIf { it.isNotEmpty() } else null
    val text = COPY_MARK.replace(raw, " ")
        .replace("\r\n", "\n")
        .replace(Regex("""[ \t]*\n[ \t]*"""), "\n")
        .replace(Regex("""\n{3,}"""), "\n\n")
        .replace(Regex("""[ \t]{2,}"""), " ")
        .trim()
    return CopyParse(text, copy)
}

private val DOUBLE_QUOTES = setOf('"', '\u201C', '\u201D', '\u201E', '\u00AB', '\u00BB')
private val SINGLE_QUOTES = setOf('\'', '\u2018', '\u2019', '\u201A')
private val QUOTE_AT_WORD_EDGE = Regex("""(^|\s)['\u2018\u2019\u201A]|['\u2018\u2019\u201A](\s|$)""")

/** [text] trimmed, without a pair of quotes around the whole of it (not when the quotes are part of the text, like `"yes" and "no"`). */
fun trimQuotes(text: String): String {
    val t = text.trim()
    if (t.length < 2) return t
    val inner = t.substring(1, t.length - 1)
    val a = t.first(); val b = t.last()
    return when {
        a in DOUBLE_QUOTES && b in DOUBLE_QUOTES && inner.none { it in DOUBLE_QUOTES } -> inner.trim()
        a in SINGLE_QUOTES && b in SINGLE_QUOTES && !QUOTE_AT_WORD_EDGE.containsMatchIn(inner) -> inner.trim()   // apostrophes inside words are fine
        a == '`' && b == '`' && '`' !in inner -> inner.trim()
        else -> t
    }
}

private fun cleanCopy(content: String) = trimQuotes(content.replace("\r\n", "\n"))

/** A reply as shown and spoken ([spoken]: no tags, markers or markdown), the drawing it asks for, and the text for the clipboard. */
class ReadReply(val spoken: String, val shapes: List<Shape>, val copy: String?)

/** Everything in a Claude answer: [COPY] block, drawing tags and markdown, in that order of removal. */
fun readReply(raw: String): ReadReply {
    val c = parseCopy(raw)
    val a = parseAnswer(c.text)
    return ReadReply(stripMarkdown(a.spoken), a.shapes, c.copy)
}

/** The last reply on screen: what was displayed, and the text it asked to be copied, if any. */
class Reply(val display: String, val copy: String?)

/** What "copy that" and the card's Copy button put on the clipboard: the written text if the reply had a [COPY] block, else the whole reply. */
fun copyTarget(reply: Reply?): String? = reply?.let { it.copy ?: it.display }?.takeIf { it.isNotBlank() }

// endregion
