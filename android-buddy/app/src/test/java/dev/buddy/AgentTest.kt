package dev.buddy

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Payloads below are the examples on the "Computer use tool" docs page (computer_toolset_20260801). */
class AgentJsonTest {
    private fun obj(s: String) = JSONObject(s)
    private fun same(expected: String, actual: JSONObject) = assertTrue("$actual", obj(expected).similar(actual))

    @Test fun toolsEntryHasNoNameOrDisplaySizeAndDisablesPhoneIrrelevantMembers() {
        val toolset = AgentJson.tools().getJSONObject(0)
        assertEquals("computer_toolset_20260801", toolset.getString("type"))
        for (k in listOf("name", "display_width_px", "display_height_px", "display_number", "enable_zoom")) assertFalse(toolset.has(k))
        val configs = toolset.getJSONObject("configs")
        assertEquals(
            setOf("middle_click", "triple_click", "left_mouse_down", "left_mouse_up", "hold_key", "cursor_position"),
            configs.keys().asSequence().toSet(),
        )
        assertFalse(configs.getJSONObject("hold_key").getBoolean("enabled"))
        assertFalse(configs.has("zoom"))   // zoom stays on: we implement it
    }

    @Test fun customToolsAreStrictOnlyWhenTheyHaveProperties() {
        val tools = AgentJson.tools()
        assertEquals(19, tools.length())
        for (i in 1 until tools.length()) {
            val t = tools.getJSONObject(i)
            val schema = t.getJSONObject("input_schema")
            assertFalse(schema.getBoolean("additionalProperties"))
            if (schema.getJSONObject("properties").length() == 0) {
                assertFalse(t.getString("name"), t.has("strict"))
                assertFalse(schema.has("required"))
                assertTrue(JSONObject("""{"type":"object","properties":{},"additionalProperties":false}""").similar(schema))
            } else assertTrue(t.getString("name"), t.getBoolean("strict"))
        }
        val nav = tools.getJSONObject(18).getJSONObject("input_schema").getJSONObject("properties").getJSONObject("action")
        assertEquals(setOf("back", "home", "recents", "notifications", "quick_settings"), (0 until 5).map { nav.getJSONArray("enum").getString(it) }.toSet())
    }

    @Test fun toolsAreDeclaredInOrder() {
        val names = (0 until 19).map { AgentJson.tools().getJSONObject(it).let { t -> t.optString("name").ifEmpty { t.getString("type") } } }
        assertEquals(
            listOf("computer_toolset_20260801", "open_app", "open_url", "open_settings", "list_apps", "set_alarm", "set_timer", "create_event",
                "read_calendar", "read_notifications", "media", "torch", "volume", "device_status", "default_apps", "remember", "save_screenshot", "web_search", "android_nav"),
            names,
        )
        assertEquals(17, AgentJson.SETTINGS_PAGES.size)
    }

    @Test fun actionToolSchemas() {
        fun tool(name: String) = (0 until 17).map { AgentJson.tools().getJSONObject(it) }.first { it.optString("name") == name }
        fun req(name: String) = (0 until tool(name).getJSONObject("input_schema").getJSONArray("required").length())
            .map { tool(name).getJSONObject("input_schema").getJSONArray("required").getString(it) }
        assertEquals(listOf("hour", "minute"), req("set_alarm"))
        assertEquals(listOf("seconds"), req("set_timer"))
        assertEquals(listOf("title", "start_iso"), req("create_event"))
        assertEquals(listOf("action"), req("media"))
        assertEquals(listOf("on"), req("torch"))
        assertTrue(tool("read_calendar").getBoolean("strict"))                       // one optional property
        assertFalse(tool("read_calendar").getJSONObject("input_schema").has("required"))
        assertFalse(tool("read_notifications").has("strict"))                        // no properties
        assertFalse(tool("device_status").has("strict"))
    }

    @Test fun requestIsNonStreamingWithCachingEffortAndTokenBudget() {
        val r = AgentJson.request("claude-haiku-5-5", "system", JSONArray())
        assertEquals(4096, r.getInt("max_tokens"))
        assertEquals("low", r.getJSONObject("output_config").getString("effort"))
        same("""{"type":"ephemeral"}""", r.getJSONObject("cache_control"))
        for (k in listOf("stream", "thinking", "temperature", "top_p", "top_k", "fallbacks")) assertFalse(k, r.has(k))
    }

    @Test fun effortIsLowByDefaultAndMediumWhenAsked() {
        assertEquals("low", AgentJson.request("claude-haiku-5-5", "s", JSONArray()).getJSONObject("output_config").getString("effort"))
        assertEquals("low", AgentJson.request("claude-haiku-5-5", "s", JSONArray(), "low").getJSONObject("output_config").getString("effort"))
        val medium = AgentJson.request("claude-haiku-5-5", "s", JSONArray(), "medium")
        assertEquals("medium", medium.getJSONObject("output_config").getString("effort"))
        // nothing else about the request changes with the effort
        val low = AgentJson.request("claude-haiku-5-5", "s", JSONArray(), "low")
        low.getJSONObject("output_config").put("effort", "medium")
        assertTrue(low.toString() == medium.toString())
    }

    @Test fun fallbacksOnlyForSonnetAndOpus() {
        assertEquals("default", AgentJson.request("claude-sonnet-5-5", "s", JSONArray()).getString("fallbacks"))
        assertEquals("default", AgentJson.request("claude-opus-5-5", "s", JSONArray()).getString("fallbacks"))
        assertFalse(AgentJson.usesFallbacks("claude-haiku-5-5"))
    }

    @Test fun toolUseBlocksParseWithTheirToolset() {
        val content = JSONArray(
            """[
              {"type":"thinking","thinking":"","signature":"EqoBCkgIARABGAIiQ..."},
              {"type":"text","text":"tapping that"},
              {"type":"tool_use","id":"toolu_01WkoTUvSHDzTBu2xnGk8Ep8","name":"left_click","toolset_name":"computer","input":{"coordinate":[512,742]}},
              {"type":"tool_use","id":"toolu_01Qg8m3XqC5aRy7tD2eS4jUg","name":"left_click","toolset_name":"computer","input":{"coordinate":[500,300],"text":"shift"}},
              {"type":"tool_use","id":"toolu_custom1","name":"left_click","input":{"x":1}},
              {"type":"tool_use","id":"toolu_custom2","name":"open_app","input":{"name":"YouTube"}}
            ]""",
        )
        val uses = AgentJson.toolUses(content)
        assertEquals(4, uses.size)
        assertTrue(uses[0].isComputer); assertEquals("left_click", uses[0].name)
        assertEquals(512, uses[0].input.getJSONArray("coordinate").getInt(0))
        assertEquals("shift", uses[1].input.getString("text"))
        assertFalse(uses[2].isComputer)   // a custom tool may share a member's name
        assertEquals("open_app", uses[3].name)
        assertEquals("tapping that", AgentJson.textOf(content))
    }

    @Test fun resultsEchoToolsetNameForMembersOnly() {
        val member = ToolUse("toolu_01HqCF3nJ4Vzr8sTkPZ2wxYA", "left_click", "computer", JSONObject())
        same(
            """{"type":"tool_result","tool_use_id":"toolu_01HqCF3nJ4Vzr8sTkPZ2wxYA","toolset_name":"computer","content":[{"type":"text","text":"OK"}]}""",
            AgentJson.result(member, null, null),
        )
        val custom = ToolUse("toolu_c", "open_app", null, JSONObject())
        assertFalse(AgentJson.result(custom, null, null).has("toolset_name"))
    }

    @Test fun errorAndHaltResultsMatchTheDocs() {
        val use = ToolUse("toolu_01A09q90qw90lq917835lq9", "screenshot", "computer", JSONObject())
        same(
            """{"type":"tool_result","tool_use_id":"toolu_01A09q90qw90lq917835lq9","toolset_name":"computer","content":"Error: Failed to capture screenshot. Display may be locked or unavailable.","is_error":true}""",
            AgentJson.result(use, null, "Error: Failed to capture screenshot. Display may be locked or unavailable."),
        )
        val halted = AgentJson.result(ToolUse("toolu_01Xf5W1sD8Q9aBcJ7kLmN2pQ", "left_click", "computer", JSONObject()), null, AgentJson.HALT)
        same(
            """{"type":"tool_result","tool_use_id":"toolu_01Xf5W1sD8Q9aBcJ7kLmN2pQ","toolset_name":"computer","is_error":true,"content":"Not executed: an earlier computer action in this turn failed."}""",
            halted,
        )
    }

    @Test fun imageResultsCarryAnImageBlock() {
        val use = ToolUse("toolu_01Xf5W1sD8Q9aBcJ7kLmN2pQ", "screenshot", "computer", JSONObject())
        val r = AgentJson.result(use, listOf(JSONObject("""{"type":"image","source":{"type":"base64","media_type":"image/jpeg","data":"iVBORw0KGgo"}}""")), null)
        assertEquals("image", r.getJSONArray("content").getJSONObject(0).getString("type"))
        assertEquals("computer", r.getString("toolset_name"))
    }

    @Test fun cancelledBatchesLeavePendingToolUsesToAnswer() {
        val messages = JSONArray(
            """[{"role":"user","content":[{"type":"text","text":"hi"}]},
                {"role":"assistant","content":[{"type":"tool_use","id":"t1","name":"screenshot","toolset_name":"computer","input":{}}]}]""",
        )
        assertEquals(listOf("t1"), AgentJson.pendingToolUses(messages).map { it.id })
        messages.put(JSONObject("""{"role":"user","content":[{"type":"text","text":"x"}]}"""))
        assertTrue(AgentJson.pendingToolUses(messages).isEmpty())
    }
}

class PhoneMathTest {
    @Test fun claudesCoordinatesMapFromScreenshotToDisplayPixels() {
        // Docs: coordinates are in the pixel space of the screenshots we return (here 576x1280 of a 1080x2400 display).
        val m = ScreenMap(576, 1280, 1080, 2400)
        val p = m.toDisplay(Pt(512f, 742f))
        assertEquals(960f, p.x, 1f)
        assertEquals(1391f, p.y, 1f)
    }

    @Test fun elementListFormat() {
        val text = formatElements(
            listOf(
                UiElement("button", "Display", 540, 410),
                UiElement("switch", "Dark theme", 900, 600, "on"),
                UiElement("image", "", 10, 20),
                UiElement("password field", "", 300, 700),
            ),
        )
        assertEquals(
            "[1] button \"Display\" at (540,410)\n[2] switch \"Dark theme\" (on) at (900,600)\n[3] image at (10,20)\n[4] password field at (300,700)",
            text,
        )
    }

    @Test fun labelsAreTruncatedAndCleaned() {
        val long = "x".repeat(60)
        assertEquals("[1] text \"${"x".repeat(40)}…\" at (1,2)", formatElements(listOf(UiElement("text", long, 1, 2))))
        assertEquals("[1] text \"a b 'c'\" at (1,2)", formatElements(listOf(UiElement("text", "a\n b  \"c\"", 1, 2))))
    }

    @Test fun listIsCappedAtFifty() {
        val lines = formatElements((1..80).map { UiElement("text", "t$it", it, it) }).lines()
        assertEquals(50, lines.size)
        assertTrue(lines.last().startsWith("[50] "))
        assertEquals("(no readable elements)", formatElements(emptyList()))
    }

    @Test fun rolesNeverExposeAPasswordAsText() {
        assertEquals("password field", roleOf("android.widget.EditText", false, true, false, false, true))
        assertEquals("input", roleOf("android.widget.EditText", true, true, false, false, false))
        assertEquals("switch", roleOf("android.widget.Switch", true, false, false, true, false))
        assertEquals("checkbox", roleOf("android.widget.CheckBox", true, false, false, true, false))
    }

    @Test fun scrollIsTheOppositeSwipe() {
        // 1080x2400, scroll down 3 clicks (3 x 15% of the height) from the middle: finger moves up 1080 px.
        assertEquals(listOf(540, 1200, 540, 120), scrollSwipe("down", 3, 540, 1200, 1080, 2400).toList())
        assertEquals(listOf(540, 1200, 540, 2280), scrollSwipe("up", 3, 540, 1200, 1080, 2400).toList())
        val right = scrollSwipe("right", 1, 900, 1200, 1080, 2400)   // 360 px, finger moves left
        assertEquals(listOf(900, 1200, 540, 1200), right.toList())
        val left = scrollSwipe("left", 1, 100, 1200, 1080, 2400)
        assertEquals(1200, left[1]); assertTrue(left[2] > left[0])
    }

    @Test fun scrollDistanceIsClampedAndStaysOnScreen() {
        val tiny = scrollSwipe("down", 0, 540, 1200, 1080, 2400)
        assertEquals(240, tiny[1] - tiny[3])                       // at least 10% of the height
        val huge = scrollSwipe("down", 50, 540, 300, 1080, 2400)   // 80% max, start shifted down to fit
        assertEquals(1920, huge[1] - huge[3])
        assertTrue(huge[3] >= 0 && huge[1] <= 2400)
    }

    @Test fun keys() {
        assertEquals(KeyAction.ENTER, parseKey("Return"))
        assertEquals(KeyAction.BACKSPACE, parseKey("BackSpace"))
        assertEquals(KeyAction.BACK, parseKey("Escape"))
        assertEquals(KeyAction.HOME, parseKey("super"))
        assertEquals(KeyAction.SELECT_ALL, parseKey("ctrl+a"))
        assertNull(parseKey("alt+Tab"))
    }

    @Test fun nearestLabelForStatusLines() {
        val els = listOf(UiElement("button", "Display", 540, 410), UiElement("button", "Sound", 540, 700))
        assertEquals("Display", nearestLabel(els, 530f, 420f))
        assertNull(nearestLabel(els, 100f, 100f))
    }

    @Test fun appNamesMatchFuzzily() {
        val apps = listOf("YouTube", "YouTube Music", "Settings", "Gmail", "Google Maps", "Camera")
        assertEquals("YouTube", matchApps("youtube", apps).best)
        assertEquals("Settings", matchApps("setting", apps).best)
        assertEquals("Google Maps", matchApps("maps", apps).best)
        assertEquals("Gmail", matchApps("gmial", apps).best)
        val none = matchApps("whatsapp", apps)
        assertNull(none.best)
        assertTrue(none.candidates.size <= 3)
    }

    @Test fun actionRequestsAreRecognisedForClaudeAppMode() {
        assertTrue(looksLikeAction("Open YouTube and search lofi"))
        assertTrue(looksLikeAction("hey buddy, turn on dark mode"))
        assertTrue(looksLikeAction("please scroll down"))
        assertFalse(looksLikeAction("how do i turn on dark mode"))
        assertFalse(looksLikeAction("what is this screen"))
    }
}

class PowerToolsTest {
    @Test fun turnCaps() { assertEquals(25, turnCap("claude-haiku-5-5")); assertEquals(40, turnCap("claude-sonnet-5-5")); assertEquals(40, turnCap("claude-opus-5-5")) }

    @Test fun linksNeverCallOrSend() {
        assertEquals("VIEW", urlAction("https://example.com/x"))
        assertEquals("VIEW", urlAction("geo:37.7749,-122.4194"))
        assertEquals("SENDTO", urlAction("mailto:a@b.com?subject=hi"))
        assertEquals("SENDTO", urlAction("sms:+15551234567?body=hi"))
        assertEquals("DIAL", urlAction("tel:+15551234567"))
        assertNull(urlAction("javascript:alert(1)"))
        assertNull(urlAction("intent://scan/#Intent;end"))
        assertNull(urlAction("file:///sdcard/x"))
    }

    @Test fun everySettingsPageMapsToAnIntentAction() {
        for (p in AgentJson.SETTINGS_PAGES) assertTrue(p, settingsAction(p).startsWith("android.settings."))
        assertEquals("android.settings.WIFI_SETTINGS", settingsAction("wifi"))
        assertEquals("android.settings.APPLICATION_DETAILS_SETTINGS", settingsAction("app_details"))
        assertEquals(MAIN_SETTINGS_ACTION, settingsAction("nonsense"))
        assertEquals(17, AgentJson.SETTINGS_PAGES.map(::settingsAction).distinct().size)
    }
}
