package com.cyclone.mobile.mind.pilot

import com.cyclone.mobile.mind.MindApp
import com.cyclone.mobile.mind.MindDevicePort
import com.cyclone.mobile.mind.MindToolCall
import com.cyclone.mobile.mind.PhoneMindToolbox
import com.cyclone.mobile.mind.PhoneMindToolboxTest.Control
import com.cyclone.mobile.mind.PhoneMindToolboxTest.FakeEnv
import com.cyclone.mobile.mind.PhoneMindToolboxTest.FakeOwner
import com.cyclone.mobile.mind.PhoneMindToolboxTest.FakeScreen
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Plan 41: the `pilot` tool inside the real toolbox, with a fake phone and a scripted fast model. */
class PilotToolboxTest {
    private val device = object : MindDevicePort {
        override fun apps() = listOf(MindApp("com.whatsapp", "WhatsApp"), MindApp("com.revolut.revolut", "Revolut"))
        override fun now() = "Monday 18:20"
        override fun device() = "Test phone"
        override fun sleep(ms: Long) {}
    }
    private val chats = FakeScreen("com.whatsapp", listOf(Control("louella", "Louella"), Control("search", "Search")), listOf("Chats"))
    private val chat = FakeScreen("com.whatsapp", listOf(Control("box", "Message", "edit_text", editable = true), Control("send", "Send")), listOf("Louella"))
    private val steps = """{"steps":[{"do":"Open the chat with Louella","expect":"the chat is open"},{"do":"Type the ETA","text":"On my way"}]}"""

    /** Picks the option whose meaning mentions [label], or [fallback]. */
    private fun picking(vararg script: Pair<String, String>): PilotDecider {
        val queue = ArrayDeque(script.toList())
        return PilotDecider { q ->
            val (want, kind) = queue.removeFirstOrNull() ?: return@PilotDecider null
            val choice = if (kind == "label") q.options.entries.first { it.value.contains(want) }.key else want
            PilotAnswer(choice, 0.95, if (choice == Pilot.HAND_BACK) "unexpected_screen" else null, 300)
        }
    }

    private fun box(env: FakeEnv, decider: PilotDecider?) = PhoneMindToolbox(env, FakeOwner(), device, "Send Louella my ETA",
        fast = decider?.let { PilotSetup(it, PilotSettings(0.85, images = false), "test/fast") })

    private fun PhoneMindToolbox.pilot(args: String) = execute(MindToolCall("c", "pilot", args), JSONObject(args))

    @Test fun thePilotIsOfferedOnlyInFastMode() {
        assertFalse(box(FakeEnv(chats), null).specs().any { it.name == "pilot" })
        assertTrue(box(FakeEnv(chats), picking()).specs().any { it.name == "pilot" })
    }

    @Test fun routineStepsRunThroughTheMindsOwnActPath() {
        val env = FakeEnv(chats)
        env.onAct = { tool, params -> if (tool == "phone.click" && params.optString("elementId").endsWith(":louella")) env.screen = chat }
        val toolbox = box(env, picking("Louella" to "label", Pilot.STEP_DONE to "id", "Message" to "label", Pilot.STEP_DONE to "id"))
        val result = toolbox.pilot(steps)
        assertTrue(result.text, result.ok)
        assertTrue(result.changedScreen)
        assertEquals(listOf("phone.click", "phone.type"), env.acts.map { it.first })
        assertTrue(result.text, result.text.contains("All 2 steps done"))
        // The Mind sees the real screen after the record.
        assertTrue(result.text, result.text.contains("Screen:"))
    }

    @Test fun theFastModelsHandBackReachesTheMindWithTheScreen() {
        val env = FakeEnv(chats)
        val result = box(env, picking(Pilot.HAND_BACK to "id")).pilot(steps)
        assertFalse(result.ok)
        assertFalse(result.changedScreen)
        assertTrue(env.acts.isEmpty())
        assertTrue(result.text, result.text.contains("The rapid model handed step 1 back to you") && result.text.contains("unexpected screen"))
        assertTrue(result.brief, result.brief.contains("handed back"))
    }

    @Test fun passwordScreensAndKeptOffAppsAreHandedBackWithoutAQuestion() {
        var asked = 0
        val counting = PilotDecider { asked++; null }
        val login = FakeScreen("com.example.shop", listOf(Control("user", "Email", editable = true), Control("pw", "Password", editable = true, password = true)))
        val bank = FakeScreen("com.revolut.revolut", listOf(Control("pay", "Transfer")))
        listOf(login, bank).forEach { screen ->
            val env = FakeEnv(screen)
            val result = box(env, counting).pilot(steps)
            assertFalse(result.ok)
            assertTrue(result.text, result.text.contains("password, code or card field, or the app is kept out of Fast mode"))
            assertTrue(env.acts.isEmpty())
        }
        assertEquals(0, asked)
    }

    @Test fun toolMovesGoThroughTheMindsOwnTools() {
        val env = FakeEnv(chats)
        val toolbox = box(env, picking(Pilot.OPEN_APP to "id", Pilot.STEP_DONE to "id"))
        val result = toolbox.pilot("""{"steps":[{"do":"Open WhatsApp","app":"WhatsApp","expect":"WhatsApp is open"}]}""")
        assertTrue(result.text, result.ok)
        assertEquals(listOf("phone.open_app"), env.acts.map { it.first })
    }

    @Test fun anUnplannedIrreversibleTapNeverHappensWithoutTheSmartModel() {
        val env = FakeEnv(chat)
        val result = box(env, picking("Send" to "label")).pilot("""{"steps":[{"do":"Send it"}]}""")
        assertFalse(result.ok)
        assertTrue(env.acts.isEmpty())
        assertTrue(result.text, result.text.contains("looks irreversible (Send)"))
    }

    @Test fun stepsAreRequired() {
        assertTrue(box(FakeEnv(chats), picking()).pilot("{}").text.startsWith("ERROR"))
    }
}
