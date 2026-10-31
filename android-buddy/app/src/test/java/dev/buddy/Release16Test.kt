package dev.buddy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TimingLogTest {
    private fun e(step: String, ms: Long, detail: String = "") = TimingEntry(1_700_000_000_000, step, ms, detail)

    @Test fun summaryOfTheLastFinishedRequest() {
        val log = listOf(
            e("screen", 400), e("claude", 2000, "9 KB, HTTP 200"), e("total", 3000),            // an earlier request
            e("screen", 600), e("voice", 1500), e("claude", 3000), e("action", 200, "left_click"), e("claude", 1100), e("total", 6200),
        )
        assertEquals("Last answer: 6.2 s (Claude 4.1 s, voice 1.5 s, screen 0.6 s)", summarize(log))
    }

    @Test fun noFinishedRequestMeansNoSummary() {
        assertNull(summarize(emptyList()))
        assertNull(summarize(listOf(e("claude", 100))))
        assertEquals("Last answer: 0.2 s", summarize(listOf(e("total", 200))))
    }

    @Test fun entriesFormatOnePerLine() {
        val text = formatEntries(listOf(e("claude", 4123, "38 KB, HTTP 200"), e("settle", 250)))
        val lines = text.lines()
        assertEquals(2, lines.size)
        assertTrue(lines[0].contains("claude 4123 ms (38 KB, HTTP 200)"))
        assertTrue(lines[1].endsWith("settle 250 ms"))
    }

    @Test fun ringBufferKeepsTheLast30() {
        repeat(40) { TimingLog.record("action", it.toLong(), "n$it") }
        val kept = TimingLog.entries()
        assertEquals(30, kept.size)
        assertEquals("n39", kept.last().detail)
        assertEquals("n10", kept.first().detail)
    }
}

class ReportTest {
    private val key = "sk-ant-api03-SECRETSECRETSECRET-ab12"

    private fun info(error: String? = null) = ReportInfo(
        version = "1.6", device = "Google Pixel 8", android = "Android 15 (SDK 35)", mode = "CLAUDE", model = "claude-haiku-5-5",
        voice = "PHONE", keyMasked = maskKey(key),
        checks = listOf(CheckResult("Mode", true, "Claude API"), CheckResult("Accessibility", false, "not connected", 12)),
        timing = listOf(TimingEntry(1_700_000_000_000, "claude", 4100, "9 KB, HTTP 200")), lastError = error, secretToScrub = key,
    )

    @Test fun keyIsMaskedToItsLastFourCharacters() {
        assertEquals("…ab12", maskKey(key))
        assertEquals("(none)", maskKey(""))
    }

    @Test fun reportNeverContainsTheKey() {
        val text = buildReport(info(error = "API error 401: invalid x-api-key $key"))
        assertFalse(text.contains(key))
        assertFalse(text.contains("SECRETSECRET"))
        assertTrue(text.contains("…ab12"))
    }

    @Test fun reportHasTheDiagnosticFields() {
        val text = buildReport(info(error = "no internet"))
        for (needle in listOf("App version: 1.6", "Device: Google Pixel 8", "Android 15 (SDK 35)", "Mode: CLAUDE", "Model: claude-haiku-5-5",
            "Voice engine: PHONE", "OK   Mode: Claude API", "FAIL Accessibility: not connected [12 ms]", "Last error: no internet", "claude 4100 ms")) {
            assertTrue(needle, text.contains(needle))
        }
    }
}

class FollowUpTest {
    @Test fun quickFollowUpKeepsContext() {
        assertTrue(keepContext(lastAt = 10_000, now = 60_000, messageCount = 6))
        assertFalse(keepContext(lastAt = 10_000, now = 100_001, messageCount = 6))   // 90 s or more
        assertFalse(keepContext(lastAt = 0, now = 5_000, messageCount = 0))         // nothing before it
        assertFalse(keepContext(lastAt = 10_000, now = 20_000, messageCount = MAX_MESSAGES))
        assertEquals(24, MAX_MESSAGES)
    }

    @Test fun accessibilityProblemsAreTold() {
        assertNull(a11yProblem(enabled = true, connected = true))
        assertEquals("Claude can't control the screen yet: turn on Buddy in Accessibility", a11yProblem(false, false))
        assertTrue(a11yProblem(true, false)!!.contains("turn Buddy off and on again"))
    }
}

class UpdateTest {
    @Test fun parsesVersionJson() {
        val u = parseUpdate("""{"versionCode": 12, "versionName": "1.7", "apk": "https://example.com/buddy.apk", "notes": "faster"}""")!!
        assertEquals(12L, u.versionCode); assertEquals("1.7", u.versionName); assertEquals("faster", u.notes)
        assertNull(parseUpdate("not json"))
        assertNull(parseUpdate("""{"versionName": "1.7"}"""))
    }

    @Test fun onlyNewerVersionsOffered() {
        assertTrue(isNewer(12, 11)); assertFalse(isNewer(11, 11)); assertFalse(isNewer(10, 11))
    }
}

class LocalRouterTest {
    private val apps = listOf("YouTube", "YouTube Music", "Gmail", "Google Maps", "Settings", "Camera", "WhatsApp")
    private fun route(t: String) = routeLocal(t) { apps }

    @Test fun navigation() {
        assertEquals(LocalCommand.Nav("home"), route("go home"))
        assertEquals(LocalCommand.Nav("home"), route("Please go to the home screen"))
        assertEquals(LocalCommand.Nav("back"), route("go back"))
        assertEquals(LocalCommand.Nav("recents"), route("recent apps"))
        assertEquals(LocalCommand.Nav("notifications"), route("open notifications"))
        assertEquals(LocalCommand.Nav("quick_settings"), route("can you open quick settings please"))
    }

    @Test fun torchAndVolume() {
        assertEquals(LocalCommand.Torch(true), route("flashlight on"))
        assertEquals(LocalCommand.Torch(false), route("turn off the torch"))
        assertEquals(LocalCommand.Torch(true), route("turn the flashlight on"))
        assertNull(route("flashlight"))
        assertEquals(LocalCommand.Volume("up"), route("volume up"))
        assertEquals(LocalCommand.Volume("down"), route("turn the volume down"))
        assertEquals(LocalCommand.Volume("mute"), route("mute"))
        assertEquals(LocalCommand.Volume("max"), route("max volume"))
        assertEquals(LocalCommand.Volume("up"), route("Louder!"))
    }

    @Test fun settingsPages() {
        assertEquals(LocalCommand.OpenSettings("wifi"), route("open wifi settings"))
        assertEquals(LocalCommand.OpenSettings("wifi"), route("open wi-fi settings"))
        assertEquals(LocalCommand.OpenSettings("battery"), route("open the battery settings"))
        assertNull(route("open nfc settings"))   // not a local phrase: Claude can do it with open_settings
    }

    @Test fun openAppOnlyOnAConfidentMatch() {
        assertEquals(LocalCommand.OpenApp("YouTube"), route("open youtube"))
        assertEquals(LocalCommand.OpenApp("Gmail"), route("Hey buddy, please launch gmail"))
        assertEquals(LocalCommand.OpenApp("Camera"), route("start the camera app"))
        assertEquals(LocalCommand.OpenApp("WhatsApp"), route("open whatsap"))     // similarity >= 0.85
        assertEquals(LocalCommand.OpenApp("Google Maps"), route("open google m"))  // prefix
        assertNull(route("open spotify"))                                          // not installed: Claude explains
        assertNull(route("open ca"))                                               // too short to trust
    }

    @Test fun everythingElseGoesToClaude() {
        assertNull(route("open youtube and search lofi"))
        assertNull(route("what is on my screen"))
        assertNull(route("turn on dark mode"))
        assertNull(route(""))
    }

    @Test fun fuzzyThreshold() {
        assertEquals("Gmail", confidentApp("gmail", apps))
        assertNull(confidentApp("gmial", listOf("Gmail", "Calendar")) .takeIf { false })   // transposition: 2 edits of 5 = 0.6 < 0.85
        assertNull(confidentApp("gmial", listOf("Gmail", "Calendar")))
        assertEquals("WhatsApp", confidentApp("whatsap", apps))
    }
}
