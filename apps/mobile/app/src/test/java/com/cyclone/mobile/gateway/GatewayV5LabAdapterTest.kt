package com.cyclone.mobile.gateway

import com.cyclone.mobile.mind.MindUsage
import com.cyclone.mobile.mind.lab.MindLabVariant
import com.cyclone.mobile.mind.lab.MissionLab
import com.cyclone.mobile.mind.mission.Mission
import com.cyclone.mobile.mind.mission.MissionEvent
import com.cyclone.mobile.mind.mission.MissionStatus
import com.cyclone.mobile.mind.mission.OwnerField
import com.cyclone.mobile.owner.MomentKind
import com.cyclone.mobile.owner.OwnerMoment
import com.cyclone.mobile.task.TaskCommand
import com.cyclone.mobile.task.TaskCommandResult
import com.cyclone.mobile.task.TaskEngine
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class GatewayV5LabAdapterTest {
    private val started = mutableListOf<Triple<String, String, MindLabVariant>>()
    private val sent = mutableListOf<Pair<String, TaskCommand>>()
    private var live: Mission? = null
    private var overlay = true
    private var busy = false
    private var moment: OwnerMoment? = null

    private val mission = Mission("m1abcdefgh", "set a timer", MissionStatus.RUNNING, 1, 2, "openai/gpt-5", "GPT-5",
        turns = 3, usage = MindUsage(100, 20, 0.02), lab = MissionLab("run-abc123", MindLabVariant("A")),
        events = listOf(MissionEvent(5, "Typed code 123456 into the verification field")))

    @Before fun install() {
        GatewayV5LabAdapter.overlayReady = { overlay }
        GatewayV5LabAdapter.busy = { busy }
        GatewayV5LabAdapter.humanHasControl = { false }
        GatewayV5LabAdapter.start = { goal, run, variant -> started += Triple(goal, run, variant); "m1abcdefgh" }
        GatewayV5LabAdapter.live = { live }
        GatewayV5LabAdapter.load = { id -> mission.takeIf { it.id == id }?.copy(status = MissionStatus.COMPLETED) }
        GatewayV5LabAdapter.liveMetrics = { JSONObject().put("actions", 7) }
        GatewayV5LabAdapter.moment = { moment }
        GatewayV5LabAdapter.send = { task, command -> sent += task to command; TaskCommandResult.done(TaskEngine.MIND, "ok") }
        GatewayV5LabAdapter.appVersion = { "5.0.0-alpha.32.dev1" to 174L }
    }

    private fun code(block: () -> Unit): String = (runCatching(block).exceptionOrNull() as GatewayProtocolException).code
    private fun startArgs(goal: String = "Set a 5 minute timer") = JSONObject().put("goal", goal).put("runId", "run-abc123")
        .put("variant", JSONObject().put("name", "A").put("marks", false))

    @Test fun startsATaggedMindMission() {
        val ack = GatewayV5LabAdapter.start(startArgs())
        assertEquals("mission-m1abcdefgh", ack.getString("taskId"))
        assertEquals("run-abc123", started.single().second)
        assertFalse(started.single().third.marks)
    }

    @Test fun startIsRefusedWhenThePhoneIsNotReadyOrTheRequestIsWrong() {
        assertEquals("INVALID_REQUEST", code { GatewayV5LabAdapter.start(startArgs("my password: hunter2")) })
        assertEquals("INVALID_REQUEST", code { GatewayV5LabAdapter.start(startArgs().put("shell", "id")) })
        assertEquals("INVALID_REQUEST", code { GatewayV5LabAdapter.start(startArgs().put("runId", "../x")) })
        assertEquals("INVALID_REQUEST", code { GatewayV5LabAdapter.start(startArgs().put("variant", JSONObject().put("name", "A").put("effort", "max"))) })
        busy = true
        assertEquals("ASK_BUSY", code { GatewayV5LabAdapter.start(startArgs()) })
        busy = false; overlay = false
        assertEquals("OVERLAY_UNAVAILABLE", code { GatewayV5LabAdapter.start(startArgs()) })
        assertTrue(started.isEmpty())
    }

    @Test fun statusShowsTheOpenMomentOnlyForThatMission() {
        live = mission
        moment = OwnerMoment("mission-m1abcdefgh", TaskEngine.MIND, MomentKind.VALUES, "Sign-up needs your name", emptyList(),
            fields = listOf(OwnerField("First name", "name")), requestId = "req-1")
        val status = GatewayV5LabAdapter.status(JSONObject().put("missionId", "m1abcdefgh"))
        assertTrue(status.getBoolean("live"))
        assertEquals("values", status.getJSONObject("moment").getString("kind"))
        assertEquals("First name", status.getJSONObject("moment").getJSONArray("fields").getJSONObject(0).getString("label"))

        moment = moment!!.copy(taskId = "mission-other")
        assertTrue(GatewayV5LabAdapter.status(JSONObject().put("missionId", "m1abcdefgh")).isNull("moment"))
        assertEquals("RUN_NOT_FOUND", code { GatewayV5LabAdapter.status(JSONObject().put("missionId", "mzzzzzzzz")) })
    }

    @Test fun theLabAnswersThroughTaskKitAndNeverApproves() {
        live = mission
        fun answer(json: JSONObject) = GatewayV5LabAdapter.answer(json.put("missionId", "m1abcdefgh"))
        answer(JSONObject().put("action", "reply").put("text", "Work account"))
        answer(JSONObject().put("action", "fill").put("values", JSONObject().put("First name", "Jan").put("Last name", "")))
        answer(JSONObject().put("action", "decline"))
        answer(JSONObject().put("action", "stop"))
        assertEquals(listOf(TaskCommand.Reply("Work account"), TaskCommand.Fill(mapOf("First name" to "Jan"), false), TaskCommand.Decline, TaskCommand.Stop),
            sent.map { it.second })
        assertTrue(sent.all { it.first == "mission-m1abcdefgh" })

        listOf(
            JSONObject().put("action", "approve"),
            JSONObject().put("action", "done"),
            JSONObject().put("action", "reply"),
            JSONObject().put("action", "reply").put("text", "otp: 123456"),
            JSONObject().put("action", "fill").put("values", JSONObject().put("Password", "hunter2")),
        ).forEach { assertEquals(it.toString(), "INVALID_REQUEST", code { answer(it) }) }
        live = null
        assertEquals("RUN_NOT_FOUND", code { answer(JSONObject().put("action", "stop")) })
    }

    @Test fun theRecordIsRedactedAndSaysWhatProducedIt() {
        val record = GatewayV5LabAdapter.record(JSONObject().put("missionId", "m1abcdefgh"))
        assertEquals("completed", record.getString("status"))
        assertEquals("A", record.getJSONObject("lab").getJSONObject("variant").getString("name"))
        assertEquals(174L, record.getJSONObject("app").getLong("versionCode"))
        assertFalse(record.toString().contains("123456"))
        live = mission
        assertEquals(7, GatewayV5LabAdapter.record(JSONObject().put("missionId", "m1abcdefgh")).getJSONObject("metrics").getInt("actions"))
    }
}
