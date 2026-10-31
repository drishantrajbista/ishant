package dev.buddy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TagParserTest {
    private fun shapes(text: String) = parseAnswer(text).shapes

    @Test fun everyShapeParses() {
        val a = parseAnswer(
            "tap here. [POINT:540,410:dark theme] [CIRCLE:100,200,50] [BOX:10,20,300,400] [ARROW:1,2,3,4] " +
                "[LINE:5,6,7,8] [PATH:1,1,2,2,3,3] [TEXT:60,70:new email]",
        )
        assertEquals(
            listOf(
                Shape.Dot(Pt(540f, 410f), "dark theme"), Shape.Circle(Pt(100f, 200f), 50f),
                Shape.Box(Pt(10f, 20f), Pt(300f, 400f)), Shape.Arrow(Pt(1f, 2f), Pt(3f, 4f)),
                Shape.Line(Pt(5f, 6f), Pt(7f, 8f)), Shape.Path(listOf(Pt(1f, 1f), Pt(2f, 2f), Pt(3f, 3f))),
                Shape.Label(Pt(60f, 70f), "new email"),
            ),
            a.shapes,
        )
        assertEquals("tap here.", a.spoken)
    }

    @Test fun caseSpacesAndDecimalsAreTolerated() {
        assertEquals(
            listOf(Shape.Circle(Pt(100.5f, 200f), 30.25f), Shape.Dot(Pt(1f, 2f), "search bar")),
            shapes("[ circle : 100.5 , 200 , 30.25 ] [point: 1 ,2 : search bar ]"),
        )
    }

    @Test fun pointNoneAndNoTagsMeanNothingToDraw() {
        assertTrue(shapes("a vpn routes your traffic. [POINT:none]").isEmpty())
        assertEquals("a vpn routes your traffic.", parseAnswer("a vpn routes your traffic. [POINT:none]").spoken)
        assertTrue(shapes("just words").isEmpty())
    }

    @Test fun tagsAnywhereAndInOrder() {
        val a = parseAnswer("first [POINT:1,2:a] then [POINT:3,4:b] done")
        assertEquals(listOf(Shape.Dot(Pt(1f, 2f), "a"), Shape.Dot(Pt(3f, 4f), "b")), a.shapes)
        assertEquals("first then done", a.spoken)
    }

    @Test fun markdownWrappedTagsAreFoundAndRemovedWithTheirWrappers() {
        val a = parseAnswer("see this `[POINT:5,6:button]` and **[CIRCLE:7,8,9]** and _[BOX:1,2,3,4]_ ok.")
        assertEquals(3, a.shapes.size)
        assertEquals("see this and and ok.", a.spoken)
    }

    @Test fun malformedTagsAreIgnoredButNeverSpoken() {
        val a = parseAnswer("hi [CIRCLE:abc] [BOX:1,2,3] [POINT:12] [PATH:1,2] [TEXT:5,5:] [ARROW:1,2,3,4,5] bye")
        assertEquals(listOf<Shape>(Shape.Arrow(Pt(1f, 2f), Pt(3f, 4f))), a.shapes)   // the extra number is ignored
        assertEquals("hi bye", a.spoken)
        assertTrue(parseAnswer("[POINT:]").shapes.isEmpty())
    }

    @Test fun pathNeedsTwoPointsAndDropsADanglingNumber() {
        assertEquals(listOf<Shape>(Shape.Path(listOf(Pt(1f, 2f), Pt(3f, 4f)))), shapes("[PATH:1,2,3,4,5]"))
    }

    @Test fun trailingScreenSuffixIsDroppedFromLabels() {
        assertEquals(Shape.Dot(Pt(1f, 2f), "thing"), shapes("[POINT:1,2:thing:screen2]").single())
    }

    @Test fun negativeAndHugeValuesStillParse() {
        assertEquals(Shape.Dot(Pt(-5f, 99999f), null), shapes("[POINT:-5,99999]").single())
    }
}

class MarkdownTest {
    @Test fun emphasisHeadingsBulletsAndCode() {
        assertEquals("open settings then tap display now", stripMarkdown("**open** *settings* then `tap` display _now_"))
        assertEquals("Steps one two", stripMarkdown("# Steps\n- one\n- two"))
        assertEquals("a b", stripMarkdown("## a\n* b"))
    }

    @Test fun linksUrlsAndFences() {
        assertEquals("see the docs for more", stripMarkdown("see [the docs](https://example.com/x) for more"))
        assertEquals("go to now", stripMarkdown("go to https://example.com/page now"))
        assertEquals("val x = 1", stripMarkdown("```kotlin\nval x = 1\n```"))
    }

    @Test fun wordsAndMathAreLeftAlone() {
        assertEquals("keep snake_case_name here", stripMarkdown("keep snake_case_name here"))
        assertEquals("2 * 3 * 4", stripMarkdown("2 * 3 * 4"))
        assertEquals("one. two.", stripMarkdown("one.\n\ntwo."))
    }

    @Test fun quotesAndRules() {
        assertEquals("quoted text after", stripMarkdown("> quoted text\n---\nafter"))
    }
}

class ScalingTest {
    @Test fun screenshotPixelsMapToDisplayPixels() {
        val m = ScreenMap(shotW = 655, shotH = 1456, displayW = 1080, displayH = 2400)
        val p = m.toDisplay(Pt(327.5f, 728f))
        assertEquals(540f, p.x, 0.5f)
        assertEquals(1200f, p.y, 0.5f)
    }

    @Test fun coordinatesAreClampedToTheScreenshot() {
        val m = ScreenMap(100, 200, 1000, 2000)
        assertEquals(Pt(1000f, 0f), m.toDisplay(Pt(150f, -20f)))
    }

    @Test fun radiiScaleWithTheDisplay() {
        val m = ScreenMap(100, 200, 1000, 2000)
        val c = m.toDisplay(Shape.Circle(Pt(50f, 100f), 10f)) as Shape.Circle
        assertEquals(100f, c.r, 0.01f)
        assertEquals(Pt(500f, 1000f), c.center)
    }

    @Test fun aRotatedScreenNoLongerMatches() {
        val m = ScreenMap(655, 1456, 1080, 2400)
        assertTrue(m.matches(1080, 2400))
        assertFalse(m.matches(2400, 1080))
    }

    @Test fun handoffImageFitsClaudesLimits() {
        // 1080x2400 at long edge 1456 / 1.15 MP: the long edge is the binding limit.
        val s = scaleFor(1080, 2400, 1456, 1_150_000)
        assertEquals(1456f / 2400f, s, 0.0001f)
        assertTrue(1080 * s * 2400 * s <= 1_150_000f)
        // A squarer 2000x2200 screen is limited by the pixel count instead.
        val q = scaleFor(2000, 2200, 1456, 1_150_000)
        assertTrue(2000 * q * 2200 * q <= 1_150_001f)
        assertTrue(2200 * q <= 1456f)
        // Never enlarge.
        assertEquals(1f, scaleFor(400, 800, 1456, 1_150_000), 0f)
    }
}

class HandoffTest {
    @Test fun prefilledTextIsExact() {
        assertEquals(
            "how do i turn on dark mode\n\n[buddy: my screenshot is 655x1456 px. answer to the point in one short sentence in english (more only if i ask), " +
                "no markdown. to show me on screen add tags in screenshot pixels: [POINT:x,y:label] [CIRCLE:x,y,r] " +
                "[BOX:x1,y1,x2,y2] [ARROW:x1,y1,x2,y2] [LINE:x1,y1,x2,y2] [PATH:x1,y1,x2,y2,...] [TEXT:x,y:words]]",
            handoffText("how do i turn on dark mode", 655, 1456, draw = true),
        )
    }

    @Test fun withDrawingOffOnlyTheQuestionIsSent() {
        assertEquals("what is this", handoffText("what is this", 655, 1456, draw = false))
    }

    @Test fun clipboardThatStillHoldsOurOwnTextMeansNothingWasCopied() {
        val question = "how do i turn on dark mode"
        val copied = handoffText(question, 655, 1456, draw = true)
        assertTrue(isOwnText(copied, copied, question))
        assertTrue(isOwnText("  $copied\n", copied, question))
        assertTrue(isOwnText(question, copied, question))
        assertTrue(isOwnText("", copied, question))
        assertTrue(isOwnText("   \n", null, null))
        assertFalse(isOwnText("open settings and tap display. [POINT:540,410:dark theme]", copied, question))
        assertFalse(isOwnText("anything", null, null))
        assertNull(null)
    }
}

class BlankFrameTest {
    @Test fun allBlackIsBlank() = assertTrue(isBlankFrame(IntArray(100) { 0xFF000000.toInt() }))
    @Test fun nearBlackIsStillBlank() = assertTrue(isBlankFrame(IntArray(100) { 0xFF050505.toInt() }))
    @Test fun anyRealContentIsNot() {
        val px = IntArray(100) { 0xFF000000.toInt() }
        px[42] = 0xFF303030.toInt()
        assertFalse(isBlankFrame(px))
        assertFalse(isBlankFrame(IntArray(100) { 0xFFFFFFFF.toInt() }))
        assertFalse(isBlankFrame(IntArray(100) { 0xFF0000FF.toInt() }))   // pure blue: luminance ~29
    }
    @Test fun noSamplesIsNotBlank() = assertFalse(isBlankFrame(IntArray(0)))
}
