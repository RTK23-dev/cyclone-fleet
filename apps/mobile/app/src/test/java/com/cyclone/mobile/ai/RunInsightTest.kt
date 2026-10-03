package com.cyclone.mobile.ai

import com.cyclone.mobile.gateway.GatewayProtocol
import com.cyclone.mobile.gateway.GatewayProtocolException
import com.cyclone.mobile.gateway.GatewayV5RunsAdapter
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RunInsightTest {
    private var clock = 1_000L
    private val run = "ai-run-1"

    private fun ev(kind: String, text: String = kind.lowercase(), code: String? = null, ok: Boolean? = null, detail: String? = null) =
        AiTraceEvent("evt-${clock}", run, clock.also { clock += 500 }, kind, text, code, ok, detail)

    private fun session(status: String, result: String = "", goal: String = "find the dm of Louella") =
        AiTraceSession(run, goal, "model-x", status, 1_000L, if (status == "RUNNING") null else clock, result, 3)

    /** Start, open Facebook, tap Messages, verify. */
    private fun opening(): MutableList<AiTraceEvent> = mutableListOf(
        ev("START", "Starting task", "task.start"),
        ev("TOOL_REQUESTED", "Opening the app needed for this task", "tool.requested", detail = "action=open_app:com.facebook.katana · page=aaaaaaaaaaaaaaaa"),
        ev("ANDROID_EXECUTION", "Opened Facebook", "executor.ok", ok = true, detail = "executorInvoked=true"),
        ev("VERIFICATION", "Screen changed", "verify.progress", ok = true),
        ev("TOOL_REQUESTED", "Opening the selected control: Messages", "tool.requested", detail = "action=click:Messages · page=bbbbbbbbbbbbbbbb"),
        ev("ANDROID_EXECUTION", "Tapped Messages", "executor.ok", ok = true),
    )

    @After
    fun reset() {
        GatewayV5RunsAdapter.sessions = { emptyList() }
        GatewayV5RunsAdapter.session = { null }
        GatewayV5RunsAdapter.events = { emptyList() }
        GatewayV5RunsAdapter.marks = com.cyclone.mobile.gateway.RunMarks.InMemory()
        GatewayV5RunsAdapter.doorsOut = { _, _ -> null }
        GatewayV5RunsAdapter.doorTargets = { _, _ -> null }
    }

    @Test
    fun stepsFollowDecisionTurnsWithOutcomes() {
        val events = opening().apply { add(ev("VERIFICATION", "Expected screen missing", "verify.failed", ok = false)) }
        val steps = RunInsight.steps(events)
        assertEquals(3, steps.size)
        assertEquals("Starting the task", steps[0].title)
        assertEquals("open_app:com.facebook.katana", steps[1].action)
        assertEquals("aaaaaaaaaaaaaaaa", steps[1].pageId)
        assertEquals(RunInsight.StepOutcome.OK, steps[1].outcome)
        assertEquals(RunInsight.StepOutcome.UNVERIFIED, steps[2].outcome)
    }

    @Test
    fun completedAndRunningRunsHaveNoCauseOfDeath() {
        val events = opening().apply { add(ev("DONE", "Task finished", "task.finish", ok = true)) }
        assertNull(RunInsight.causeOfDeath(session("COMPLETED"), events))
        assertNull(RunInsight.causeOfDeath(session("RUNNING"), opening()))
    }

    @Test
    fun loginWallIsNeedsSecretOnTheRightStep() {
        val events = opening().apply {
            add(ev("GATE_SUSPEND", "Facebook wants a password. Waiting for the Secrets Card on the phone.", "gate.need_secret"))
        }
        val cause = RunInsight.causeOfDeath(session("SUSPENDED"), events)!!
        assertEquals("needs-secret", cause.kind)
        assertEquals(2, cause.stepIndex)
        assertTrue(cause.fix.contains("password slot"))

        val hard = opening().apply { add(ev("HARD_BLOCKER", "hard blocker", "Log in to continue")) }
        assertEquals("needs-secret", RunInsight.causeOfDeath(session("FAILED"), hard)!!.kind)
    }

    @Test
    fun gateApprovalThatWasResumedIsNotTheCause() {
        val events = opening().apply {
            add(ev("GATE_SUSPEND", "Approve sending this message?", "gate.approve"))
            add(ev("GATE_RESUME", "Approved", "gate.resume"))
            add(ev("NON_CONVERGENCE", "non convergence", "convergence.task_timeout", ok = false))
        }
        assertEquals("timeout", RunInsight.causeOfDeath(session("FAILED"), events)!!.kind)
        val waiting = opening().apply { add(ev("GATE_SUSPEND", "Approve sending this message?", "gate.approve")) }
        assertEquals("gate", RunInsight.causeOfDeath(session("SUSPENDED"), waiting)!!.kind)
    }

    @Test
    fun nonConvergenceCodesMapToPlainCauses() {
        val expected = mapOf(
            "convergence.repeated_action" to "unchanged",
            "convergence.stale_target" to "element-not-found",
            "convergence.backtrack" to "wrong-room",
            "convergence.mutations_without_verified_progress" to "verification-failed",
            "completion.ambiguous_after_recheck" to "verification-failed",
            "convergence.malformed_model" to "model-gave-up",
            "classifier.non_convergence" to "model-gave-up",
        )
        for ((code, kind) in expected) {
            val events = opening().apply { add(ev("NON_CONVERGENCE", "non convergence", code, ok = false)) }
            val cause = RunInsight.causeOfDeath(session("FAILED"), events)!!
            assertEquals(code, kind, cause.kind)
            assertEquals(code, 2, cause.stepIndex)
        }
    }

    @Test
    fun cancelledHumanAndProviderFailuresAreNamed() {
        val cancelled = opening().apply { add(ev("CANCELLED", "cancelled", "Request stopped by you.")) }
        assertEquals("cancelled", RunInsight.causeOfDeath(session("CANCELLED"), cancelled)!!.kind)
        val human = opening().apply {
            add(ev("ACTION_REJECTED", "Rejected", "HUMAN_HAS_CONTROL", ok = false))
            add(ev("CANCELLED", "cancelled"))
        }
        assertEquals("human-took-control", RunInsight.causeOfDeath(session("CANCELLED"), human)!!.kind)
        val locked = opening().apply { add(ev("ACTION_REJECTED", "Rejected", "PHONE_LOCKED", ok = false)) }
        assertEquals("transport", RunInsight.causeOfDeath(session("FAILED"), locked)!!.kind)
        val provider = opening().apply { add(ev("ERROR", "The model provider returned 502", "provider.http_502", ok = false)) }
        assertEquals("provider-error", RunInsight.causeOfDeath(session("FAILED"), provider)!!.kind)
        val unknown = opening().apply { add(ev("ANDROID_EXECUTION", "Tap failed", "executor.failed", ok = false)) }
        val cause = RunInsight.causeOfDeath(session("FAILED", "Couldn't finish"), unknown)!!
        assertEquals("unknown", cause.kind)
        assertEquals(2, cause.stepIndex)
    }

    @Test
    fun wireTextNeverCarriesAKeyValueSecretForm() {
        val events = opening().apply {
            add(ev("TOOL_REQUESTED", "Filling the requested field without storing its contents", detail = "password=[REDACTED] token: abc spin: 3"))
            add(ev("NON_CONVERGENCE", "non convergence", "convergence.task_timeout", ok = false))
        }
        val text = RunInsight.detailJson(session("FAILED", "api_key=sk-live-123"), events).toString()
        val inlineSecret = Regex("(?i)(password|passcode|passwd|pin|otp|token|secret|api[_-]?key|authorization|cookie|cvv|credential)\\s*[:=]")
        assertFalse(text, inlineSecret.containsMatchIn(text))
        assertFalse(text.contains("sk-live-123"))
    }

    @Test
    fun runsOpsListAndGetFromTheTrace() {
        val failed = opening().apply { add(ev("NON_CONVERGENCE", "non convergence", "convergence.stale_target", ok = false)) }
        val ok = session("COMPLETED").copy(id = "ai-run-2", goal = "open clock")
        GatewayV5RunsAdapter.sessions = { listOf(session("FAILED"), ok) }
        GatewayV5RunsAdapter.session = { id -> if (id == run) session("FAILED") else null }
        GatewayV5RunsAdapter.events = { id -> if (id == run) failed else emptyList() }

        val all = GatewayV5RunsAdapter.dispatch("runs.list", JSONObject()).getJSONArray("runs")
        assertEquals(2, all.length())
        val first = all.getJSONObject(0)
        assertEquals("failed", first.getString("status"))
        assertEquals("element-not-found", first.getJSONObject("cause").getString("kind"))
        assertEquals(3, first.getInt("stepCount"))
        assertTrue(all.getJSONObject(1).isNull("cause"))

        val onlyFailed = GatewayV5RunsAdapter.dispatch("runs.list", JSONObject().put("filter", "failed")).getJSONArray("runs")
        assertEquals(1, onlyFailed.length())

        val detail = GatewayV5RunsAdapter.dispatch("runs.get", JSONObject().put("runId", run))
        val steps = detail.getJSONArray("steps")
        assertEquals(3, steps.length())
        assertEquals("failed", steps.getJSONObject(2).getString("outcome"))
        assertEquals("NON_CONVERGENCE", steps.getJSONObject(2).getJSONArray("events").getJSONObject(2).getString("kind"))
    }

    @Test
    fun runsOpsValidateArgumentsAndAreReadOnly() {
        fun code(args: JSONObject, op: String = "runs.list") =
            (runCatching { GatewayV5RunsAdapter.dispatch(op, args) }.exceptionOrNull() as GatewayProtocolException).code
        assertEquals("INVALID_REQUEST", code(JSONObject().put("limit", 0)))
        assertEquals("INVALID_REQUEST", code(JSONObject().put("filter", "everything")))
        assertEquals("INVALID_REQUEST", code(JSONObject().put("sql", "drop")))
        assertEquals("INVALID_REQUEST", code(JSONObject().put("runId", "../etc"), "runs.get"))
        assertEquals("RUN_NOT_FOUND", code(JSONObject().put("runId", "ai-missing"), "runs.get"))
        assertTrue(setOf("runs.list", "runs.get").all { it in GatewayProtocol.operations && it in GatewayProtocol.legacyReadOnlyOperations })
    }

    @Test
    fun runRecordV2CarriesRoomsAppVersionAndDecisionSource() {
        val events = mutableListOf(
            ev("START", "Starting task", "task.start"),
            ev("TOOL_REQUESTED", "Opening Gmail", "tool.requested",
                detail = "action=open_app:com.google.android.gm · room=screen:home:0123456789abcdef · place=package:com.google.android.gm · appv=2026.09.14"),
            ev("VERIFY", "verify", "verify.progress", ok = true, detail = "roomAfter=screen:list:aaaaaaaaaaaaaaaa"),
            ev("TOOL_REQUESTED", "Known door: Inbox", "tool.requested",
                detail = "action=graph:door-7 · room=screen:list:aaaaaaaaaaaaaaaa · place=package:com.google.android.gm · appv=2026.09.14"),
            ev("VERIFY", "verify", "verify.progress", ok = true, detail = "roomAfter=screen:detail:bbbbbbbbbbbbbbbb"),
            ev("TOOL_REQUESTED", "Bad room", "tool.requested", detail = "action=click:x · room=Inbox of alice@example.com"),
        )
        val steps = RunInsight.steps(events)
        assertEquals("screen:home:0123456789abcdef", steps[1].roomId)
        assertEquals("screen:list:aaaaaaaaaaaaaaaa", steps[1].roomAfter)
        assertEquals("package:com.google.android.gm", steps[1].placeId)
        assertEquals("2026.09.14", steps[1].appVersion)
        assertEquals("model", steps[1].decisionSource)
        assertEquals("map", steps[2].decisionSource)
        assertNull("a non-structural room value is dropped", steps[3].roomId)
        assertNull(steps[0].decisionSource)

        val detail = RunInsight.detailJson(session("COMPLETED"), events)
        assertEquals(1, detail.getInt("mapSteps"))
        assertEquals(2, detail.getInt("modelSteps"))
        val place = detail.getJSONArray("places").getJSONObject(0)
        assertEquals("package:com.google.android.gm", place.getString("placeId"))
        assertEquals(3, place.getJSONArray("route").length())
        assertEquals("map", detail.getJSONArray("steps").getJSONObject(2).getString("decisionSource"))
        assertTrue(detail.getJSONArray("steps").getJSONObject(3).isNull("roomId"))
    }

    @Test
    fun aFailedMappedDoorIsAStaleDoorWithTheAppVersion() {
        val events = opening().apply {
            add(ev("TOOL_REQUESTED", "Known door: Messages", "tool.requested",
                detail = "action=graph:door-3 · room=screen:home:0123456789abcdef · place=package:com.facebook.katana · appv=512.0.0"))
            add(ev("ANDROID_EXECUTION", "Tap failed", "executor.failed", ok = false))
            add(ev("NON_CONVERGENCE", "non convergence", "convergence.stale_target", ok = false))
        }
        val cause = RunInsight.causeOfDeath(session("FAILED"), events)!!
        assertEquals("stale-door", cause.kind)
        assertEquals(3, cause.stepIndex)
        assertTrue(cause.headline.contains("512.0.0"))
        // The same failure chosen by the model stays a model/screen problem.
        val model = opening().apply { add(ev("NON_CONVERGENCE", "non convergence", "convergence.stale_target", ok = false)) }
        assertEquals("element-not-found", RunInsight.causeOfDeath(session("FAILED"), model)!!.kind)
    }

    @Test
    fun runsCanBeMarkedExpectedOnThePhone() {
        GatewayV5RunsAdapter.sessions = { listOf(session("FAILED")) }
        GatewayV5RunsAdapter.session = { id -> if (id == run) session("FAILED") else null }
        GatewayV5RunsAdapter.events = { opening() }
        assertFalse(GatewayV5RunsAdapter.dispatch("runs.list", JSONObject()).getJSONArray("runs").getJSONObject(0).getBoolean("expected"))
        val marked = GatewayV5RunsAdapter.dispatch("runs.mark", JSONObject().put("runId", run).put("expected", true))
        assertTrue(marked.getBoolean("expected"))
        assertTrue(GatewayV5RunsAdapter.dispatch("runs.get", JSONObject().put("runId", run)).getBoolean("expected"))
        GatewayV5RunsAdapter.dispatch("runs.mark", JSONObject().put("runId", run).put("expected", false))
        assertFalse(GatewayV5RunsAdapter.dispatch("runs.list", JSONObject()).getJSONArray("runs").getJSONObject(0).getBoolean("expected"))
        fun code(args: JSONObject) = (runCatching { GatewayV5RunsAdapter.dispatch("runs.mark", args) }.exceptionOrNull() as GatewayProtocolException).code
        assertEquals("INVALID_REQUEST", code(JSONObject().put("runId", run).put("expected", "yes")))
        assertEquals("RUN_NOT_FOUND", code(JSONObject().put("runId", "ai-missing").put("expected", true)))
        assertTrue("runs.mark" in GatewayProtocol.operations && "runs.mark" !in GatewayProtocol.legacyReadOnlyOperations)
    }

    @Test
    fun givingUpInAMappedRoomWithNoDoorOnwardIsDoorMissing() {
        val events = opening().apply {
            add(ev("TOOL_REQUESTED", "Looking around", "tool.requested",
                detail = "action=click:x · room=screen:list:aaaaaaaaaaaaaaaa · place=package:com.facebook.katana · appv=512.0.0"))
            add(ev("NON_CONVERGENCE", "non convergence", "classifier.non_convergence", ok = false))
        }
        GatewayV5RunsAdapter.sessions = { listOf(session("FAILED")) }
        GatewayV5RunsAdapter.session = { session("FAILED") }
        GatewayV5RunsAdapter.events = { events }
        GatewayV5RunsAdapter.doorsOut = { place, room -> if (place == "package:com.facebook.katana" && room == "screen:list:aaaaaaaaaaaaaaaa") 0 else null }
        val cause = GatewayV5RunsAdapter.dispatch("runs.get", JSONObject().put("runId", run)).getJSONObject("cause")
        assertEquals("door-missing", cause.getString("kind"))
        assertEquals(3, cause.getInt("stepIndex"))
        // A room with doors, or a room the map does not know, keeps the model's cause.
        GatewayV5RunsAdapter.doorsOut = { _, _ -> 2 }
        assertEquals("model-gave-up", GatewayV5RunsAdapter.dispatch("runs.list", JSONObject()).getJSONArray("runs").getJSONObject(0).getJSONObject("cause").getString("kind"))
        GatewayV5RunsAdapter.doorsOut = { _, _ -> null }
        assertEquals("model-gave-up", GatewayV5RunsAdapter.dispatch("runs.get", JSONObject().put("runId", run)).getJSONObject("cause").getString("kind"))
    }

    @Test
    fun aMapDoorThatLandsInAnUnexpectedRoomIsWrongRoom() {
        val home = "screen:home:aaaaaaaaaaaaaaaa"
        val list = "screen:list:bbbbbbbbbbbbbbbb"
        val settings = "screen:settings:cccccccccccccccc"
        val events = opening().apply {
            add(ev("TOOL_REQUESTED", "Known door: Open inbox", "tool.requested",
                detail = "action=graph:door-1 · room=$home · place=package:com.facebook.katana · appv=512.0.0"))
            add(ev("VERIFICATION", "Screen changed", "verify.progress", ok = true, detail = "roomAfter=$settings"))
            add(ev("NON_CONVERGENCE", "non convergence", "classifier.non_convergence", ok = false))
        }
        GatewayV5RunsAdapter.sessions = { listOf(session("FAILED")) }
        GatewayV5RunsAdapter.session = { session("FAILED") }
        GatewayV5RunsAdapter.events = { events }
        GatewayV5RunsAdapter.doorTargets = { _, room -> if (room == home) setOf(list) else null }
        val cause = GatewayV5RunsAdapter.dispatch("runs.get", JSONObject().put("runId", run)).getJSONObject("cause")
        assertEquals("wrong-room", cause.getString("kind"))
        assertEquals(3, cause.getInt("stepIndex"))
        // The door the map knows leads where the run went: the model's cause stands.
        GatewayV5RunsAdapter.doorTargets = { _, _ -> setOf(settings) }
        assertEquals("model-gave-up", GatewayV5RunsAdapter.dispatch("runs.get", JSONObject().put("runId", run)).getJSONObject("cause").getString("kind"))
        GatewayV5RunsAdapter.doorTargets = { _, _ -> null }
    }

    @Test fun aMindMissionHasOneStepPerToolAndItsCauseIsTheLastFailedTool() {
        val events = mutableListOf(
            ev("START", "Starting task", "task.start"),
            ev("MIND_TURN", "Turn 1: reading", "MIND_TURN"),
            ev("MIND_ACTION", "screen_read", "screen_read"),
            ev("MIND_RESULT", "Read the screen", "screen_read", ok = true),
            ev("MIND_ACTION", "type_text", "type_text"),
            ev("MIND_RESULT", "Not done: Cyclone could not tell that control apart", "type_text", ok = false),
            ev("MIND_ACTION", "type_text", "type_text"),
            ev("MIND_RESULT", "Not done: Cyclone could not tell that control apart", "type_text", ok = false),
        )
        val detail = RunInsight.detailJson(session("FAILED"), events)
        assertEquals(4, detail.getInt("stepCount"))
        assertEquals(3, detail.getJSONObject("metrics").getInt("toolCalls"))
        assertEquals(2, detail.getJSONObject("metrics").getInt("toolFailures"))
        val cause = detail.getJSONObject("cause")
        assertEquals("text-not-delivered", cause.getString("kind"))
        assertEquals(3, cause.getInt("stepIndex"))
    }
}
