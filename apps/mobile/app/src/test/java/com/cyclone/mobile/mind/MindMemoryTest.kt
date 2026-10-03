package com.cyclone.mobile.mind

import com.cyclone.mobile.UiBounds
import com.cyclone.mobile.UiNodeSnapshot
import com.cyclone.mobile.ui.overlay.ClickGateIntercept
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class MindMemoryTest {
    private fun memory(clock: () -> Long = { 1_000 }) = MindMemory(Files.createTempFile("mind", ".json").toFile(), clock, limit = 3)

    @Test fun storesDeduplicatesAndPersists() {
        val file = Files.createTempFile("mind", ".json").toFile()
        val a = MindMemory(file)
        assertTrue(a.remember("Owner prefers Dutch replies") is MindMemory.Saved.Stored)
        assertTrue(a.remember("owner prefers dutch replies") is MindMemory.Saved.Updated)
        assertEquals(1, MindMemory(file).all().size)
    }

    @Test fun refusesSecrets() {
        val m = memory()
        listOf("wachtwoord is Zomer2024", "card 4111 1111 1111 1111", "the verification code is 123456",
            "api key sk-or-v1-abcdefghijk", "IBAN NL91ABNA0417164300").forEach {
            assertTrue(it, m.remember(it) is MindMemory.Saved.Refused)
        }
        assertTrue(m.all().isEmpty())
    }

    @Test fun keepsTheNewestWithinTheLimit() {
        var now = 0L
        val m = memory { now }
        (1..5).forEach { now = it * 10L; m.remember("fact number $it about apps") }
        assertEquals(3, m.all().size)
        assertTrue(m.all().none { it.text.endsWith("1 about apps") })
    }

    @Test fun searchRanksByOverlap() {
        val m = memory()
        m.remember("Timer app is Google Clock")
        m.remember("The owner's Gmail is jan@example.com")
        assertEquals("The owner's Gmail is jan@example.com", m.search("which gmail account").first().text)
        assertTrue(m.search("zzz").isEmpty())
    }
}

class PromptCachingTest {
    @Test fun anthropicRoutesGetTwoBreakpoints() {
        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", "sys"))
            .put(JSONObject().put("role", "user").put("content", "goal"))
            .put(JSONObject().put("role", "assistant").put("content", JSONObject.NULL))
            .put(JSONObject().put("role", "tool").put("tool_call_id", "c").put("content", "result"))
            .put(JSONObject().put("role", "user").put("content", JSONArray()
                .put(JSONObject().put("type", "text").put("text", "shot"))
                .put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", "data:x")))))
        assertTrue(OpenRouterMindModel.promptCaching("anthropic/claude-sonnet-5"))
        assertFalse(OpenRouterMindModel.promptCaching("openai/gpt-6"))
        val cached = OpenRouterMindModel.withCacheBreakpoints(messages)
        assertEquals(2, Regex("cache_control").findAll(cached.toString()).count())
        assertTrue(cached.getJSONObject(0).get("content") is JSONArray)
        assertEquals("goal", cached.getJSONObject(1).getString("content"))
        assertTrue(cached.getJSONObject(4).getJSONArray("content").getJSONObject(0).has("cache_control"))
        assertFalse("input is not mutated", messages.toString().contains("cache_control"))
    }
}

class PointLabelsTest {
    private fun node(id: String, path: String, depth: Int, bounds: UiBounds, text: String = "", clickable: Boolean = false) = UiNodeSnapshot(
        id = id, path = path, parentId = null, childIds = emptyList(), depth = depth, windowId = 1, className = "View",
        role = if (clickable) "button" else "text", text = text, contentDescription = "", resourceId = "", bounds = bounds,
        clickable = clickable, longClickable = false, editable = false, scrollable = false, enabled = true, selected = false,
        checked = false, checkable = false, focused = false, focusable = false, visibleToUser = true)

    @Test fun aTapOnAButtonsChildCarriesTheButtonsWords() {
        val nodes = listOf(
            node("root", "0", 0, UiBounds(0, 0, 1080, 2400)),
            node("send", "0/1", 1, UiBounds(900, 2200, 1060, 2300), clickable = true),
            node("icon", "0/1/0", 2, UiBounds(920, 2210, 980, 2290)),
            node("label", "0/1/1", 2, UiBounds(980, 2210, 1050, 2290), text = "Send"),
        )
        assertTrue("Send" in ClickGateIntercept.labelsAtPoint(nodes, 950, 2250))
        assertTrue(ClickGateIntercept.labelsAtPoint(nodes, 10, 10).isEmpty())
    }
}
