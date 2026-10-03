package com.cyclone.mobile.mind.workspace

import com.cyclone.mobile.mind.MindApp
import com.cyclone.mobile.mind.MindConversation
import com.cyclone.mobile.mind.MindDevicePort
import com.cyclone.mobile.mind.MindLoop
import com.cyclone.mobile.mind.MindMessage
import com.cyclone.mobile.mind.MindModel
import com.cyclone.mobile.mind.MindModelReply
import com.cyclone.mobile.mind.MindModelRequest
import com.cyclone.mobile.mind.MindStatus
import com.cyclone.mobile.mind.MindToolCall
import com.cyclone.mobile.mind.MindToolbox
import com.cyclone.mobile.mind.MindUsage
import com.cyclone.mobile.mind.PhoneMindToolbox
import com.cyclone.mobile.mind.PhoneMindToolboxTest.Control
import com.cyclone.mobile.mind.PhoneMindToolboxTest.FakeEnv
import com.cyclone.mobile.mind.PhoneMindToolboxTest.FakeOwner
import com.cyclone.mobile.mind.PhoneMindToolboxTest.FakeScreen
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Plan 37 §15: the workspace is guidance, not gates. No new tools; a model that never uses the new arguments works
 * as in a classic run; nothing in the workspace refuses an action; folded turns come back byte-exact.
 */
class WorkspaceFlexibilityTest {
    private val device = object : MindDevicePort {
        override fun apps() = listOf(MindApp("com.instagram.android", "Instagram"), MindApp("com.whatsapp", "WhatsApp"),
            MindApp("com.google.android.apps.maps", "Maps"))
        override fun now() = "Monday 18:20"
        override fun device() = "Test phone"
        override fun sleep(ms: Long) {}
    }
    private val chats = FakeScreen("com.whatsapp", listOf(Control("louella", "Louella ❤️"), Control("search", "Search")), listOf("Chats", "Louella ❤️"))
    private val chat = FakeScreen("com.whatsapp", listOf(Control("box", "Message", "edit_text", editable = true), Control("send", "Send")),
        listOf("Louella ❤️", "Kerkstraat 12"))
    private val maps = FakeScreen("com.google.android.apps.maps", listOf(Control("bike", "Bike")), listOf("Directions", "22 min"))

    private fun MindToolbox.run(name: String, args: String = "{}") = execute(MindToolCall("c", name, args), JSONObject(args))

    @Test
    fun theWorkspaceAddsNoToolsOnlyOptionalArguments() {
        val classic = PhoneMindToolbox(FakeEnv(chats), FakeOwner(), device, "goal").specs()
        val workspace = PhoneMindToolbox(FakeEnv(chats), FakeOwner(), device, "goal", workspace = MissionWorkspace()).specs()
        assertEquals(classic.map { it.name }, workspace.map { it.name })
        classic.zip(workspace).filter { it.first.name != "recall" }.forEach { (a, b) ->
            assertEquals("${a.name}: nothing new is required", a.parameters.optJSONArray("required")?.toString(),
                b.parameters.optJSONArray("required")?.toString())
        }
        val tap = workspace.first { it.name == "tap" }.parameters.getJSONObject("properties")
        assertTrue(tap.has("expect") && tap.has("step"))
        assertEquals("[]", workspace.first { it.name == "recall" }.parameters.getJSONArray("required").toString())
        assertTrue(workspace.first { it.name == "open_app" }.parameters.getJSONObject("properties").has("resume"))
    }

    @Test
    fun aModelThatIgnoresTheWorkspaceWorksAsInAClassicRun() {
        val env = FakeEnv(chats)
        env.onAct = { _, _ -> env.screen = chat }
        val ws = MissionWorkspace()
        val box = PhoneMindToolbox(env, FakeOwner(), device, "Tell Louella I'm on my way", workspace = ws)
        assertTrue(box.run("screen_read").ok)
        assertTrue(box.run("tap", """{"ref":"e1"}""").ok)
        assertTrue(box.run("plan_update", """{"steps":[{"step":"Open her chat","status":"done"},{"step":"Write it","status":"doing"}]}""").ok)
        assertTrue(box.run("note", """{"text":"She lives at Kerkstraat 12"}""").ok)
        val finish = box.run("task_finish", """{"summary":"Done","evidence":"Chat open"}""")
        assertTrue("no done checks were given, so nothing blocks the finish", finish.ok)
        assertEquals(com.cyclone.mobile.mind.MindEnding.COMPLETED, finish.ending)
    }

    @Test
    fun expectationsAndWhatChangedAppearUnderTheNewScreen() {
        val env = FakeEnv(chats)
        env.onAct = { _, _ -> env.screen = chat }
        val box = PhoneMindToolbox(env, FakeOwner(), device, "goal", workspace = MissionWorkspace())
        box.run("screen_read")
        val held = box.run("tap", """{"ref":"e1","expect":"chat with \"Louella ❤️\" opens","step":1}""")
        assertTrue(held.text, held.text.contains("Check: ✓"))
        assertTrue(held.text.contains("Changed:"))
        env.onAct = { _, _ -> }
        val same = box.run("tap", """{"ref":"e2","expect":"it sends"}""")
        assertTrue(same.text, same.text.contains("Check: ~"))
    }

    @Test
    fun switchingAppsKeepsTheWayBackAndNotesStayInTheLiveState() {
        val env = FakeEnv(chat)
        val ws = MissionWorkspace()
        val box = PhoneMindToolbox(env, FakeOwner(), device, "goal", workspace = ws)
        ws.beginTurn(1, 2, 0, 0)
        box.run("screen_read")
        assertTrue(box.run("note", """{"text":"Kerkstraat 12","key":"address"}""").ok)
        assertFalse("secrets are never collected", box.run("note", """{"text":"password: hunter2"}""").ok)
        ws.beginTurn(2, 5, 0, 0)
        env.onAct = { _, params -> env.screen = if (params.optString("package") == "com.whatsapp") chat else maps }
        val opened = box.run("open_app", """{"app":"Maps","why":"bike time","carry":"reply with the ETA"}""")
        assertTrue(opened.ok)
        assertEquals("Maps", ws.currentStay?.app)
        ws.beginTurn(3, 8, 0, 0)
        val back = box.run("open_app", """{"app":"WhatsApp","resume":true}""")
        assertTrue(back.text, back.text.contains("Where this mission left WhatsApp (stay 1)"))
        val state = ws.liveState()!!
        assertTrue(state.contains("address = Kerkstraat 12 (WhatsApp, t1)"))
        assertTrue(state.contains("came from Maps"))
    }

    @Test
    fun aDoneCheckNudgesOnceThenTheFinishIsAlwaysAccepted() {
        val env = FakeEnv(chat)
        val box = PhoneMindToolbox(env, FakeOwner(), device, "goal", workspace = MissionWorkspace())
        box.run("screen_read")
        assertTrue(box.run("plan_update", """{"steps":["Send it"],"done":[{"kind":"sent_to","value":"Louella on WhatsApp"}]}""").ok)
        val first = box.run("task_finish", """{"summary":"Sent","evidence":"The chat"}""")
        assertFalse(first.ok)
        assertTrue(first.text.contains("call task_finish again"))
        val second = box.run("task_finish", """{"summary":"Sent","evidence":"The chat"}""")
        assertTrue(second.ok)
        assertEquals(com.cyclone.mobile.mind.MindEnding.COMPLETED, second.ending)
    }

    @Test
    fun aDiversionIsShownToTheOwnerAndNeverStopsTheMission() {
        val owner = object : com.cyclone.mobile.mind.MindOwnerPort by FakeOwner() {
            val seen = mutableListOf<String>()
            override fun diverted(from: String, to: String, why: String) { seen += "$from → $to ($why)" }
        }
        val ws = MissionWorkspace()
        val box = PhoneMindToolbox(FakeEnv(chat), owner, device, "goal", workspace = ws)
        val result = box.run("plan_update", """{"steps":["Message on WhatsApp"],"divert":{"from":"DM on Instagram","to":"WhatsApp","why":"DMs closed"}}""")
        assertTrue(result.ok)
        assertEquals(listOf("DM on Instagram → WhatsApp (DMs closed)"), owner.seen)
        assertTrue(ws.liveState()!!.contains("Diverted: DM on Instagram → WhatsApp (DMs closed)"))
    }

    private class Scripted(private val replies: MutableList<List<Pair<String, String>>>) : MindModel {
        override val id = "m"
        override val label = "m"
        override val vision = false
        val requests = mutableListOf<MindModelRequest>()
        override fun complete(request: MindModelRequest): MindModelReply {
            requests += request
            val calls = replies.removeAt(0)
            return MindModelReply("", calls.mapIndexed { i, (n, a) -> MindToolCall("c${requests.size}_$i", n, a) }, usage = MindUsage(10, 5, 0.0))
        }
    }

    @Test
    fun theLoopSendsTheLiveStateLastNeverStoresItAndRecallUnfoldsAFoldedTurn() {
        val env = FakeEnv(chat)
        env.onAct = { _, params -> env.screen = if (params.optString("package") == "com.whatsapp") chat else maps }
        val ws = MissionWorkspace()
        val box = PhoneMindToolbox(env, FakeOwner(), device, "goal", workspace = ws)
        val model = Scripted(mutableListOf(
            listOf("screen_read" to "{}"),
            listOf("plan_update" to """{"steps":[{"step":"Read address","status":"done"},{"step":"Bike time","status":"doing"}]}"""),
            listOf("open_app" to """{"app":"Maps"}"""),
            listOf("recall" to """{"turn":1}"""),
            listOf("task_finish" to """{"summary":"ok","evidence":"22 min"}"""),
        ))
        val conversation = MindConversation(listOf(MindMessage.System("sys"), MindMessage.User("goal")))
        val outcome = MindLoop(model, null, box, workspace = ws).run(conversation)
        assertEquals(MindStatus.COMPLETED, outcome.status)
        val last = model.requests.last().messages
        val tail = last.getJSONObject(last.length() - 1)
        assertTrue(tail.getString("content").startsWith("LIVE STATE"))
        assertTrue("never stored", conversation.all().none { it is MindMessage.User && it.text.startsWith("LIVE STATE") })
        assertTrue("the WhatsApp stay was folded with a journal block",
            conversation.all().any { it is MindMessage.User && it.text.startsWith("JOURNAL · stay 1 · WhatsApp") })
        val recalled = conversation.all().filterIsInstance<MindMessage.Tool>().first { it.name == "recall" }
        assertTrue(recalled.full, recalled.full.contains("Kerkstraat 12"))
        val classic = Scripted(mutableListOf(listOf("screen_read" to "{}"), listOf("task_finish" to """{"summary":"ok","evidence":"x"}""")))
        MindLoop(classic, null, PhoneMindToolbox(FakeEnv(chat), FakeOwner(), device, "goal"))
            .run(MindConversation(listOf(MindMessage.System("sys"), MindMessage.User("goal"))))
        assertNull("a classic run has no tail", classic.requests.last().messages.let { m ->
            m.getJSONObject(m.length() - 1).optString("content").takeIf { it.startsWith("LIVE STATE") } })
    }
}
