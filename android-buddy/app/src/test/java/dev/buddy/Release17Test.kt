package dev.buddy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

private val UTC = ZoneId.of("UTC")
private fun at(h: Int, m: Int = 0, day: Int = 3) = ZonedDateTime.of(2025, 11, day, h, m, 0, 0, UTC).toInstant().toEpochMilli()
private val NOW = ZonedDateTime.of(2025, 11, 3, 8, 15, 0, 0, UTC)   // a Monday

class BriefingTest {
    private val events = listOf(
        CalEvent("Standup", at(9), at(9, 30), null, false),
        CalEvent("Lunch with Sam", at(12, 30), at(13, 30), "Cafe", false),
        CalEvent("Dentist", at(16), at(17), null, false),
        CalEvent("Gym", at(18), at(19), null, false),
    )
    private val notifs = listOf(
        Notif("1", "WhatsApp", "Sam", "are we still on for lunch?", at(7, 50)),
        Notif("2", "Gmail", "Boss", "Report due", at(8, 0)),
        Notif("3", "WhatsApp", "Mum", "call me", at(8, 10)),
    )

    @Test fun everythingAvailable() {
        val text = composeBriefing(NOW, 80, false, events, notifs)
        assertEquals(
            "it's 8:15 am on monday. you have 4 coming up: standup at 9 am, then lunch with sam at 12:30 pm, then dentist at 4 pm. " +
                "3 notifications, mostly whatsapp. latest: whatsapp, mum, call me.",
            text,
        )
    }

    @Test fun missingSourcesAreSkippedSilently() {
        assertEquals("it's 8:15 am on monday.", composeBriefing(NOW, 80, false, null, null))
        val calendarOnly = composeBriefing(NOW, 80, false, events.take(1), null)
        assertEquals("it's 8:15 am on monday. next up: standup at 9 am.", calendarOnly)
        assertFalse(calendarOnly.contains("notification"))
        assertFalse(composeBriefing(NOW, 80, false, null, notifs).contains("coming up"))
    }

    @Test fun emptyButAllowedSourcesSaySo() {
        assertEquals("it's 8:15 am on monday. nothing coming up on your calendar. no new notifications.", composeBriefing(NOW, 80, false, emptyList(), emptyList()))
    }

    @Test fun lowBatteryIsMentionedOnlyWhenNotCharging() {
        assertTrue(composeBriefing(NOW, 14, false, null, null).endsWith("battery is at 14 percent."))
        assertFalse(composeBriefing(NOW, 14, true, null, null).contains("battery"))
        assertFalse(composeBriefing(NOW, 55, false, null, null).contains("battery"))
    }

    @Test fun oneNotificationIsSingular() {
        assertTrue(composeBriefing(NOW, null, false, null, notifs.take(1)).contains("1 notification. latest: whatsapp, sam, are we still on for lunch?."))
    }
}

class CapabilityFormatTest {
    @Test fun spokenTimes() {
        assertEquals("9 am", spokenTime(at(9), UTC))
        assertEquals("12:30 pm", spokenTime(at(12, 30), UTC))
        assertEquals("12 am", spokenTime(at(0), UTC))
        assertEquals("4:05 pm", spokenTime(at(16, 5), UTC))
    }

    @Test fun calendarLines() {
        val text = formatEvents(
            listOf(CalEvent("Standup", at(9), at(10), "Room 4", false), CalEvent("Trip", at(0, 0, 4), at(0, 0, 5), null, true)),
            UTC, at(8),
        )
        assertEquals("Standup, today 9 am, Room 4\nTrip, tomorrow, all day", text)
        assertEquals("no upcoming events", formatEvents(emptyList(), UTC, 0))
    }

    @Test fun notificationLines() {
        assertEquals("7:50 am WhatsApp, Sam: hi there", formatNotifications(listOf(Notif("k", "WhatsApp", "Sam", "hi there", at(7, 50))), UTC))
        assertEquals("no notifications", formatNotifications(emptyList(), UTC))
    }

    @Test fun notificationStoreKeepsThe30NewestInMemory() {
        NotificationStore.clear()
        repeat(35) { NotificationStore.add(Notif("k$it", "app", "t$it", "", it.toLong())) }
        assertEquals(30, NotificationStore.entries().size)
        assertEquals("t5", NotificationStore.entries().first().title)
        NotificationStore.add(Notif("k34", "app", "updated", "", 99))   // same key replaces
        assertEquals(30, NotificationStore.entries().size)
        assertEquals("updated", NotificationStore.entries().last().title)
        NotificationStore.remove("k34")
        assertEquals(29, NotificationStore.entries().size)
        NotificationStore.clear()
    }

    @Test fun deviceStatus() {
        val text = deviceStatusText(80, true, NOW, true, "vibrate")
        assertEquals("time: 8:15 am, monday 2025-11-03\nbattery: 80%, charging\nwifi: on\nringer: vibrate", text)
        assertEquals("time: 8:15 am, monday 2025-11-03\nringer: normal", deviceStatusText(null, false, NOW, null, "normal"))
    }

    @Test fun isoTimes() {
        assertEquals(at(15), parseIso("2025-11-03T15:00", UTC))
        assertEquals(at(15), parseIso("2025-11-03T15:00:00Z", UTC))
        assertEquals(at(15), parseIso("2025-11-03T16:00:00+01:00", UTC))
        assertEquals(at(9), parseIso("2025-11-03", UTC))
        assertNull(parseIso("tomorrow at 3", UTC))
        assertNull(parseIso("", UTC))
    }

    @Test fun lookingToolsAreNotActions() {
        for (n in listOf("screenshot", "zoom", "mouse_move", "list_apps", "read_calendar", "read_notifications", "device_status")) assertFalse(n, isActionTool(n))
        for (n in listOf("left_click", "type", "set_alarm", "open_app", "media", "torch", "web_search", "create_event")) assertTrue(n, isActionTool(n))
    }
}

class ConversationModeTest {
    @Test fun followUpOnlyAfterSpokenAnswersNotActionConfirmations() {
        assertTrue(shouldOpenFollowUp(true, true, false, "it's about 5 km away."))
        assertFalse(shouldOpenFollowUp(true, true, true, "done"))
        assertFalse(shouldOpenFollowUp(false, true, false, "it's about 5 km away."))   // master toggle
        assertFalse(shouldOpenFollowUp(true, false, false, "x"))
        assertFalse(shouldOpenFollowUp(true, true, false, "  "))
    }

    @Test fun aQuestionAfterAnActionStillOpensTheWindow() {
        assertTrue(shouldOpenFollowUp(true, true, true, "send it to sam?"))
    }

    @Test fun windowStates() {
        val w = FollowUp()
        assertEquals(FollowUpState.CLOSED, w.state)
        w.open(); assertEquals(FollowUpState.WAITING, w.state); assertTrue(w.isOpen)
        assertTrue(w.timeout()); assertEquals(FollowUpState.CLOSED, w.state)    // silence closes it
        w.open(); w.speechStarted(); assertEquals(FollowUpState.HEARING, w.state)
        assertFalse(w.timeout()); assertEquals(FollowUpState.HEARING, w.state)  // mid-sentence: the time limit doesn't cut in
        w.close(); assertEquals(FollowUpState.CLOSED, w.state)                  // a tap or the end of the sentence
        w.speechStarted(); assertEquals(FollowUpState.CLOSED, w.state)          // speech without an open window does nothing
    }
}

class RouterAdditionsTest {
    private val none = { emptyList<String>() }
    private fun route(t: String) = routeLocal(t, none)

    @Test fun alarms() {
        assertEquals(LocalCommand.Alarm(7, 0), route("set an alarm for 7 am"))
        assertEquals(LocalCommand.Alarm(18, 30), route("Set alarm for 6:30 pm"))
        assertEquals(LocalCommand.Alarm(0, 5), route("wake me up at 12 05 am"))
        assertEquals(LocalCommand.Alarm(12, 0), route("alarm at 12 pm"))
        assertEquals(LocalCommand.Alarm(15, 45), route("alarm for 15 45"))
        assertNull(route("set an alarm for 7"))          // am or pm? Claude can ask
        assertNull(route("set an alarm for 25 00"))
        assertNull(route("set an alarm tomorrow"))
    }

    @Test fun timers() {
        assertEquals(LocalCommand.Timer(300), route("set a timer for 5 minutes"))
        assertEquals(LocalCommand.Timer(600), route("timer 10 min"))
        assertEquals(LocalCommand.Timer(120), route("2 minute timer"))
        assertEquals(LocalCommand.Timer(30), route("start a 30 second timer"))
        assertEquals(LocalCommand.Timer(5400), route("set a timer for 1 hour 30 minutes"))
        assertNull(route("set a timer"))
        assertNull(route("what is a timer"))
    }

    @Test fun media() {
        assertEquals(LocalCommand.Media("pause"), route("pause"))
        assertEquals(LocalCommand.Media("pause"), route("please pause the music"))
        assertEquals(LocalCommand.Media("play"), route("resume"))
        assertEquals(LocalCommand.Media("next"), route("next song"))
        assertEquals(LocalCommand.Media("next"), route("skip"))
        assertEquals(LocalCommand.Media("previous"), route("previous track"))
        assertNull(route("play lofi on youtube"))        // not a bare media command
    }

    @Test fun briefing() {
        for (p in listOf("what's my day", "Whats my day", "good morning", "what did I miss", "briefing", "give me my daily briefing", "brief me")) {
            assertEquals(p, LocalCommand.Briefing, route(p))
        }
        assertNull(route("good morning to you my friend"))
    }

    @Test fun helpers() {
        assertEquals("7:30 am", clockText(7, 30)); assertEquals("12:05 pm", clockText(12, 5)); assertEquals("12:00 am", clockText(0, 0))
        assertEquals("5 minutes", durationText(300)); assertEquals("1 hour 30 minutes", durationText(5400)); assertEquals("45 seconds", durationText(45))
        assertEquals(90, parseDuration("a 1 min 30 sec timer"))
        assertNull(parseDuration("timer for a while"))
    }
}

class HintTest {
    @Test fun hintShowsOnlyTheFirstThreeTimes() {
        assertTrue(shouldShowHint(0)); assertTrue(shouldShowHint(1)); assertTrue(shouldShowHint(2))
        assertFalse(shouldShowHint(3)); assertFalse(shouldShowHint(10))
        assertEquals("hold to talk · drag down to ✕ to close", BUBBLE_HINT)
    }
}

class DesignMathTest {
    @Test fun pagesCrossFadeAsTheySwipe() {
        assertEquals(1f, pageFade(0f), 0.001f)
        assertEquals(0.5f, pageFade(0.5f), 0.001f)
        assertEquals(0.5f, pageFade(-0.5f), 0.001f)
        assertEquals(0f, pageFade(1f), 0.001f)
        assertEquals(0f, pageFade(2.5f), 0.001f)
    }

    @Test fun smallTitleFadesInWhileScrolling() {
        assertEquals(0f, titleAlpha(0), 0.001f)
        assertEquals(0f, titleAlpha(40), 0.001f)
        assertEquals(0.5f, titleAlpha(80), 0.001f)
        assertEquals(1f, titleAlpha(500), 0.001f)
    }

    @Test fun segmentedIndexAndKeyStatus() {
        assertEquals(1, segmentIndex(listOf("a", "b", "c"), "b"))
        assertEquals(0, segmentIndex(listOf("a", "b"), "zzz"))
        assertEquals("Not set", keyStatus(""))
        assertEquals("Saved: …cdef", keyStatus("sk-ant-api03-abcdef"))
        assertFalse(keyStatus("sk-ant-api03-abcdef").contains("api03"))
    }
}
