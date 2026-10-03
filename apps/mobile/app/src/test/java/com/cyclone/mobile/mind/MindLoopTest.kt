package com.cyclone.mobile.mind

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MindLoopTest {
    private class ScriptedModel(
        override val id: String,
        override val label: String = id,
        override val vision: Boolean = true,
        private val script: MutableList<(MindModelRequest) -> MindModelReply>,
    ) : MindModel {
        val requests = mutableListOf<MindModelRequest>()
        override fun complete(request: MindModelRequest): MindModelReply {
            requests += request
            check(script.isNotEmpty()) { "$id was asked more often than scripted" }
            return script.removeAt(0)(request)
        }
    }

    private class FakeToolbox(val handler: (MindToolCall, JSONObject) -> MindToolResult) : MindToolbox {
        val calls = mutableListOf<String>()
        override fun specs() = listOf("tap", "type_text", "screen_look", "task_finish", "task_give_up", "owner_ask")
            .map { MindToolSpec(it, "test $it") }
        override fun execute(call: MindToolCall, arguments: JSONObject): MindToolResult {
            calls += call.name
            return handler(call, arguments)
        }
    }

    private fun reply(vararg calls: Pair<String, String>, text: String = "") = { _: MindModelRequest ->
        MindModelReply(text, calls.mapIndexed { i, (name, args) -> MindToolCall("c${i}_$name", name, args) }, usage = MindUsage(10, 5, 0.001))
    }

    private val standardTools = FakeToolbox { call, _ ->
        when (call.name) {
            "task_finish" -> MindToolResult("Accepted.", ending = MindEnding.COMPLETED, summary = "Timer running", evidence = "4:59 shown")
            "task_give_up" -> MindToolResult("Recorded.", ending = MindEnding.GAVE_UP, summary = "No network")
            "tap" -> MindToolResult("Tapped. New screen: Clock", changedScreen = true)
            else -> MindToolResult("ok ${call.name}")
        }
    }

    private fun fresh() = MindConversation(listOf(MindMessage.System("sys"), MindMessage.User("set a 5 minute timer")))

    @Test fun finishesWhenTheModelProvesTheGoal() {
        val model = ScriptedModel("a", script = mutableListOf(reply("tap" to "{\"ref\":\"e1\"}"), reply("task_finish" to "{}")))
        val conversation = fresh()
        val outcome = MindLoop(model, null, standardTools).run(conversation)
        assertEquals(MindStatus.COMPLETED, outcome.status)
        assertEquals("Timer running", outcome.summary)
        assertEquals("4:59 shown", outcome.evidence)
        assertEquals(2, outcome.turns)
        assertEquals(20, outcome.usage.promptTokens)
        // The second request carries the full conversation: goal, first call and its result.
        val wire = model.requests[1].messages
        assertEquals("tool", wire.getJSONObject(3).getString("role"))
        assertEquals("c0_tap", wire.getJSONObject(3).getString("tool_call_id"))
    }

    @Test fun onlyOneScreenChangePerTurnButEveryCallGetsAResult() {
        val tools = FakeToolbox { call, _ -> if (call.name == "tap") MindToolResult("Tapped", changedScreen = true) else MindToolResult("typed") }
        val model = ScriptedModel("a", script = mutableListOf(
            reply("type_text" to "{}", "type_text" to "{}", "tap" to "{}", "tap" to "{}"),
            reply("task_give_up" to "{}"),
        ))
        val conversation = fresh()
        MindLoop(model, null, FakeToolbox { c, a -> if (c.name == "task_give_up") MindToolResult("x", ending = MindEnding.GAVE_UP) else tools.execute(c, a) }).run(conversation)
        val results = conversation.all().filterIsInstance<MindMessage.Tool>()
        assertEquals(5, results.size)
        assertTrue(results[3].full.startsWith("NOT RUN"))
    }

    @Test fun nudgesASilentModelThenStopsHonestly() {
        val model = ScriptedModel("a", script = mutableListOf(reply(text = "I think it's done"), reply(text = "done"), reply(text = "really")))
        val outcome = MindLoop(model, null, standardTools).run(fresh())
        assertEquals(MindStatus.FAILED, outcome.status)
        assertTrue(outcome.summary.contains("really"))
        assertEquals(3, model.requests.size)
    }

    @Test fun nudgeRecoversWhenTheModelThenActs() {
        val model = ScriptedModel("a", script = mutableListOf(reply(text = "thinking"), reply("task_finish" to "{}")))
        val conversation = fresh()
        assertEquals(MindStatus.COMPLETED, MindLoop(model, null, standardTools).run(conversation).status)
        assertTrue(conversation.all().any { it is MindMessage.User && it.text == MindPrompt.NUDGE })
    }

    @Test fun rateLimitSwitchesToBackupWithTheSameConversation() {
        val primary = ScriptedModel("a", script = mutableListOf({ _ -> throw MindModelError.RateLimited("429") }))
        val backup = ScriptedModel("b", script = mutableListOf(reply("task_finish" to "{}")))
        val conversation = fresh()
        conversation.add(MindMessage.Assistant("earlier", emptyList(), JSONArray().put(JSONObject().put("type", "reasoning.encrypted"))))
        val outcome = MindLoop(primary, backup, standardTools).run(conversation)
        assertEquals(MindStatus.COMPLETED, outcome.status)
        assertEquals("b", outcome.modelLabel)
        val sent = backup.requests.single().messages.toString()
        assertTrue(sent.contains("set a 5 minute timer"))
        assertFalse("reasoning state never crosses models", sent.contains("reasoning_details"))
    }

    @Test fun rateLimitWithoutBackupWaitsAndRetries() {
        var now = 0L
        val primary = ScriptedModel("a", script = mutableListOf({ _ -> throw MindModelError.RateLimited("429", 2_000) }, reply("task_finish" to "{}")))
        val outcome = MindLoop(primary, null, standardTools, clock = { now }, sleep = { now += it }).run(fresh())
        assertEquals(MindStatus.COMPLETED, outcome.status)
        assertTrue(now >= 2_000)
    }

    @Test fun deadlineRetriesOnceThenUsesBackup() {
        val primary = ScriptedModel("a", script = mutableListOf({ _ -> throw MindModelError.Deadline("slow") }, { _ -> throw MindModelError.Deadline("slow") }))
        val backup = ScriptedModel("b", script = mutableListOf(reply("task_finish" to "{}")))
        assertEquals(MindStatus.COMPLETED, MindLoop(primary, backup, standardTools).run(fresh()).status)
        assertEquals(2, primary.requests.size)
    }

    @Test fun fatalWithoutBackupFails() {
        val primary = ScriptedModel("a", script = mutableListOf({ _ -> throw MindModelError.Fatal("bad key") }))
        val outcome = MindLoop(primary, null, standardTools).run(fresh())
        assertEquals(MindStatus.FAILED, outcome.status)
        assertTrue(outcome.summary.contains("bad key"))
    }

    @Test fun toolsUnsupportedFallsBackToTextProtocol() {
        val primary = ScriptedModel("a", script = mutableListOf(
            { _ -> throw MindModelError.ToolsUnsupported("no tools") },
            { request ->
                assertFalse(request.nativeTools)
                val system = request.messages.getJSONObject(0).getString("content")
                assertTrue(system.contains("RESULT of"))
                assertTrue(system.contains("- task_finish"))
                MindModelReply("", listOf(MindToolCall("env_0_task_finish", "task_finish", "{}")))
            },
        ))
        assertEquals(MindStatus.COMPLETED, MindLoop(primary, null, standardTools).run(fresh()).status)
    }

    @Test fun cancellationStopsBeforeTheNextTurn() {
        var stop = false
        val tools = FakeToolbox { _, _ -> stop = true; MindToolResult("ok", changedScreen = true) }
        val primary = ScriptedModel("a", script = mutableListOf(reply("tap" to "{}")))
        val outcome = MindLoop(primary, null, tools, cancelled = { stop }).run(fresh())
        assertEquals(MindStatus.CANCELLED, outcome.status)
    }

    @Test fun workingTimeExcludesOwnerWaits() {
        var now = 0L
        val tools = FakeToolbox { call, _ ->
            when (call.name) {
                "owner_ask" -> { now += 20 * 60_000; MindToolResult("Owner answered: blue", ownerWaitMs = 20 * 60_000) }
                "task_finish" -> MindToolResult("ok", ending = MindEnding.COMPLETED, summary = "done")
                else -> MindToolResult("ok")
            }
        }
        val model = ScriptedModel("a", script = mutableListOf(
            { now += 5_000; reply("owner_ask" to "{}")(it) },
            reply("task_finish" to "{}"),
        ))
        val outcome = MindLoop(model, null, tools, budget = MindBudget(workingMs = 60_000), clock = { now }).run(fresh())
        assertEquals(MindStatus.COMPLETED, outcome.status)
        assertEquals(5_000, outcome.workingMs)
    }

    @Test fun outOfWorkingTimePausesWithProgress() {
        var now = 0L
        val model = ScriptedModel("a", script = mutableListOf(
            { now += 61_000; MindModelReply("Opened Clock", listOf(MindToolCall("c", "tap", "{}"))) },
        ))
        val checkpoints = mutableListOf<MindCheckpoint>()
        val listener = object : MindListener { override fun checkpoint(checkpoint: MindCheckpoint) { checkpoints += checkpoint } }
        val outcome = MindLoop(model, null, standardTools, MindBudget(workingMs = 60_000), listener, clock = { now }).run(fresh())
        assertEquals(MindStatus.OUT_OF_BUDGET, outcome.status)
        assertTrue(outcome.summary.contains("Opened Clock"))
        assertTrue(checkpoints.isNotEmpty())
    }

    @Test fun budgetWarningIsGivenOnce() {
        var now = 0L
        val model = ScriptedModel("a", script = mutableListOf(
            { now += 58_000; reply("type_text" to "{}")(it) },
            { now += 500; reply("type_text" to "{}")(it) },
            reply("task_finish" to "{}"),
        ))
        val conversation = fresh()
        MindLoop(model, null, standardTools, MindBudget(workingMs = 60_000, warnBeforeEndMs = 10_000), clock = { now }).run(conversation)
        assertEquals(1, conversation.all().count { it is MindMessage.User && it.text.contains("working time remain") })
    }

    @Test fun unknownToolsAndBadArgumentsAreReportedToTheModel() {
        val model = ScriptedModel("a", script = mutableListOf(
            reply("fly" to "{}", text = ""),
            reply("tap" to "{not json"),
            reply("task_finish" to "{}"),
        ))
        val conversation = fresh()
        MindLoop(model, null, standardTools).run(conversation)
        val results = conversation.all().filterIsInstance<MindMessage.Tool>()
        assertTrue(results[0].full.contains("There is no tool named fly"))
        assertTrue(results[1].full.contains("not valid JSON"))
    }

    @Test fun repeatedIdenticalFailuresGetOneNote() {
        val tools = FakeToolbox { call, _ ->
            if (call.name == "task_finish") MindToolResult("ok", ending = MindEnding.COMPLETED) else MindToolResult.error("No element e9")
        }
        val script = MutableList(4) { reply("tap" to "{\"ref\":\"e9\"}") }
        script += reply("task_finish" to "{}")
        val conversation = fresh()
        MindLoop(ScriptedModel("a", script = script), null, tools).run(conversation)
        assertEquals(1, conversation.all().count { it is MindMessage.User && it.text.contains("failed 3 times") })
    }

    @Test fun ownerMessagesJoinTheConversation() {
        val inbox = mutableListOf("make it 10 minutes instead")
        val model = ScriptedModel("a", script = mutableListOf(reply("task_finish" to "{}")))
        val conversation = fresh()
        MindLoop(model, null, standardTools, ownerMessages = { inbox.toList().also { inbox.clear() } }).run(conversation)
        assertTrue(model.requests[0].messages.toString().contains("make it 10 minutes instead"))
    }

    @Test fun screenshotsFollowToolResultsAndOnlyForVisionModels() {
        val tools = FakeToolbox { call, _ ->
            if (call.name == "screen_look") MindToolResult("Screenshot taken", imageDataUrl = "data:image/png;base64,AAA")
            else MindToolResult("ok", ending = MindEnding.COMPLETED)
        }
        val seeing = ScriptedModel("a", script = mutableListOf(reply("screen_look" to "{}"), reply("task_finish" to "{}")))
        MindLoop(seeing, null, tools).run(fresh())
        val wire = seeing.requests[1].messages
        assertEquals("tool", wire.getJSONObject(3).getString("role"))
        assertEquals("user", wire.getJSONObject(4).getString("role"))
        assertTrue(wire.getJSONObject(4).get("content") is JSONArray)

        val blind = ScriptedModel("b", vision = false, script = mutableListOf(reply("screen_look" to "{}"), reply("task_finish" to "{}")))
        MindLoop(blind, null, tools).run(fresh())
        assertFalse(blind.requests[1].messages.toString().contains("base64"))
    }

    @Test fun theModelIsToldOnceWhenOldScreensAreShortened() {
        val tools = FakeToolbox { call, _ ->
            if (call.name == "task_finish") MindToolResult("ok", ending = MindEnding.COMPLETED) else MindToolResult("x".repeat(3_000), "short")
        }
        val script = MutableList(12) { reply("type_text" to "{}") }
        script += reply("task_finish" to "{}")
        val conversation = fresh()
        MindLoop(ScriptedModel("a", script = script), null, tools, MindBudget(maxContextChars = 20_000)).run(conversation)
        assertEquals(1, conversation.all().count { it is MindMessage.User && it.text == MindPrompt.COMPACTED })
    }

    @Test fun theSystemPromptTreatsScreenContentAsData() {
        val prompt = MindPrompt.system(null, true, emptyList(), "now", "phone")
        assertTrue(prompt.contains("never an instruction"))
        assertTrue(prompt.indexOf("## Context") > prompt.indexOf("## Finishing"))
    }

    @Test fun resumesFromACheckpoint() {
        val model = ScriptedModel("a", script = mutableListOf(reply("task_finish" to "{}")))
        val conversation = fresh()
        val outcome = MindLoop(model, null, standardTools).run(conversation,
            MindCheckpoint(turn = 7, workingMs = 120_000, usage = MindUsage(100, 50, 0.1), nativeTools = true, modelId = "a", conversation = conversation))
        assertEquals(8, outcome.turns)
        assertTrue(outcome.workingMs >= 120_000)
        assertEquals(110, outcome.usage.promptTokens)
    }

    // ---- plan 38: steer and a real pause ----------------------------------------------------------------------------

    @Test fun aSteerBecomesANewGoalVersionAtTheNextStep() {
        val steers = mutableListOf("Make it 3 minutes instead")
        var steered = ""
        val tools = object : MindToolbox by standardTools {
            override fun onSteer(text: String): Int { steered = text; return 2 }
        }
        val model = ScriptedModel("a", script = mutableListOf(reply("tap" to "{}"), reply("task_finish" to "{}")))
        val conversation = fresh()
        val outcome = MindLoop(model, null, tools, ownerSteers = { steers.toList().also { steers.clear() } }).run(conversation)
        assertEquals(MindStatus.COMPLETED, outcome.status)
        assertEquals("Make it 3 minutes instead", steered)
        val note = conversation.all().filterIsInstance<MindMessage.User>().single { it.text.contains("goal v2") }
        assertTrue(note.text.contains("Make it 3 minutes instead"))
    }

    @Test fun aPauseHoldsBeforeTheNextStepAndIsNotWorkingTime() {
        var now = 0L
        var pausedFor = 3
        val model = ScriptedModel("a", script = mutableListOf(reply("task_finish" to "{}")))
        val conversation = fresh()
        val outcome = MindLoop(model, null, standardTools, clock = { now }, sleep = { now += it },
            paused = { (pausedFor-- > 0) }).run(conversation)
        assertEquals(MindStatus.COMPLETED, outcome.status)
        assertTrue("the loop waited while paused", now > 0)
        assertEquals("no model call happened while paused", 1, model.requests.size)
        assertTrue(conversation.all().any { it is MindMessage.User && it.text == MindPrompt.PAUSED })
        assertTrue("paused time is not working time", outcome.workingMs < now)
    }

    @Test fun aStopWhilePausedEndsWithoutAnotherStep() {
        var stop = false
        val model = ScriptedModel("a", script = mutableListOf())
        var polls = 0
        val outcome = MindLoop(model, null, standardTools, cancelled = { stop }, sleep = { if (++polls == 2) stop = true },
            paused = { true }).run(fresh())
        assertEquals(MindStatus.CANCELLED, outcome.status)
        assertEquals(0, model.requests.size)
    }
}

class MindConversationTest {
    @Test fun compactionKeepsThoughtsButShrinksOldResultsAndImages() {
        val conversation = MindConversation(listOf(MindMessage.System("sys"), MindMessage.User("goal")))
        repeat(20) { i ->
            conversation.add(MindMessage.Assistant("step $i", listOf(MindToolCall("c$i", "tap", "{}"))))
            conversation.add(MindMessage.Tool("c$i", "tap", "x".repeat(2_000), "tapped $i"))
            conversation.add(MindMessage.User("shot $i", "data:image/png;base64,AA", MindMessage.User.Origin.SCREEN))
        }
        conversation.compact(maxChars = 20_000, keepRecent = 6)
        val all = conversation.all()
        assertEquals(1, all.count { it is MindMessage.User && it.imageDataUrl != null })
        assertTrue(conversation.chars() <= 20_000)
        assertEquals(20, all.count { it is MindMessage.Assistant })
        assertFalse((all.last { it is MindMessage.Tool } as MindMessage.Tool).compacted)
        assertTrue((all.first { it is MindMessage.Tool } as MindMessage.Tool).compacted)
    }

    @Test fun compactionShrinksWellBelowTheCeilingSoThePrefixStaysStable() {
        val conversation = MindConversation(listOf(MindMessage.System("sys"), MindMessage.User("goal")))
        repeat(20) { i ->
            conversation.add(MindMessage.Assistant("step $i", listOf(MindToolCall("c$i", "tap", "{}"))))
            conversation.add(MindMessage.Tool("c$i", "tap", "x".repeat(2_000), "tapped $i"))
        }
        assertTrue(conversation.compact(maxChars = 30_000, keepRecent = 4) > 0)
        assertTrue(conversation.chars() <= 18_000)
        // Under the ceiling again: nothing changes on the next turns.
        conversation.add(MindMessage.Assistant("next", listOf(MindToolCall("n", "tap", "{}"))))
        conversation.add(MindMessage.Tool("n", "tap", "x".repeat(2_000), "tapped n"))
        assertEquals(0, conversation.compact(maxChars = 30_000, keepRecent = 4))
    }

    @Test fun journalRoundTripDropsReasoningAndImages() {
        val conversation = MindConversation(listOf(
            MindMessage.System("sys"),
            MindMessage.User("goal"),
            MindMessage.Assistant("ok", listOf(MindToolCall("c1", "tap", "{\"ref\":\"e2\"}")), JSONArray().put("secret-reasoning")),
            MindMessage.Tool("c1", "tap", "full result", "brief"),
            MindMessage.User("shot", "data:image/png;base64,AA", MindMessage.User.Origin.SCREEN),
        ))
        val journal = conversation.toJournal().toString()
        assertFalse(journal.contains("secret-reasoning"))
        assertFalse(journal.contains("base64"))
        val restored = MindConversation.fromJournal(JSONArray(journal)).all()
        assertEquals(5, restored.size)
        val assistant = restored[2] as MindMessage.Assistant
        assertNull(assistant.reasoningDetails)
        assertEquals("{\"ref\":\"e2\"}", assistant.toolCalls.single().arguments)
        assertEquals(MindMessage.User.Origin.SCREEN, (restored[4] as MindMessage.User).origin)
    }

    @Test fun textProtocolRendersCallsAndResults() {
        val conversation = MindConversation(listOf(
            MindMessage.User("goal"),
            MindMessage.Assistant("opening", listOf(MindToolCall("c1", "open_app", "{\"name\":\"Clock\"}"))),
            MindMessage.Tool("c1", "open_app", "Clock is open", "open"),
        ))
        val wire = conversation.toWire(nativeTools = false)
        val envelope = JSONObject(wire.getJSONObject(1).getString("content"))
        assertEquals("open_app", envelope.getJSONArray("calls").getJSONObject(0).getString("tool"))
        assertEquals("user", wire.getJSONObject(2).getString("role"))
        assertTrue(wire.getJSONObject(2).getString("content").startsWith("RESULT of open_app"))
    }
}

class MindModelParseTest {
    @Test fun parsesNativeToolCallsAndUsage() {
        val json = JSONObject("""{"choices":[{"message":{"role":"assistant","content":null,
            "tool_calls":[{"id":"call_1","type":"function","function":{"name":"set_timer","arguments":"{\"seconds\":300}"}}],
            "reasoning_details":[{"type":"reasoning.summary","summary":"x"}]}}],
            "usage":{"prompt_tokens":1200,"completion_tokens":40,"cost":0.0031}}""")
        val reply = OpenRouterMindModel.parse(json)
        assertEquals("set_timer", reply.toolCalls.single().name)
        assertEquals(300, reply.toolCalls.single().argumentsJson()!!.getInt("seconds"))
        assertEquals("", reply.text)
        assertEquals(1200, reply.usage.promptTokens)
        assertEquals(1, reply.reasoningDetails!!.length())
    }

    @Test fun parsesTheTextEnvelope() {
        val json = JSONObject().put("choices", JSONArray().put(JSONObject().put("message", JSONObject()
            .put("content", "Sure.\n{\"say\":\"Opening Clock\",\"calls\":[{\"tool\":\"open_app\",\"arguments\":{\"name\":\"Clock\"}}]}"))))
        val reply = OpenRouterMindModel.parse(json)
        assertEquals("Opening Clock", reply.text)
        assertEquals("open_app", reply.toolCalls.single().name)
        assertEquals("Clock", reply.toolCalls.single().argumentsJson()!!.getString("name"))
    }

    @Test fun plainTextHasNoCalls() {
        val json = JSONObject().put("choices", JSONArray().put(JSONObject().put("message", JSONObject().put("content", "All done {maybe}"))))
        val reply = OpenRouterMindModel.parse(json)
        assertTrue(reply.toolCalls.isEmpty())
        assertEquals("All done {maybe}", reply.text)
    }

    @Test fun toolNamesAreValidated() {
        assertTrue(runCatching { MindToolSpec("phone.tap", "x") }.isFailure)
        assertTrue(runCatching { MindToolSpec("tap", "x") }.isSuccess)
    }
}
