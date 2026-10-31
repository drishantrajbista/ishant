package dev.buddy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class CropMathTest {
    private val bounds = CropRect(0f, 0f, 1000f, 2000f)

    @Test fun fitCentersTheImage() {
        val r = fitRect(1000f, 2000f, 500, 500)   // a square image in a tall view
        assertEquals(CropRect(0f, 500f, 1000f, 1500f), r)
        assertEquals(CropRect(0f, 0f, 1000f, 2000f), fitRect(1000f, 2000f, 1080, 2160))
    }

    @Test fun clampKeepsInsideAndMinSize() {
        assertEquals(CropRect(900f, 1900f, 1000f, 2000f), clampRect(CropRect(950f, 1950f, 1050f, 2050f), bounds, 50f))   // slides back
        assertEquals(50f, clampRect(CropRect(10f, 10f, 20f, 15f), bounds, 50f).width, 0.001f)                           // grows to min
        assertEquals(bounds, clampRect(CropRect(-100f, -100f, 2000f, 3000f), bounds, 50f))                              // shrinks to bounds
        assertEquals(10f, clampRect(CropRect(0f, 0f, 5f, 5f), CropRect(0f, 0f, 10f, 10f), 50f).width, 0.001f)          // min bigger than bounds
    }

    @Test fun dragCornersAndMove() {
        val r = CropRect(100f, 200f, 500f, 800f)
        assertEquals(CropRect(150f, 250f, 500f, 800f), dragRect(r, Handle.TOP_LEFT, 50f, 50f, bounds, 56f))
        assertEquals(CropRect(100f, 200f, 1000f, 900f), dragRect(r, Handle.BOTTOM_RIGHT, 9999f, 100f, bounds, 56f))   // stops at the edge
        val squeezed = dragRect(r, Handle.TOP_LEFT, 9999f, 9999f, bounds, 56f)                                         // can't invert
        assertEquals(56f, squeezed.width, 0.001f); assertEquals(56f, squeezed.height, 0.001f)
        assertEquals(CropRect(0f, 200f, 400f, 800f), dragRect(r, Handle.MOVE, -500f, 0f, bounds, 56f))
        assertEquals(r, dragRect(r, Handle.NONE, 40f, 40f, bounds, 56f))
    }

    @Test fun hitTesting() {
        val r = CropRect(100f, 200f, 500f, 800f)
        assertEquals(Handle.TOP_LEFT, hitHandle(r, 110f, 190f, 28f))
        assertEquals(Handle.BOTTOM_RIGHT, hitHandle(r, 490f, 810f, 28f))
        assertEquals(Handle.MOVE, hitHandle(r, 300f, 500f, 28f))
        assertEquals(Handle.NONE, hitHandle(r, 700f, 500f, 28f))
    }

    @Test fun viewToImageScalesAndClamps() {
        val dest = CropRect(0f, 750f, 1000f, 1250f)            // a 2000x1000 image drawn at half size, centered
        assertEquals(PixelRect(200, 100, 1000, 500), viewToImage(CropRect(100f, 800f, 500f, 1000f), dest, 2000, 1000))
        assertEquals(PixelRect(0, 0, 2000, 1000), viewToImage(CropRect(-50f, 0f, 2000f, 3000f), dest, 2000, 1000))
        val tiny = viewToImage(CropRect(100f, 900f, 100f, 900f), dest, 2000, 1000)                              // never empty
        assertTrue(tiny.width >= 1 && tiny.height >= 1)
    }

    @Test fun scalingNeverGrows() {
        assertEquals(800 to 600, scaleToMaxEdge(800, 600))
        assertEquals(1280 to 640, scaleToMaxEdge(2560, 1280))
        assertEquals(640 to 1280, scaleToMaxEdge(1000, 2000))
        assertEquals(1 to 1280, scaleToMaxEdge(1, 5000))
    }
}

class TranscriptTest {
    private fun ex(i: Int, user: String = "q$i", reply: String = "a$i", at: Long = i * 1000L) = Exchange(at, user, reply, "claude api")

    @Test fun keepsTheLast300() {
        var list = emptyList<Exchange>()
        for (i in 1..MAX_EXCHANGES + 7) list = addExchange(list, ex(i))
        assertEquals(MAX_EXCHANGES, list.size)
        assertEquals("q8", list.first().user)
        assertEquals("q${MAX_EXCHANGES + 7}", list.last().user)
    }

    @Test fun contextHasTheLast10OldestFirst() {
        val list = (1..15).map { ex(it) }
        val text = recentContext(list)
        assertTrue(text.startsWith("recent conversation"))
        assertFalse(text.contains("user: q5\n"))
        assertTrue(text.indexOf("user: q6\n") in 0 until text.indexOf("user: q15\n"))
        assertEquals("", recentContext(emptyList()))
    }

    @Test fun contextItemsAndTotalAreCapped() {
        val long = "word ".repeat(200)
        val text = recentContext(listOf(ex(1, long, long)))
        assertTrue(text.lines().drop(1).all { it.length <= RECENT_ITEM_CHARS + 8 })
        val many = (1..10).map { ex(it, long, long) }
        val capped = recentContext(many)
        assertTrue(capped.length <= RECENT_CONTEXT_CHARS)
        assertTrue(capped.contains("user: word"))
        assertEquals(RECENT_CONTEXT_CHARS, 1500)
    }

    @Test fun totalCapDropsTheOldestFirst() {
        val long = "x".repeat(290)
        val list = (1..10).map { ex(it, "$it $long", "r$it $long") }
        val text = recentContext(list)
        assertTrue(text.contains("user: 10 "))
        assertFalse(text.contains("user: 1 "))
    }

    @Test fun appHandoffLine() {
        assertEquals("", recentLine(emptyList()))
        val line = recentLine((1..5).map { ex(it) })
        assertTrue(line.contains("q5") && line.contains("q3") && !line.contains("q2"))
        assertTrue(recentLine((1..3).map { ex(it, "u".repeat(400), "r".repeat(400)) }).length <= 500)
    }

    @Test fun handoffTextKeepsOldBehaviourAndAddsContext() {
        assertEquals("hi", handoffText("hi", 100, 200, false))
        assertTrue(handoffText("hi", 100, 200, true).contains("my screenshot is 100x200 px"))
        assertTrue(handoffText("hi", 100, 200, false, "earlier: x").contains("[buddy: earlier: x]"))
        val cropped = handoffText("hi", 100, 200, true, "", cropped = true)
        assertTrue(cropped.contains("cropped part of my screen") && !cropped.contains("POINT"))
    }

    @Test fun groupsByDayNewestFirstAndSearches() {
        val zone = ZoneId.of("UTC")
        val day1 = LocalDate.of(2026, 10, 30).atStartOfDay(zone).toInstant().toEpochMilli()
        val day2 = LocalDate.of(2026, 10, 31).atStartOfDay(zone).toInstant().toEpochMilli()
        val list = listOf(ex(1, "open maps", "ok", day1 + 1000), ex(2, "weather", "sunny in Pokhara", day2 + 1000), ex(3, "time", "noon", day2 + 5000))
        val groups = groupByDay(list, zone)
        assertEquals(listOf(LocalDate.of(2026, 10, 31), LocalDate.of(2026, 10, 30)), groups.map { it.first })
        assertEquals(listOf("time", "weather"), groups[0].second.map { it.user })
        assertEquals(listOf("weather"), searchExchanges(list, "POKHARA").map { it.user })
        assertEquals(3, searchExchanges(list, "  ").size)
        assertEquals(0, searchExchanges(list, "zzz").size)
    }

    @Test fun storageRoundTripsAndSurvivesGarbage() {
        val list = listOf(ex(1, "नमस्ते", "hello \"there\"\nnew line"))
        assertEquals(list, decodeExchanges(encodeExchanges(list)))
        assertEquals(emptyList<Exchange>(), decodeExchanges("not json"))
    }

    @Test fun reportShowsOnlyACount() {
        val info = ReportInfo("1.7.4", "d", "a", "m", "model", "v", "…abcd", emptyList(), emptyList(), null, transcriptCount = 42)
        val text = buildReport(info)
        assertTrue(text.contains("Transcript: 42 saved (contents not included)"))
    }

    @Test fun cropObservationIsLabelled() {
        val shot = Screenshot(ByteArray(0), 640, 300, 640, 300)
        assertTrue(Observation(shot, "", cropped = true).cropped)
        assertFalse(Observation(shot, "").cropped)
    }
}
