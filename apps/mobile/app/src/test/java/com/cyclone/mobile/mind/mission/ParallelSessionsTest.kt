package com.cyclone.mobile.mind.mission

import com.cyclone.mobile.policy.PublishGate
import com.cyclone.mobile.runtime.background.TaskPhase
import com.cyclone.mobile.runtime.background.WorkspaceTaskUi
import com.cyclone.mobile.task.TaskCommand
import com.cyclone.mobile.task.TaskCommandBus
import com.cyclone.mobile.task.TaskCommandResult
import com.cyclone.mobile.task.TaskController
import com.cyclone.mobile.task.TaskEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.concurrent.thread

/** Plan 26 §6 (alpha.65): one mission in front, up to two behind it, one shared inbox, Task Kit for all of them. */
class ParallelSessionsTest {
    private val gb = 1_000_000_000L

    @Test
    fun memoryDecidesHowManyTasksWorkBehind() {
        assertEquals(0, Crew.behindSlots(4 * gb))
        assertEquals(1, Crew.behindSlots(6 * gb))
        assertEquals(2, Crew.behindSlots(8 * gb))
        assertEquals(2, Crew.behindSlots(12 * gb))
    }

    private fun facts(frontLive: Boolean = true, behind: Int = 0, slots: Int = 2, blocker: String? = null, enabled: Boolean = true,
                      lab: Boolean = false, hands: Boolean = false) =
        Crew.Facts(enabled, frontLive, behind, slots, blocker, lab, hands)

    @Test
    fun admissionTableCoversEveryRow() {
        assertEquals(Crew.Admit.Front, Crew.admit(facts(frontLive = false, slots = 0, blocker = "off", enabled = false)))
        assertEquals(Crew.Admit.Behind, Crew.admit(facts()))
        assertEquals(Crew.Admit.Behind, Crew.admit(facts(behind = 1)))
        assertTrue(Crew.admit(facts(behind = 2)) is Crew.Admit.Queue)
        assertTrue(Crew.admit(facts(slots = 1, behind = 1)) is Crew.Admit.Queue)
        assertTrue(Crew.admit(facts(slots = 0)) is Crew.Admit.Queue)
        assertTrue(Crew.admit(facts(enabled = false)) is Crew.Admit.Queue)
        assertEquals("Turn on background work.", (Crew.admit(facts(blocker = "Turn on background work.")) as Crew.Admit.Queue).reason)
        assertTrue("a Lab run always works alone", Crew.admit(facts(lab = true)) is Crew.Admit.Queue)
        assertTrue("a task that needs your hands waits for the screen", Crew.admit(facts(hands = true)) is Crew.Admit.Queue)
        assertTrue(Crew.needsHands("Log in to my bank and check the balance"))
        assertFalse(Crew.needsHands("Order oat milk on the groceries app"))
    }

    @Test
    fun aBehindMissionNeverTouchesTheOwnersScreen() {
        for (tool in listOf("screen_read", "screen_look", "tap", "type_text", "scroll", "back", "home", "open_link", "open_settings", "go_to")) {
            assertNotNull(tool, Crew.behindRefusal(tool, hasOwnScreen = false))
        }
        for (tool in listOf("open_app", "set_timer", "set_alarm")) assertNull(tool, Crew.behindRefusal(tool, hasOwnScreen = false))
        assertNull("with a background screen of its own, it works there", Crew.behindRefusal("tap", hasOwnScreen = true))
        assertTrue(Crew.BEHIND_SITUATION.contains("never see or touch their screen"))
        assertEquals("b", Crew.next(listOf("a" to 30L, "b" to 10L, "c" to 20L)))
        assertNull(Crew.next(emptyList()))
        assertEquals("For “Order oat milk”:", Crew.label("Order oat milk"))
        assertTrue(Crew.label("x".repeat(60)).endsWith("…”:"))
    }

    @Test
    fun oneInboxHoldsOneOpenRequestPerMissionAndShowsThemOneAtATime() {
        val inbox = OwnerInbox()
        inbox.label("behind", Crew.label("Order oat milk"))
        val front = inbox.post("front", OwnerRequestKind.QUESTION, "Which account?")
        val behind = inbox.post("behind", OwnerRequestKind.APPROVAL, "Cyclone wants to: tap Pay")
        assertEquals("the oldest is on screen", front, inbox.pending.value)
        assertEquals(2, inbox.all.value.size)
        assertEquals("For “Order oat milk”: Cyclone wants to: tap Pay", behind.text)
        assertEquals(behind, inbox.openFor("behind"))
        // A mission's new request replaces its own previous one, never another mission's.
        val again = inbox.post("front", OwnerRequestKind.QUESTION, "Which card?")
        assertTrue(inbox.await(front, 1_000) { false }.cancelled)
        assertEquals(listOf(behind, again), inbox.all.value)
        // Answers go to the request they name, whichever mission it belongs to.
        thread { Thread.sleep(50); assertTrue(inbox.respond(behind.id, OwnerResponse.Approve)) }
        assertEquals(OwnerResponse.Approve, inbox.await(behind, 5_000) { false }.response)
        assertEquals(again, inbox.pending.value)
        // Stopping one mission withdraws only its request.
        inbox.post("behind", OwnerRequestKind.QUESTION, "?")
        inbox.withdrawMission("behind")
        assertEquals(listOf(again), inbox.all.value)
        inbox.label("front", null)
        assertEquals("Next?", inbox.post("front", OwnerRequestKind.QUESTION, "Next?").text)
    }

    private fun task(id: String) = WorkspaceTaskUi(id, "default-foreground", "Cyclone Mind", "", "goal", phase = TaskPhase.WORKING,
        engine = TaskEngine.MIND, displayId = 0)

    @Test
    fun taskKitReachesTasksBehindTheFrontOne() {
        val handled = mutableListOf<String>()
        val controller = object : TaskController {
            override val engine = TaskEngine.MIND
            override val supported: Set<Class<out TaskCommand>> = setOf(TaskCommand.Stop::class.java)
            override fun handle(task: WorkspaceTaskUi, command: TaskCommand): TaskCommandResult {
                handled += task.taskId
                return TaskCommandResult.done(engine, "ok")
            }
        }
        val bus = TaskCommandBus(listOf(controller), current = { task("mission-front") }, others = { listOf(task("mission-behind")) })
        assertTrue(bus.send("mission-behind", TaskCommand.Stop).handled)
        assertTrue(bus.send("mission-front", TaskCommand.Stop).handled)
        assertFalse(bus.send("mission-gone", TaskCommand.Stop).handled)
        assertEquals(listOf("mission-behind", "mission-front"), handled)
    }

    @Test
    fun thePublishGateStaysOnWhileAnyPostingTaskRuns() {
        val running = mutableSetOf("m-post", "m-other")
        PublishGate.running = { it in running }
        try {
            PublishGate.mark("m-post", publish = true)
            PublishGate.mark("m-other", publish = false)
            assertTrue("starting another task never turns the gate off", PublishGate.active())
            assertTrue(PublishGate.gates("Share"))
            running -= "m-post"
            assertFalse(PublishGate.active())
        } finally {
            PublishGate.running = null
            PublishGate.missionId = null
        }
    }
}
