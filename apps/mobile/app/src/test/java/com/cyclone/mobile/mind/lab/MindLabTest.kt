package com.cyclone.mobile.mind.lab

import com.cyclone.mobile.mind.MindMessage
import com.cyclone.mobile.mind.MindToolCall
import com.cyclone.mobile.mind.MindToolResult
import com.cyclone.mobile.mind.MindUsage
import com.cyclone.mobile.mind.mission.Mission
import com.cyclone.mobile.mind.mission.MissionStatus
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MindLabTest {
    @Test fun aVariantChangesOnlyWhatItNames() {
        val plain = MindLabVariant.parse(JSONObject().put("name", "A")).getOrThrow()
        assertEquals(MindLabVariant("A"), plain)
        assertNull(plain.modelId)
        assertTrue("lab runs start fresh unless told otherwise", plain.freshMemory && plain.marks)
        assertTrue("the map is on unless an arm turns it off", plain.useMap)
        assertFalse(MindLabVariant.parse(JSONObject().put("name", "no map").put("useMap", false)).getOrThrow().useMap)

        val full = MindLabVariant.parse(JSONObject().put("name", "B no marks").put("modelId", "anthropic/claude-sonnet-4.5")
            .put("effort", "low").put("workingMinutes", 10).put("marks", false).put("freshMemory", false)
            .put("promptAddendum", "Prefer deep links.").put("useMap", false)).getOrThrow()
        assertEquals(full, MindLabVariant.parse(full.toJson()).getOrThrow())
    }

    @Test fun badVariantsAreRefusedWithAReason() {
        listOf(
            JSONObject(),
            JSONObject().put("name", "A").put("shell", "x"),
            JSONObject().put("name", "A/B"),
            JSONObject().put("name", "A").put("modelId", "not a model"),
            JSONObject().put("name", "A").put("effort", "max"),
            JSONObject().put("name", "A").put("workingMinutes", 500),
            JSONObject().put("name", "A").put("workingMinutes", "10"),
            JSONObject().put("name", "A").put("marks", "yes"),
            JSONObject().put("name", "A").put("useMap", "on"),
            JSONObject().put("name", "A").put("promptAddendum", "x".repeat(MindLabVariant.MAX_ADDENDUM + 1)),
            JSONObject().put("name", "A").put("promptAddendum", "use password: hunter2"),
        ).forEach { assertTrue(it.toString(), MindLabVariant.parse(it).isFailure) }
        assertTrue(MindLabVariant.parse(null).isFailure)
    }

    @Test fun metricsCountWhatTheMissionDidWithoutItsContents() {
        var now = 0L
        val metrics = MissionMetrics { now }
        fun call(name: String, args: String = "{}") = MindToolCall("c", name, args)
        metrics.onModelStart(1, FakeModel)
        now = 1_500
        metrics.onAssistant(1, MindMessage.Assistant("", emptyList()))
        metrics.onToolResult(1, call("screen_tap", "{\"ref\":\"e3\"}"), MindToolResult("tapped", changedScreen = true))
        metrics.onToolResult(2, call("screen_tap", "{\"ref\":\"e3\"}"), MindToolResult("tapped", changedScreen = true))
        metrics.onToolResult(3, call("screen_type", "{\"text\":\"my password: hunter2\"}"), MindToolResult.error("password: hunter2 was rejected"))
        metrics.onToolResult(4, call("task_finish"), MindToolResult.error("evidence is required"))
        metrics.onToolResult(5, call("owner_ask"), MindToolResult("answered", ownerWaitMs = 4_000))
        metrics.onToolResult(5, call("go_to"), MindToolResult("arrived", changedScreen = true, mapMoves = 3))
        metrics.onNotice(5, "Switched from A to B: timeout")
        metrics.onNotice(6, "Model is rate-limited; waiting 5 s.")

        val json = metrics.toJson()
        assertEquals(2, json.getJSONObject("toolCalls").getInt("screen_tap"))
        assertEquals(6, json.getInt("actions"))
        assertEquals(3, json.getInt("mapMoves"))
        assertEquals(2, json.getInt("errors"))
        assertEquals(1, json.getInt("repeatedActions"))
        assertEquals(3, json.getInt("screenChanges"))
        assertEquals(4_000, json.getLong("ownerWaitMs"))
        assertEquals(1_500, json.getLong("modelMs"))
        assertEquals(1, json.getInt("finishRejections"))
        assertEquals(1, json.getInt("modelSwitches"))
        assertEquals(1, json.getInt("rateLimits"))
        assertEquals(3, json.getInt("firstErrorTurn"))
        assertFalse("typed values never reach metrics", json.toString().contains("hunter2"))
    }

    @Test fun aLabMissionKeepsItsTagAndMetricsAcrossTheStore() {
        val lab = MissionLab("run-abc123", MindLabVariant("B", modelId = "openai/gpt-5", marks = false))
        val mission = Mission("m1abcdef", "set a timer", MissionStatus.COMPLETED, 1, 2, "openai/gpt-5", "GPT-5",
            usage = MindUsage(10, 5, 0.01), lab = lab, metrics = JSONObject().put("actions", 3))
        val back = Mission.fromJson(mission.toJson())
        assertEquals(lab, back.lab)
        assertEquals(3, back.metrics!!.getInt("actions"))
        assertNull(Mission.fromJson(mission.copy(lab = null, metrics = null).toJson()).lab)
    }

    @Test fun oneBrokenListenerNeverStopsTheOthers() {
        val metrics = MissionMetrics()
        val broken = object : com.cyclone.mobile.mind.MindListener {
            override fun onToolResult(turn: Int, call: MindToolCall, result: MindToolResult) = error("boom")
        }
        TeeMindListener(listOf(broken, metrics)).onToolResult(1, MindToolCall("c", "screen_tap", "{}"), MindToolResult("ok"))
        assertEquals(1, metrics.toJson().getInt("actions"))
    }

    private object FakeModel : com.cyclone.mobile.mind.MindModel {
        override val id = "fake"
        override val label = "fake"
        override val vision = false
        override fun complete(request: com.cyclone.mobile.mind.MindModelRequest): com.cyclone.mobile.mind.MindModelReply = error("unused")
    }

    @Test fun thePlaneKnobIsOptionalAndChecked() {
        org.junit.Assert.assertNull(MindLabVariant.parse(org.json.JSONObject().put("name", "a")).getOrThrow().plane)
        org.junit.Assert.assertEquals("background",
            MindLabVariant.parse(org.json.JSONObject().put("name", "a").put("plane", "background")).getOrThrow().plane)
        org.junit.Assert.assertTrue(MindLabVariant.parse(org.json.JSONObject().put("name", "a").put("plane", "sideways")).isFailure)
    }
}
