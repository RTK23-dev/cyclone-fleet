package com.cyclone.mobile.task

import com.cyclone.mobile.runtime.background.TaskInterruption
import com.cyclone.mobile.runtime.background.TaskNotificationProjection
import com.cyclone.mobile.runtime.background.TaskPhase
import com.cyclone.mobile.runtime.background.WorkspaceConfirmation
import com.cyclone.mobile.runtime.background.WorkspaceTaskUi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskKitTest {
    private fun task(id: String, foreground: Boolean = true, engine: TaskEngine? = null) = WorkspaceTaskUi(
        taskId = id, sessionId = if (foreground) "default-foreground" else "ws-1", app = "App", packageName = "com.example",
        goal = "goal", displayId = if (foreground) 0 else 7, workspaceId = if (foreground) null else "ws", engine = engine)

    private class Fake(override val engine: TaskEngine, override val supported: Set<Class<out TaskCommand>>) : TaskController {
        val seen = mutableListOf<TaskCommand>()
        override fun handle(task: WorkspaceTaskUi, command: TaskCommand): TaskCommandResult {
            seen += command
            if (command == TaskCommand.Pause) error("boom")
            return TaskCommandResult.done(engine, "ok")
        }
    }

    @Test fun everyLegacyActionStringParses() {
        mapOf("cancel" to TaskCommand.Stop, "handoff" to TaskCommand.TakeOver, "pause" to TaskCommand.Pause,
            "resume" to TaskCommand.Done, "done" to TaskCommand.Done, "autofill" to TaskCommand.Autofill,
            "approve" to TaskCommand.Approve, "decline" to TaskCommand.Decline).forEach { (wire, command) ->
            assertEquals(wire, command, TaskCommand.parse(wire))
        }
        assertEquals(TaskCommand.Confirm("t1"), TaskCommand.parse("confirm", "t1"))
        assertEquals(null, TaskCommand.parse("explode"))
        // Commands that carry what the owner typed only parse with that input; nothing blank reaches an engine.
        val typed = setOf(TaskCommand.Reply::class, TaskCommand.Fill::class, TaskCommand.Steer::class, TaskCommand.Queue::class, TaskCommand.Parallel::class)
        TaskCommand.ALL.filterNot { it::class in typed }.forEach { assertNotNull(it.wire, TaskCommand.parse(it.wire)) }
        // Plan 38: Steer, Queue and Parallel carry the owner's text too; blank never reaches an engine.
        listOf("steer", "queue", "parallel").forEach { assertEquals(null, TaskCommand.parse(it, text = "  ")) }
        assertEquals(TaskCommand.Steer("make it 3 minutes"), TaskCommand.parse("steer", text = " make it 3 minutes "))
        assertEquals(null, TaskCommand.parse("reply", text = "   "))
        assertEquals(TaskCommand.Reply("the blue one"), TaskCommand.parse("reply", text = " the blue one "))
        assertEquals(null, TaskCommand.parse("fill"))
    }

    @Test fun eachTaskHasExactlyOneEngine() {
        assertEquals(TaskEngine.MIND, TaskEngines.of(task("mission-m1")))
        assertEquals(TaskEngine.MIND, TaskEngines.of(task("anything", engine = TaskEngine.MIND)))
        assertEquals(TaskEngine.CLASSIC_FOREGROUND, TaskEngines.of(task("foreground-1")))
        assertEquals(TaskEngine.BACKGROUND_WORKSPACE, TaskEngines.of(task("bg-1", foreground = false)))
    }

    @Test fun theBusRoutesToTheOwnerAndNeverDropsSilently() {
        val mind = Fake(TaskEngine.MIND, setOf(TaskCommand.Done::class.java, TaskCommand.Pause::class.java))
        val classic = Fake(TaskEngine.CLASSIC_FOREGROUND, setOf(TaskCommand.Done::class.java))
        var current: WorkspaceTaskUi? = task("mission-m1")
        val log = mutableListOf<TaskCommandResult>()
        val bus = TaskCommandBus(listOf(mind, classic), { current }, { _, _, result -> log += result })

        assertTrue(bus.send("mission-m1", TaskCommand.Done).handled)
        assertEquals(listOf<TaskCommand>(TaskCommand.Done), mind.seen)
        assertTrue(classic.seen.isEmpty())

        val unsupported = bus.send("mission-m1", TaskCommand.Autofill)
        assertFalse(unsupported.handled)
        assertTrue(unsupported.detail.contains("not available"))

        val crashed = bus.send("mission-m1", TaskCommand.Pause)
        assertFalse(crashed.handled)
        assertTrue(crashed.detail.contains("boom"))

        assertFalse(bus.send("someone-else", TaskCommand.Done).handled)
        current = task("bg-1", foreground = false)
        assertTrue(bus.send("bg-1", TaskCommand.Done).detail.contains("No controller"))
        assertEquals("every command is recorded, handled or not", 5, log.size)
    }

    @Test fun oneControllerPerEngine() {
        val a = Fake(TaskEngine.MIND, emptySet())
        assertTrue(runCatching { TaskCommandBus(listOf(a, Fake(TaskEngine.MIND, emptySet())), { null }) }.isFailure)
    }

    /**
     * The contract matrix. Every engine offers the commands every surface shows (Stop, Take over, I'm done), and no
     * surface can offer a button the owning engine cannot handle. The alpha.27 "I'm done does nothing" bug was a
     * button with no engine behind it.
     */
    @Test fun everyButtonASurfaceCanShowHasAnEngineBehindIt() {
        val controllers = listOf(MindTaskController, ClassicForegroundTaskController, BackgroundWorkspaceTaskController)
        assertEquals(TaskEngine.entries.toSet(), controllers.map { it.engine }.toSet())
        controllers.forEach { controller ->
            listOf(TaskCommand.Stop, TaskCommand.TakeOver, TaskCommand.Done, TaskCommand.Pause).forEach {
                assertTrue("${controller.engine} must support ${it.label}", controller.supports(it))
            }
        }
        val interruption = TaskInterruption("x", "Do it", canTakeOver = true, canResumeAfterHuman = true, canAutofill = true, confirmationToken = "tok")
        val states = listOf(
            task("mission-m1", engine = TaskEngine.MIND).copy(phase = TaskPhase.HUMAN, interruption = interruption.copy(canAutofill = false)),
            task("mission-m1", engine = TaskEngine.MIND).copy(phase = TaskPhase.WORKING),
            task("foreground-1").copy(phase = TaskPhase.REVIEW, interruption = interruption),
            task("bg-1", foreground = false).copy(phase = TaskPhase.REVIEW, interruption = interruption,
                confirmation = WorkspaceConfirmation("tok", "click", "n1", "f1", "send")),
        )
        states.forEach { state ->
            val controller = controllers.first { it.engine == TaskEngines.of(state) }
            val planes = if (TaskEngines.of(state) == TaskEngine.MIND) listOf(null, "screen", "background") else listOf(null)
            planes.flatMap { TaskNotificationProjection.actions(state, it) + TaskNotificationProjection.actions(state, it, waiting = true) }.forEach { (wire, label) ->
                val command = TaskCommand.parse(wire, state.confirmation?.token)
                assertNotNull("$label parses", command)
                assertTrue("${controller.engine} handles the '$label' button", controller.supports(command!!))
            }
        }
    }
}
