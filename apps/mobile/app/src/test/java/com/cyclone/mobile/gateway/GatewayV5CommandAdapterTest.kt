package com.cyclone.mobile.gateway

import com.cyclone.mobile.mind.MindUsage
import com.cyclone.mobile.mind.mission.Mission
import com.cyclone.mobile.mind.mission.MissionStatus
import com.cyclone.mobile.mind.mission.OwnerField
import com.cyclone.mobile.mind.mission.OwnerSend
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

class GatewayV5CommandAdapterTest {
    private val started = mutableListOf<String>()
    private val sent = mutableListOf<Pair<String, TaskCommand>>()
    private var live: Mission? = null
    private var overlay = true
    private var busy = false
    private var moment: OwnerMoment? = null

    private val mission = Mission("m1abcdefgh", "post the weekly video", MissionStatus.RUNNING, 1, 2, "openai/gpt-5", "GPT-5",
        turns = 3, usage = MindUsage(100, 20, 0.02))

    @Before fun install() {
        GatewayV5CommandAdapter.overlayReady = { overlay }
        GatewayV5CommandAdapter.busyFor = { busy }
        GatewayV5CommandAdapter.humanHasControl = { false }
        GatewayV5CommandAdapter.start = { goal -> started += goal; "m1abcdefgh" }
        GatewayV5CommandAdapter.running = { id -> live?.takeIf { it.id == id } }
        GatewayV5CommandAdapter.load = { id -> mission.takeIf { it.id == id }?.copy(status = MissionStatus.COMPLETED, summary = "Posted.") }
        GatewayV5CommandAdapter.moment = { moment }
        GatewayV5CommandAdapter.send = { task, command -> sent += task to command; TaskCommandResult.done(TaskEngine.MIND, "ok") }
    }

    private fun code(block: () -> Unit): String = (runCatching(block).exceptionOrNull() as GatewayProtocolException).code
    private fun approval(text: String = "Send this message?", send: OwnerSend? = OwnerSend("See you at 6", "Anna", "WhatsApp")) =
        OwnerMoment("mission-m1abcdefgh", TaskEngine.MIND, MomentKind.APPROVAL, text, emptyList(), requestId = "req-7", gate = "send", send = send)
    private fun answer(json: JSONObject) = GatewayV5CommandAdapter.answer(json.put("missionId", "m1abcdefgh"))

    @Test fun startsAnOrdinaryMissionAndRefusesSecretsAndBusyPhones() {
        val ack = GatewayV5CommandAdapter.start(JSONObject().put("goal", "Post the weekly video"))
        assertEquals("m1abcdefgh", ack.getString("missionId"))
        assertEquals(listOf("Post the weekly video"), started)
        assertEquals("INVALID_REQUEST", code { GatewayV5CommandAdapter.start(JSONObject().put("goal", "log in, password: hunter2")) })
        assertEquals("INVALID_REQUEST", code { GatewayV5CommandAdapter.start(JSONObject().put("goal", "x").put("shell", "id")) })
        busy = true
        assertEquals("ASK_BUSY", code { GatewayV5CommandAdapter.start(JSONObject().put("goal", "x")) })
        busy = false; overlay = false
        assertEquals("OVERLAY_UNAVAILABLE", code { GatewayV5CommandAdapter.start(JSONObject().put("goal", "x")) })
        assertEquals(1, started.size)
    }

    @Test fun aSignupMappingTaskStartsWithItsAppAndNothingElse() {
        val mapped = mutableListOf<Pair<String, String>>()
        GatewayV5CommandAdapter.startSignup = { goal, pkg -> mapped += goal to pkg; "m1abcdefgh" }
        GatewayV5CommandAdapter.installed = { pkg -> pkg == "com.instagram.android" }
        val ack = GatewayV5CommandAdapter.start(JSONObject().put("goal", "Map the sign-up of Instagram").put("signupMap", "com.instagram.android"))
        assertEquals("m1abcdefgh", ack.getString("missionId"))
        assertEquals(listOf("Map the sign-up of Instagram" to "com.instagram.android"), mapped)
        assertTrue(started.isEmpty())
        assertEquals("CAPABILITY_UNAVAILABLE", code { GatewayV5CommandAdapter.start(JSONObject().put("goal", "x").put("signupMap", "com.not.installed")) })
        assertEquals("INVALID_REQUEST", code { GatewayV5CommandAdapter.start(JSONObject().put("goal", "x").put("signupMap", "rm -rf /")) })
        assertEquals("INVALID_REQUEST", code { GatewayV5CommandAdapter.start(JSONObject().put("goal", "x").put("signupMap", "com.instagram.android").put("publish", true)) })
        assertEquals(1, mapped.size)
    }

    @Test fun anAccountSetupRunStartsWithTheMapAndTheRowsValues() {
        val recorder = com.cyclone.mobile.mind.signup.SignupRecorder("com.instagram.android", "Instagram", "350.0") { 1L }
        recorder.page("Enter your email", listOf(mapOf("label" to "Email", "kind" to "email")), "Next", null)
        val map = recorder.finish(false)
        val plans = mutableListOf<com.cyclone.mobile.mind.signup.AccountSetupPlan>()
        GatewayV5CommandAdapter.installed = { true }
        GatewayV5CommandAdapter.signupMap = { pkg -> map.takeIf { pkg == "com.instagram.android" } }
        GatewayV5CommandAdapter.startSetup = { _, plan -> plans += plan; "m2abcdefgh" }
        val run = JSONObject().put("package", "com.instagram.android").put("values", JSONObject().put("email", "hello@brand.one"))
        val ack = GatewayV5CommandAdapter.start(JSONObject().put("goal", "Create a new Instagram account").put("signupRun", run))
        assertEquals("m2abcdefgh", ack.getString("missionId"))
        assertEquals(mapOf("email" to "hello@brand.one"), plans.single().values)
        val other = JSONObject().put("package", "com.other.app").put("values", JSONObject())
        assertEquals("CAPABILITY_UNAVAILABLE", code { GatewayV5CommandAdapter.start(JSONObject().put("goal", "x").put("signupRun", other)) })
        val bad = JSONObject().put("package", "com.instagram.android").put("values", JSONObject().put("Email", "x"))
        assertEquals("INVALID_REQUEST", code { GatewayV5CommandAdapter.start(JSONObject().put("goal", "x").put("signupRun", bad)) })
        com.cyclone.mobile.mind.signup.AccountSetupProgress.set("m2abcdefgh", com.cyclone.mobile.mind.signup.AccountSetupProgress(page = 1, pages = 1))
        assertEquals(1, plans.size)
    }

    @Test fun statusCarriesTheExactSendAndTheSummaryWhenDone() {
        live = mission
        moment = approval()
        val status = GatewayV5CommandAdapter.status(JSONObject().put("missionId", "m1abcdefgh"))
        val shown = status.getJSONObject("moment")
        assertEquals("approval", shown.getString("kind"))
        assertEquals("req-7", shown.getString("requestId"))
        assertEquals("See you at 6", shown.getJSONObject("send").getString("text"))
        assertTrue(shown.getBoolean("approvableHere"))

        live = null
        val done = GatewayV5CommandAdapter.status(JSONObject().put("missionId", "m1abcdefgh"))
        assertEquals("completed", done.getString("status"))
        assertEquals("Posted.", done.getString("summary"))
        assertTrue(done.isNull("moment"))
    }

    @Test fun approvesOnlyTheRequestItWasShown() {
        live = mission
        moment = approval()
        answer(JSONObject().put("action", "approve").put("requestId", "req-7"))
        assertEquals(TaskCommand.Approve, sent.single().second)
        assertEquals("mission-m1abcdefgh", sent.single().first)
        assertEquals("MOMENT_CHANGED", code { answer(JSONObject().put("action", "approve").put("requestId", "req-6")) })
        assertEquals("INVALID_REQUEST", code { answer(JSONObject().put("action", "approve")) })
        moment = moment!!.copy(taskId = "mission-other")
        assertEquals("MOMENT_CHANGED", code { answer(JSONObject().put("action", "approve").put("requestId", "req-7")) })
        assertEquals(1, sent.size)
    }

    @Test fun aSendWithRedactedPartsIsApprovedOnThePhoneOnly() {
        live = mission
        moment = approval(send = OwnerSend("My code is 123456", "Bank", "Messages"))
        assertFalse(GatewayV5CommandAdapter.approvableHere(moment!!))
        assertEquals("ANSWER_ON_PHONE", code { answer(JSONObject().put("action", "approve").put("requestId", "req-7")) })
        assertTrue(sent.isEmpty())
    }

    @Test fun secureInputAndHandoverAreNeverAnsweredFromThePc() {
        live = mission
        moment = OwnerMoment("mission-m1abcdefgh", TaskEngine.MIND, MomentKind.SECRET, "Type your password", emptyList(), requestId = "req-s")
        assertEquals("ANSWER_ON_PHONE", code { answer(JSONObject().put("action", "reply").put("requestId", "req-s").put("text", "x")) })
        assertTrue(sent.isEmpty())
    }

    @Test fun repliesFillsDeclinesAndStopsGoThroughTaskKit() {
        live = mission
        moment = OwnerMoment("mission-m1abcdefgh", TaskEngine.MIND, MomentKind.VALUES, "Which caption?", emptyList(),
            fields = listOf(OwnerField("Caption", "text")), requestId = "req-v")
        answer(JSONObject().put("action", "fill").put("requestId", "req-v").put("values", JSONObject().put("Caption", "New drop")))
        answer(JSONObject().put("action", "decline").put("requestId", "req-v"))
        answer(JSONObject().put("action", "stop"))
        assertEquals(listOf("fill", "decline", "cancel"), sent.map { it.second.wire })
        assertEquals("INVALID_REQUEST", code {
            answer(JSONObject().put("action", "fill").put("requestId", "req-v").put("values", JSONObject().put("password", "x")))
        })
        assertEquals("INVALID_REQUEST", code { answer(JSONObject().put("action", "approve").put("requestId", "req-v")) })
        assertEquals("INVALID_REQUEST", code { answer(JSONObject().put("action", "run_shell").put("requestId", "req-v")) })
    }

    @Test fun aPostingTaskTurnsOnThePublishGateForItsMissionOnly() {
        com.cyclone.mobile.policy.PublishGate.liveMission = { "m1abcdefgh" }
        GatewayV5CommandAdapter.start(JSONObject().put("goal", "Post the video").put("taskId", "tsk_video0001").put("publish", true))
        assertTrue(com.cyclone.mobile.policy.PublishGate.active())
        GatewayV5CommandAdapter.start(JSONObject().put("goal", "Check the inbox"))
        assertFalse(com.cyclone.mobile.policy.PublishGate.active())
        assertEquals("INVALID_REQUEST", code { GatewayV5CommandAdapter.start(JSONObject().put("goal", "x").put("publish", "yes")) })
        com.cyclone.mobile.policy.PublishGate.liveMission = { null }
    }
}
