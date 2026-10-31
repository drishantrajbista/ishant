package dev.buddy

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.Instant

/** Realistic Google 429 bodies (RESOURCE_EXHAUSTED with QuotaFailure + RetryInfo details). */
private object Bodies {
    private fun body(message: String, quotaId: String, quotaValue: String, retryDelay: String) = """
        {
          "error": {
            "code": 429,
            "message": "$message",
            "status": "RESOURCE_EXHAUSTED",
            "details": [
              {
                "@type": "type.googleapis.com/google.rpc.Help",
                "links": [{"description": "Learn more about Gemini API quotas", "url": "https://ai.google.dev/gemini-api/docs/rate-limits"}]
              },
              {
                "@type": "type.googleapis.com/google.rpc.QuotaFailure",
                "violations": [
                  {
                    "quotaMetric": "generativelanguage.googleapis.com/generate_content_free_tier_requests",
                    "quotaId": "$quotaId",
                    "quotaDimensions": {"location": "global", "model": "gemini-2.5-flash"},
                    "quotaValue": "$quotaValue"
                  }
                ]
              },
              {"@type": "type.googleapis.com/google.rpc.RetryInfo", "retryDelay": "$retryDelay"}
            ]
          }
        }
    """.trimIndent()

    private const val INTRO = "You exceeded your current quota, please check your plan and billing details. For more " +
        "information on this error, head to: https://ai.google.dev/gemini-api/docs/rate-limits. \\n* Quota exceeded for " +
        "metric: generativelanguage.googleapis.com/generate_content_free_tier_requests"

    val perMinute = body("$INTRO, limit: 5, model: gemini-2.5-flash\\nPlease retry in 13.289s.",
        "GenerateRequestsPerMinutePerProjectPerModel-FreeTier", "5", "13s")
    val perMinuteSlow = body("$INTRO, limit: 5, model: gemini-2.5-flash\\nPlease retry in 41.5s.",
        "GenerateRequestsPerMinutePerProjectPerModel-FreeTier", "5", "41s")
    val perDay = body("$INTRO, limit: 20, model: gemini-2.5-flash\\nPlease retry in 35.126s.",
        "GenerateRequestsPerDayPerProjectPerModel-FreeTier", "20", "35s")
    val limitZero = body("$INTRO, limit: 0, model: gemini-2.5-pro\\nPlease retry in 2.1s.",
        "GenerateRequestsPerDayPerProjectPerModel-FreeTier", "0", "2s")
    val tokensPerMinute = body("$INTRO, limit: 250000, model: gemini-2.5-flash\\nPlease retry in 9.8s.",
        "GenerateContentInputTokensPerModelPerMinute-FreeTier", "250000", "9s")
    const val bare = """{"error": {"code": 429, "message": "Resource exhausted. Please try again later.", "status": "RESOURCE_EXHAUSTED"}}"""
}

class QuotaParsingTest {
    @Test fun perMinuteQuotaWithShortRetryDelay() {
        val q = parseQuotaError(Bodies.perMinute)
        assertTrue(q.perMinute); assertFalse(q.daily)
        assertEquals(13.0, q.retryAfterSec!!, 0.001)
    }

    @Test fun perDayQuotaIsDailyEvenWithAShortRetryDelay() {
        val q = parseQuotaError(Bodies.perDay)
        assertTrue(q.daily); assertFalse(q.perMinute)
        assertEquals(35.0, q.retryAfterSec!!, 0.001)
    }

    @Test fun limitZeroMeansNoFreeQuotaAtAll() {
        val q = parseQuotaError(Bodies.limitZero)
        assertTrue(q.daily)
    }

    @Test fun tokensPerMinuteCountsAsPerMinute() {
        val q = parseQuotaError(Bodies.tokensPerMinute)
        assertTrue(q.perMinute); assertFalse(q.daily)
        assertEquals(9.0, q.retryAfterSec!!, 0.001)
    }

    @Test fun unknownBodiesAreNeitherMinuteNorDaily() {
        val q = parseQuotaError(Bodies.bare)
        assertFalse(q.perMinute); assertFalse(q.daily); assertNull(q.retryAfterSec)
        val junk = parseQuotaError("<html>429</html>")
        assertFalse(junk.perMinute); assertFalse(junk.daily); assertNull(junk.retryAfterSec)
    }

    @Test fun retryDelayFallsBackToTheMessage() {
        val q = parseQuotaError("""{"error": {"code": 429, "message": "Quota exceeded. Please retry in 7.5s."}}""")
        assertEquals(7.5, q.retryAfterSec!!, 0.001)
    }
}

class ModelOrderTest {
    private val listed = listOf(
        "gemini-2.0-flash", "gemini-2.0-flash-lite", "gemini-2.5-flash", "gemini-2.5-flash-lite",
        "gemini-2.5-pro", "gemini-3-flash-preview", "gemini-3.1-flash-lite-preview", "gemini-flash-latest",
        "gemini-flash-lite-latest", "gemini-2.5-flash-image", "gemini-2.5-flash-preview-tts", "gemini-2.0-flash-live-001",
    )

    @Test fun userModelThenLiteAliasThenLiteModelsNewestFirstThenFlash() {
        val order = fallbackOrder(listOf("gemini-2.5-flash", LITE_LATEST), listed)
        assertEquals(
            listOf(
                "gemini-2.5-flash", "gemini-flash-lite-latest",
                "gemini-3.1-flash-lite-preview", "gemini-2.5-flash-lite", "gemini-2.0-flash-lite",
                "gemini-flash-latest", "gemini-3-flash-preview", "gemini-2.0-flash",
            ),
            order,
        )
    }

    @Test fun skipsExhaustedAndNeverRepeatsOrPicksImageTtsOrLiveModels() {
        val order = fallbackOrder(listOf("gemini-2.5-flash", LITE_LATEST), listed, skip = setOf("gemini-2.5-flash", "gemini-3.1-flash-lite-preview"))
        assertFalse("gemini-2.5-flash" in order)
        assertFalse("gemini-3.1-flash-lite-preview" in order)
        assertEquals(order.distinct(), order)
        assertTrue(order.none { "image" in it || "tts" in it || "live" in it || "pro" in it })
    }

    @Test fun stableBeatsPreviewAtTheSameVersion() {
        assertEquals(
            listOf("gemini-2.5-flash-lite", "gemini-2.5-flash-lite-preview-09-2025"),
            newestFirst(listOf("gemini-2.5-flash-lite-preview-09-2025", "gemini-2.5-flash-lite")),
        )
    }

    @Test fun pickerShowsLiteModelsFirst() {
        val picker = pickerOrder(listed.filter { "image" !in it })
        assertEquals("gemini-flash-lite-latest", picker.first())
        val lastLite = picker.indexOfLast { "flash-lite" in it }
        val firstOther = picker.indexOfFirst { "flash-lite" !in it }
        assertTrue(lastLite < firstOther)
    }

    @Test fun quotasResetAtMidnightPacific() {
        // PDT (UTC-7) until 2026-11-01, PST (UTC-8) after.
        assertEquals("2026-10-29", pacificDay(Instant.parse("2026-10-30T06:59:00Z")))
        assertEquals("2026-10-30", pacificDay(Instant.parse("2026-10-30T07:01:00Z")))
        assertEquals("2026-11-30", pacificDay(Instant.parse("2026-12-01T07:59:00Z")))
        assertEquals("2026-12-01", pacificDay(Instant.parse("2026-12-01T08:00:00Z")))
    }
}

private class FakeStore : QuotaStore {
    val exhaustedByDay = mutableMapOf<String, MutableSet<String>>()
    val notified = mutableMapOf<String, String>()
    val variants = mutableMapOf<String, Int>()
    override fun exhausted(day: String): Set<String> = exhaustedByDay[day].orEmpty()
    override fun markExhausted(day: String, model: String) { exhaustedByDay.getOrPut(day) { mutableSetOf() } += model }
    override fun lastNotified(day: String) = notified[day]
    override fun setNotified(day: String, model: String) { notified[day] = model }
    override fun thinkingVariant(model: String) = variants[model]
    override fun setThinkingVariant(model: String, variant: Int) { variants[model] = variant }
}

class ModelFallbackTest {
    private val flash = "gemini-2.5-flash"
    private val day = "2026-10-30"
    private val store = FakeStore()
    private val notices = mutableListOf<String?>()
    private val sleeps = mutableListOf<Long>()
    private var listCalls = 0
    private var listed = listOf("gemini-2.5-flash-lite", "gemini-2.0-flash-lite", "gemini-2.5-flash", "gemini-2.0-flash")
    private val attempts = mutableListOf<Pair<String, String?>>()

    private fun fallback(today: String = day) =
        ModelFallback(store, { listCalls++; listed }, { notices += it }, { today }, { sleeps += it })

    private fun tooMany(body: String) = ApiException(429, "API error 429", body)

    private fun run(first: List<String> = listOf(flash, LITE_LATEST), today: String = day, behave: (String, String?) -> String): String =
        runBlocking {
            fallback(today).run(first) { model, thinking ->
                attempts += model to thinking
                behave(model, thinking)
            }
        }

    @Test fun dailyExhaustionFallsBackToLiteAndIsRememberedForTheDay() {
        val result = run { m, _ -> if (m == flash) throw tooMany(Bodies.perDay) else "ok:$m" }
        assertEquals("ok:$LITE_LATEST", result)
        assertEquals(listOf(flash, LITE_LATEST), attempts.map { it.first }.distinct())
        assertEquals(setOf(flash), store.exhausted(day))
        assertEquals(listOf<String?>("switched to $LITE_LATEST (free limit on $flash used up)"), notices)

        attempts.clear()
        assertEquals("ok:$LITE_LATEST", run { m, _ -> if (m == flash) throw tooMany(Bodies.perDay) else "ok:$m" })
        assertEquals(listOf(LITE_LATEST), attempts.map { it.first }.distinct())   // the exhausted model isn't retried
        assertEquals(1, notices.size)                                              // and the switch is announced once
        assertEquals(0, listCalls)
    }

    @Test fun shortPerMinuteLimitIsWaitedOutOnceThenRetried() {
        var calls = 0
        val result = run { m, _ -> if (m == flash && calls++ == 0) throw tooMany(Bodies.perMinute) else "ok:$m" }
        assertEquals("ok:$flash", result)
        assertEquals(listOf(13_300L), sleeps)
        assertEquals(listOf<String?>("busy, retrying in 13s", null), notices)
        assertTrue(store.exhausted(day).isEmpty())
    }

    @Test fun longPerMinuteDelayMovesOnInsteadOfWaiting() {
        val result = run { m, _ -> if (m == flash) throw tooMany(Bodies.perMinuteSlow) else "ok:$m" }
        assertEquals("ok:$LITE_LATEST", result)
        assertTrue(sleeps.isEmpty())
        assertTrue(store.exhausted(day).isEmpty())   // per-minute isn't daily exhaustion
    }

    @Test fun aPerMinuteLimitIsOnlyRetriedOnce() {
        val result = run { m, _ -> if (m == flash) throw tooMany(Bodies.perMinute) else "ok:$m" }
        assertEquals("ok:$LITE_LATEST", result)
        assertEquals(1, sleeps.size)
        assertEquals(2, attempts.count { it.first == flash && it.second == ModelFallback.THINKING[0] })
    }

    @Test fun everyModelExhaustedGivesTheSpokenDailyLimitMessage() {
        try {
            run { _, _ -> throw tooMany(Bodies.perDay) }
            fail("expected GeminiExhaustedException")
        } catch (e: GeminiExhaustedException) {
            assertTrue(e.message!!.startsWith("gemini's free daily limit is used up. it resets at midnight pacific time."))
            assertTrue(e.message!!.endsWith("switch voice to phone and use claude app mode."))
        }
        assertEquals(1, listCalls)   // the key's other models were looked up once the known ones failed
        val tried = attempts.map { it.first }.distinct()
        assertEquals(listOf(flash, LITE_LATEST, "gemini-2.5-flash-lite", "gemini-2.0-flash-lite", "gemini-2.0-flash"), tried)
        assertEquals(tried.toSet(), store.exhausted(day))

        attempts.clear()
        try {
            run { _, _ -> throw tooMany(Bodies.perDay) }
            fail("expected GeminiExhaustedException")
        } catch (e: GeminiExhaustedException) {
            assertTrue(attempts.isEmpty())   // nothing left to try today, so no wasted requests
        }
    }

    @Test fun anUnsupportedThinkingConfigIsNeverAReasonToChangeModel() {
        val result = run { m, thinking -> if (thinking != null) throw ApiException(400, "API error 400: thinking not supported") else "ok:$m" }
        assertEquals("ok:$flash", result)
        assertEquals(listOf(flash, flash, flash), attempts.map { it.first })
        assertEquals(listOf(ModelFallback.THINKING[0], ModelFallback.THINKING[1], null), attempts.map { it.second })
        assertEquals(2, store.variants[flash])
        assertTrue(store.exhausted(day).isEmpty())
        assertEquals(0, notices.size)

        attempts.clear()   // the working variant is remembered, so the retry chain never runs again for this model
        run { m, thinking -> if (thinking != null) throw ApiException(400, "no") else "ok:$m" }
        assertEquals(1, attempts.size)
    }

    @Test fun aRejectedKeyFailsWithoutTryingOtherModels() {
        try {
            run { _, _ -> throw ApiException(400, "API error 400: API key not valid") }
            fail("expected ApiException")
        } catch (e: ApiException) {
            assertEquals(400, e.code)
        }
        assertEquals(setOf(flash), attempts.map { it.first }.toSet())
        assertTrue(store.exhausted(day).isEmpty())
    }

    @Test fun otherErrorsPropagateUntouched() {
        try {
            run { _, _ -> throw ApiException(403, "API error 403: permission denied") }
            fail("expected ApiException")
        } catch (e: ApiException) {
            assertEquals("API error 403: permission denied", e.message)
        }
        assertEquals(1, attempts.size)
    }

    @Test fun aMissingModelIsSkippedForTheDay() {
        val result = run { m, _ -> if (m == flash) throw ApiException(404, "API error 404: model not found") else "ok:$m" }
        assertEquals("ok:$LITE_LATEST", result)
        assertEquals(setOf(flash), store.exhausted(day))
        assertEquals("switched to $LITE_LATEST ($flash isn't available)", notices.single())
    }

    @Test fun exhaustionIsForgottenOnTheNextPacificDay() {
        store.markExhausted("2026-10-29", flash)
        run(today = day) { m, _ -> "ok:$m" }
        assertEquals(flash, attempts.first().first)
    }

    @Test fun transcriptionStartsOnTheLiteAliasWhateverTheAnswerModelIs() {
        run(first = listOf(LITE_LATEST)) { m, _ -> "ok:$m" }
        assertEquals(LITE_LATEST, attempts.single().first)
        assertNotNull(notices.none { it != null })
    }

    @Test fun nothingIsListedUnlessTheKnownModelsFail() {
        run { m, _ -> "ok:$m" }
        assertEquals(0, listCalls)
    }
}
