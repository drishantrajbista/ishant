package dev.buddy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ParsingTest {
    @Test fun pointTagIsStrippedAndParsed() {
        val r = parsePointingCoordinates("open settings, then tap display. [POINT:540,410:dark theme]")
        assertEquals("open settings, then tap display.", r.spokenText)
        assertEquals(540f, r.coordinate!!.x, 0f)
        assertEquals(410f, r.coordinate!!.y, 0f)
        assertEquals("dark theme", r.label)
    }

    @Test fun pointNoneAndMissingTag() {
        val none = parsePointingCoordinates("html is a markup language. [POINT:none]")
        assertEquals("html is a markup language.", none.spokenText)
        assertNull(none.coordinate)
        assertEquals("none", none.label)
        val missing = parsePointingCoordinates("no tag here")
        assertEquals("no tag here", missing.spokenText)
        assertNull(missing.label)
    }

    @Test fun trailingScreenNumberIsTolerated() {
        val r = parsePointingCoordinates("over there [POINT:10,20:thing:screen2]")
        assertEquals("over there", r.spokenText)
        assertEquals("thing", r.label)
    }

    @Test fun sentencesAreCompleteOnlyAfterWhitespace() {
        assertEquals(0, sentenceEnd("hello there", 0))
        assertEquals(0, sentenceEnd("hello there.", 0))            // no whitespace after the full stop yet
        assertEquals(13, sentenceEnd("hello there. wor", 0))
        assertEquals(13, sentenceEnd("hello there. wor", 13))
        assertEquals(26, sentenceEnd("hello there. how are you? i", 13))
        assertEquals(7, sentenceEnd("line 1\nline", 0))
        assertEquals(26, sentenceEnd("hello there. how are you? i", 0))   // both sentences at once
    }

    @Test fun decimalsAndDottedNamesDoNotSplit() {
        assertEquals(0, sentenceEnd("it costs 3.5 dollars", 0))
        assertEquals(0, sentenceEnd("open a.b.c now", 0))
    }
}

class HeardParserTest {
    private fun feedAll(vararg chunks: String): Pair<HeardParser, String?> {
        val p = HeardParser()
        var full = ""
        var out: String? = null
        for (c in chunks) { full += c; out = p.feed(full, final = false) }
        return p to out
    }

    @Test fun tagSplitAcrossChunksIsBufferedThenStripped() {
        val p = HeardParser()
        assertNull(p.feed("[HE", false))
        assertNull(p.feed("[HEARD: how do i turn", false))
        assertEquals("open settings", p.feed("[HEARD: how do i turn on dark mode] open settings", false))
        assertEquals("how do i turn on dark mode", p.heard)
        assertEquals("open settings now", p.feed("[HEARD: how do i turn on dark mode] open settings now", false))
    }

    @Test fun lowercaseTagAndLeadingWhitespaceWork() {
        val (p, out) = feedAll("\n [heard: namaste, how are you] hi there")
        assertEquals("namaste, how are you", p.heard)
        assertEquals("hi there", out)
    }

    @Test fun replyWithoutTagFlowsThroughImmediately() {
        val p = HeardParser()
        assertEquals("sure, open settings", p.feed("sure, open settings", false))
        assertNull(p.heard)
        assertEquals(true, p.decided)
    }

    @Test fun shortUndecidedTextIsHeldBackUntilFinal() {
        val p = HeardParser()
        assertNull(p.feed("[", false))
        assertEquals("[", p.feed("[", true))   // stream ended: no tag after all
    }

    @Test fun nestedBracketsInsideTheTag() {
        val (p, out) = feedAll("[HEARD: what is [x] in math] it is a variable")
        assertEquals("what is [x] in math", p.heard)
        assertEquals("it is a variable", out)
    }

    @Test fun neverClosedTagIsTreatedAsAbsent() {
        val p = HeardParser()
        val long = "[HEARD: " + "a".repeat(1300)
        assertEquals(long, p.feed(long, false))
        assertNull(p.heard)
    }
}
